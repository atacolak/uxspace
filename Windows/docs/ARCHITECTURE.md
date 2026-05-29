# UxSpace Windows — Architecture

## 1. What this is

UxSpace is a spatial multi-monitor compositor for Viture AR glasses on
Windows. It provides up to three virtual displays the OS treats as real, then
composites their framebuffers in a head-tracked 3D scene that is sent to the
glasses as side-by-side stereo.

Unlike the Android counterpart, UxSpace does **not** rebuild any UI — Windows
already has a desktop, a taskbar, a window manager, and an input stack. The
user drags windows onto the virtual monitors and uses them like ordinary
displays; UxSpace's job is only to *provide* those displays and to *render*
them in 3D.

## 2. Layered design — mirrors Android VSpace §2

| # | Layer  | What it is                                                | Module     | Key types                                      |
|---|--------|-----------------------------------------------------------|------------|------------------------------------------------|
| 1 | View   | The 3D stage — a head-tracked camera composites Surfaces in a layout | `:spatial` | `Scene`, `Camera`, `ScreenLayout`, `Surface3D` |
| 2 | Screen | A virtual monitor whose framebuffer becomes a texture     | `:spatial` | `VirtualScreen` (DDA capture)                  |
| 3 | App    | The exe that wires it together + settings overlay         | `:app`     | `main`, ImGui overlay, hotkeys                 |

Plus four infrastructure modules:

- `:driver`   — IddCx virtual display driver (WDK), provides the monitors.
- `:tracking` — `HeadTracker` interface, `HeadPose`, `ViewMode`. Vendor-neutral.
- `:viture`   — Viture Windows SDK wrapper. Implements `HeadTracker`.
- `:shared`   — IPC types and the named-pipe protocol between `:app` and `:driver`.

## 3. Module dependencies

```
:driver       IddCx -> Windows kernel APIs                            -> WDK
:tracking     HeadTracker abstraction                                 -> Win32
:viture       Viture SDK + USB + native bridge                        -> :tracking
:spatial      D3D11 toolkit + Camera + Surface3D + DDA capture        -> Win32 + D3D11
:app          settings overlay + provider wiring + IPC client         -> :spatial, :tracking, :viture, :shared
:shared       header-only IPC types                                   -> -
```

Acyclic. `:tracking`, `:driver`, `:shared` stand alone. Only `:app` knows
Viture exists — swap `:viture` for `:xreal` and nothing else moves. Same rule
as Android VSpace §3 and §10.

## 4. What carries over from Android VSpace

| Android        | Windows equivalent  | Same?                                                                 |
|----------------|---------------------|-----------------------------------------------------------------------|
| `:tracking`    | `:tracking`         | Yes — interface and semantics identical                               |
| `:viture`      | `:viture`           | Yes — same Viture SDK; only `xr_device_provider_create` signature differs (no Android FD) |
| `:spatial`     | `:spatial`          | Mostly — `Camera` PINNED/FREE, `ScreenLayout` strategy, `Surface3D` keep their roles |
| `Screen` family | `VirtualScreen`    | Simplified — just a texture from DDA, no Presentation → SurfaceTexture chain |

## 5. What disappears on Windows

| Android piece                                | Why it's gone                                                          |
|----------------------------------------------|------------------------------------------------------------------------|
| `:ui` cursor overlay, phone-as-trackpad      | Windows has a real mouse; the OS routes input on each virtual monitor  |
| `:privileged` (bundled Shizuku)              | Windows apps don't need elevation to render on a specific monitor      |
| `UiScreen` / `DesktopPresentation` / One UI desktop | Windows already is the desktop                                  |
| `AppScreen` / `am start --display`           | Users drag windows freely                                              |
| JNI bridge in `:viture`                      | App is C++, SDK is C — direct link                                     |

## 6. What is new on Windows — the `:driver` module

There is no user-mode `VirtualDisplay` equivalent on Windows. Virtual monitors
must be **provided** by a kernel-adjacent driver. `:driver` is a fork of
Microsoft's **`IddSampleDriver`** built against the WDK.

It:

- Creates up to **N virtual monitors** (start with 1, grow to 3).
- Publishes EDID with **multiple modes** per monitor; resolution is
  configurable at runtime via the `:shared` control channel.
- Hands DDA-capturable frames over to the user-mode app.

### Signing

During development: test-signing mode (`bcdedit /set testsigning on`) with a
self-signed cert. Distribution will eventually require an EV cert and a WHQL
submission, or acceptance that users enable test-signing themselves.

### Control channel

`:shared` defines a small named-pipe protocol. Messages:

- `SetMonitorCount(n)`            — driver creates/destroys monitors to match.
- `SetMonitorMode(id, w, h, hz)`  — driver re-publishes EDID for that monitor.
- `Ping`                          — health check.

Driver lifecycle is owned by the driver; `:app` is a client of an already-loaded
driver instance.

## 7. Stack and tooling

- C++17, Win32, **DirectX 11**. DDA hands `ID3D11Texture2D` directly — zero
  interop pain. DX12 is overkill for compositing a handful of quads.
- **IddCx (WDK)** for the driver. Hand-written `.vcxproj` so the WDK property
  pages survive intact.
