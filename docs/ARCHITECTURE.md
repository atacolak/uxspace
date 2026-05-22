# VSpace Architecture v2 — a spatial window manager

## 1. Why reorganize

`WorkspaceRenderer` has grown into a ~700-line god object. It currently owns GL setup
and shaders, the head-tracked camera, screen quads, the wallpaper, the cursor, the
taskbar, the drawer panel, the app-icon grid, all hit-testing, frame capture, and app
launching.

Every new DeX element — system tray, clock, window chrome, animations, multi-screen
layouts — means another hand-placed GL quad and another bespoke hit-test. That does not
scale.

The fix: separate concerns into **three libraries + the application**, along the four
layers below.

## 2. The four layers

| # | Layer        | What it is                                                            | Module     | Key types                                      |
|---|--------------|-----------------------------------------------------------------------|------------|------------------------------------------------|
| 1 | **View**     | The 3D stage — a head-tracked camera compositing Screens in a layout  | `:spatial` | `Scene`, `Camera`, `ScreenLayout`, `Surface3D` |
| 2 | **Screen**   | An N×M content surface that becomes a texture in the scene            | `:spatial` | `Screen`, `AppScreen`, `UiScreen`              |
| 3 | **UI framework** | A 2D widget toolkit — widgets, layout, input, animation           | `:ui`      | `Widget`, `UiRoot`, `Button`, `IconGrid`, `Animator` |
| 4 | **UI layout**| A concrete screenful of widgets — the DeX desktop                     | `:app`     | `DesktopLayout`, `Taskbar`, `AppDrawer`        |

Plus `:glasses` — the VITURE hardware (USB, native SDK, head tracking). Already
well-isolated; promoted to its own library.

## 3. Modules and dependencies

```
:glasses     VITURE USB + native SDK + head tracking          -> Android + native .so
:privileged  bundled Shizuku server + client + Wi-Fi pairing   -> Android
:ui          cursor, input mapping, shared One UI components   -> Android
:spatial     GL toolkit + Camera + Screen + Scene              -> :ui
:app         control panel + desktop layout + wiring           -> :spatial, :ui, :glasses, :privileged
```

Acyclic. `:glasses` and `:privileged` stand alone. The native CMake build moves into
`:glasses`. See §8 for `:privileged`.

## 4. Layer detail

### Layer 1 — View (`:spatial`, package `scene`)

- `Camera` — owns the projection + view matrix and the **ViewMode** (PINNED / FREE).
  Consumes a head-pose quaternion: PINNED → identity view; FREE → inverse head rotation.
- `Surface3D` — a textured quad with a 3D transform (position, yaw, size). The atomic
  visible thing.
- `ScreenLayout` — a placement strategy: `Single`, `ArcOfThree`, `Stack`. Maps logical
  screen slots → `Surface3D` transforms. (M3's arc, generalized.)
- `Scene` — holds the camera, the wallpaper, and the `Surface3D` list; draws them each
  frame. The slimmed-down successor to `WorkspaceRenderer`.

The View knows nothing about what is *inside* a Screen.

### Layer 2 — Screen (`:spatial`, package `screen`)

Every `Screen` is a `VirtualDisplay` rendered to a `SurfaceTexture` → GL texture — one
uniform mechanism.

- `DisplayScreen` — base: owns the `VirtualDisplay`, `SurfaceTexture`, and external-OES
  texture; exposes pixel size, `updateTexture()`, and `dispatchInput(x, y, …)`.
- `AppScreen` — a `DisplayScreen` showing a third-party app, launched onto it via
  Shizuku. (Today's `VirtualScreen`.)
- `UiScreen` — a `DisplayScreen` showing *our* `Presentation` — a real Android layout.
  The desktop (wallpaper + taskbar + drawer) is a `UiScreen`. Input dispatches straight
  into its view tree; no Shizuku needed, since it is our own window.

The View composites both kinds identically — each is just a textured quad.

### Layer 3 — UI framework (`:ui`)

With Option C the heavy lifting is Android's own widget toolkit, so `:ui` stays thin:

- `Cursor` — the pointer overlay: a static texture the View moves each frame, never
  re-rasterised.
- `CursorInput` — maps a cursor position to the front-most Screen and its pixel
  coordinates, and synthesises the `MotionEvent` / `KeyEvent` to dispatch.
- Reusable One UI-themed view components shared across layouts (taskbar item, app-grid
  cell, …) — ordinary Android `View`s, themed by `Theme.DeviceDefault`.

### Layer 4 — UI layout (`:app`, package `desktop`)

- `DesktopPresentation` — the content shown on the desktop `UiScreen`: an Android layout
  on `Theme.DeviceDefault` (One UI on Samsung) with a wallpaper, a `Taskbar` (launcher;
  later tray + clock) and an `AppDrawer` (a `RecyclerView` icon grid). Standard Android
  click listeners and animations.
- Future layouts reuse the same components.

## 5. Input flow

```
Trackpad (phone)  --delta-->  Scene moves the cursor overlay
Click  -->  Scene projects the cursor onto the front-most Surface3D
        |-- UiScreen  -> UiRoot hit-tests widgets -> fires onClick
        '-- AppScreen -> inject a touch event into the VirtualDisplay (M4, via Shizuku)
Keyboard  -->  routed to the focused Screen the same way
```

The cursor lives in the View; the View maps cursor → Screen → screen-local pixels; each
Screen handles input in its own space. The UI framework only ever sees clean 2D
widget-space events.

## 6. Migration — incremental, app stays runnable at every step

Sequenced value-first: each step is visible and self-contained. New code lands in clean
packages now; Gradle modules are extracted once the structure has settled.

1. **`UiScreen` + the real desktop.** Build `DisplayScreen` / `UiScreen` and a
   `DesktopPresentation` — a `Theme.DeviceDefault` Android layout with wallpaper +
   taskbar + app drawer. The renderer draws this `UiScreen` instead of the hand-drawn
   GL quads; cursor clicks dispatch into it. Proves Option C end-to-end and is the
   visible "looks like DeX" win.
2. **Extract `:glasses`.** Move head tracking + the native build into a library — it is
   already isolated, lowest risk.
3. **Extract `:spatial`.** Pull `Camera`, `Surface3D`, `DisplayScreen` / `AppScreen` /
   `UiScreen`, `ScreenLayout`, `Scene` out of `WorkspaceRenderer`; the renderer becomes
   a thin `Scene` driver.
4. **Extract `:ui`.** Cursor, input mapping, shared view components.
5. **Bundle Shizuku** into `:privileged` — server in the APK, in-app Wireless-Debugging
   pairing; drop the separate-app dependency. Independent of steps 1–4; can run in
   parallel. See §8.
6. **Then** DeX features — system tray, clock, window chrome, multi-screen layouts —
   are each just a new view component or a new `ScreenLayout`.

Each step leaves a working app.

## 7. Decision — how a `UiScreen` renders (and can we reuse One UI?)

**Can we use One UI components?** Samsung does not publish One UI as a developer
library. But its look is not in a library — it is baked into the *device's framework*:
standard Android widgets (`Button`, `TextView`, `RecyclerView`, …) are automatically
themed as One UI on a Samsung device when the app theme derives from
`Theme.DeviceDefault`. That is exactly how Samsung DeX itself is styled. So we can have
the One UI look "for free" — by rendering the UI with **real Android widgets**, not by
hand-drawing it.

That tilts the rendering choice:

- **A — Canvas → texture.** The UI framework hand-draws every widget to a `Bitmap`.
  Full control, render-agnostic, testable headless. But we must *recreate* the One UI
  look ourselves — colours, metrics, fonts, ripples — and it will never quite match,
  nor track Samsung updates.
- **C — Android Views on a VirtualDisplay (recommended).** The desktop UI is a real
  Android layout shown in a `Presentation` on an internal `VirtualDisplay`, rendered to
  a `SurfaceTexture` → GL texture — the *same* pipeline `AppScreen` already uses. With
  the app theme on `Theme.DeviceDefault`, every widget is One UI-styled by the device,
  for free. Cursor clicks dispatch straight into that Presentation's view tree
  (`dispatchTouchEvent`) — no Shizuku, since it is our own window. Animations are
  Android's own and simply appear in the sampled texture.

Under **C**, Layer 2 unifies: `UiScreen` and `AppScreen` are both "a VirtualDisplay
behind a quad" — `UiScreen` shows *our* Presentation, `AppScreen` shows a third-party
app. Layer 3 (`:ui`) shrinks to the cursor overlay, the cursor → display input mapping,
and a small set of reusable layout pieces; Layer 4 becomes an ordinary Android layout.

Trade-off: the One UI look appears only on One UI devices (stock Material elsewhere) —
fine for a Samsung-targeted app. Unofficial `sesl` / `oneui-design` GitHub ports exist
if the look is ever needed off-Samsung.

**Decided: C** — it is how DeX itself is built, reuses our existing display → texture
pipeline, and gives the One UI look without rebuilding it.

## 8. Privileged access — bundling Shizuku

VSpace needs shell-level privilege to launch third-party apps onto its virtual displays
(`am start --display`) and, later, to inject input. A non-rooted app **cannot
self-elevate**, so today VSpace depends on the separately-installed **Shizuku app**.

Shizuku's server is open source (Apache-2.0) — no conflict with VSpace, also open
source. So VSpace will **bundle it**: ship the Shizuku server inside VSpace's own APK and
own the whole flow in a `:privileged` module. No second app to install. (Apache-2.0
requires keeping Shizuku's licence + NOTICE — added to the module.)

What bundling changes — and what it cannot:

- **Removed:** the separate Shizuku-app install and its onboarding.
- **Owned by VSpace:** its own start command, its own pairing, restarting the server on
  later launches.
- **Irreducible (OS security boundary):** the privileged process must be started by
  *something* that already holds shell privilege. VSpace does this in-app, no PC needed —
  it pairs with the device's own **Wireless Debugging**, connects over local ADB-Wi-Fi,
  and starts the bundled server. The pairing is kept, so later launches reconnect
  silently — but the one-time pairing itself cannot be removed without root.

Net: a one-time, in-app Wireless Debugging pairing, after which privileged access is
invisible. Integrating Shizuku removes the *separate app*, not the bootstrap.

## 9. The phone control surface

The phone is the workspace's **input device** — a trackpad + keyboard ("DeX for
glasses"), not a second screen. Once Shizuku is set up, the control panel is, top to
bottom:

- **Toolbar** — icon buttons: view mode (pinned ⇄ free), capture, screen layout,
  keyboard show/hide.
- **Touchpad** — fills the middle; the relative-motion pad that drives the workspace
  cursor.
- **System keyboard** — when toggled on, the device IME rises from the bottom; the
  keyboard button shows/hides it. Keystrokes route to the focused screen (the routing
  itself is M4 input).

App launching lives entirely in the in-glasses app drawer, so the phone no longer shows
an app list. Before Shizuku is set up, the panel shows the setup banner instead of the
toolbar.