- **Viture Windows SDK** at `..\..\Android\VSpace\SDK\Windows` (current path,
  vendored locally when the repo is migrated). C headers, `glasses.lib` for
  link, runtime DLLs: `glasses.dll`, `carina_vio.dll`, `glew32.dll`,
  `libusb-1.0.dll`, `opencv_world4100.dll`. All five DLLs ship next to the
  exe. The SDK itself is **not** committed (proprietary licence; mirrors the
  Android side's gitignored `jniLibs`).
- **Dear ImGui** for the settings overlay.
- **CMake** for `:app`, `:spatial`, `:tracking`, `:viture`, `:shared`. VS 2022
  solution at the root holds the CMake-generated projects plus the
  hand-written `:driver` `.vcxproj`.

## 8. Camera modes — identical to Android VSpace §4

- **PINNED** — identity view. Quads are head-locked (a HUD-style anchor).
- **FREE** — inverse head pose. Quads stay in world space; the user looks
  around them. Default when a tracker is connected.

### Graceful degradation

If `xr_device_provider_is_product_support_native_dof()` returns 0 for the
connected device, `Camera.FREE` only uses the rotation component of the pose
(3DOF). Settings hide translation-dependent controls. If no head tracker is
connected at all, the view-mode toggle offers only PINNED.

This is exactly the Android VSpace §10 rule.

## 9. `ScreenLayout` — strategy pattern, mirrors Android VSpace §4

A `ScreenLayout` maps logical screen slots to `Surface3D` transforms and tells
`:driver` how many virtual monitors to create. The shipping presets:

- `Single`      — one quad straight ahead.
- `ArcOfThree`  — three quads on a cylinder, configurable radius and spacing.
- `Stack`       — three quads stacked vertically.
- `Theater`     — one large quad far away (cinema feel).

Adding a Viture SpaceWalker-style preset is a new strategy file plus an entry
in the ImGui picker.

## Per-window pseudo-3D layering (intra-screen composition)

`ScreenLayout` arranges multiple virtual monitors in 3D; this is the
complementary concern of how the framebuffer of a *single* virtual monitor is
composed in depth. (Unnumbered to keep §10/§11 cross-references in CLAUDE.md
stable.)

`Surface3D` carries a `uvRect` (originally added for the cursor-zoom feature in
*Beyond W5*) that selects a sub-rectangle of its bound texture. The app
exploits this to render one DDA capture as a back plane plus N per-window
quads at staggered Z, where each quad's `uvRect` covers exactly that window's
pixels in the captured framebuffer.

Win32 window enumeration and focus-history tracking live in `:app`
(`EnumWindows`, `MonitorFromWindow`, `SetWinEventHook EVENT_SYSTEM_FOREGROUND`);
the results map directly to `Surface3D` position / size / uvRect. `:spatial`
does not know about Win32 windows — the module graph is unchanged.

Limits of the approximation (back-plane edge ghosting at side angles,
occluder-pixel bleed inside partially-occluded windows) are documented in
[ROADMAP.md](../docs/ROADMAP.md) under W1.5. Real fixes would need a
desktop-only render or per-window alpha masking and are deferred past the
W1.5 MVP.

## 10. Vendor-agnostic — same rule as Android VSpace §10

`:tracking` defines `HeadTracker` with `start()`, `stop()`, `recenter()`, and
a pose stream. `:viture` is one implementation; future vendors are siblings
(`:xreal`, `:rokid`, …). Only `:app` selects a concrete provider; the rest of
UxSpace sees only the interface.

With no head tracker, `Camera.FREE` is hidden; everything else — multi-monitor
layouts, stereo output to any connected SBS display, settings overlay —
continues to work.

## 11. Startup flow

1. `:app` starts. Shows splash on the primary monitor.
2. `:app` connects to `:driver` over the named pipe. Driver creates the
   configured virtual monitor(s) with the requested EDID modes.
3. `:app` initialises DDA capture per virtual monitor.
4. `:app` initialises `:viture` (calls `xr_device_provider_create` with the
   detected product ID, then `initialize` → `start`). If no glasses, falls
   back to "no tracker" mode.
5. `:app` detects the Viture display, takes it full-screen exclusive at its
   native side-by-side stereo mode.
6. The splash re-shows as a quad in the 3D scene for ~1 s, then fades into the
   selected layout.
7. Steady state: each frame samples the latest pose (late-latched), updates
   the camera, captures all virtual monitors, renders quads in stereo.

## 12. Assets

- App icon and splash come from a single source image — `assets/source/icon_splash_src.png`.
- **Icon:** top half, cropped 1:1, exported as multi-resolution `icon.ico`
  (16/32/48/64/128/256).
- **Splash:** bottom half, exported as `splash.png`. Used in both the 2D
  startup window (step 1 above) and the 3D splash quad (step 6).
- Wordmark in UI is `U✦Space`. Code identifier is `UxSpace`.

## 13. Out of scope (for now)

- Per-eye distortion correction — Viture's birdbath optics do not require it.
- Wayland / Linux equivalents.
- SteamVR or OpenXR runtime targets — would be a separate `:openxr` provider
  in `:tracking`-land, not on the current roadmap.
- Audio routing.
