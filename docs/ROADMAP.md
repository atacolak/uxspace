# UxSpace Windows — Roadmap

Milestones, mirroring the Android VSpace M0–M5 where they map. Each leaves a
working build.

## W0 — Driver and capture skeleton

- Fork `IddSampleDriver`; enable test-signing locally; sign with a self-signed
  cert.
- Driver creates **one** 1920×1080 virtual monitor.
- `:app` connects to the driver over the named pipe, captures that monitor via
  DDA, renders the captured texture as a flat quad in a normal window on the
  primary display. **No glasses yet.**

Done when: the virtual monitor appears in `Display Settings`, dragging a
window onto it makes the window appear inside the preview quad in `:app`.

## W1 — Glasses output

- Detect the Viture display by EDID / name.
- Take it full-screen exclusive at its native side-by-side stereo mode
  (typically 3840×1080 @ 72/90 Hz).
- Render the captured quad twice with eye-offset view matrices.
- No head tracking yet — quad is screen-locked.

Done when: putting on the glasses shows the captured virtual monitor as a
fixed quad in front of the wearer.

## W1.5 — Per-window pseudo-3D layering

The captured framebuffer is one 2D image, but in the 3D scene we can
decompose it into a back plane plus per-window quads at staggered depths,
so each Win32 window has its own parallax. No new capture path — all
quads sample the same DDA texture via `Surface3D.uvRect`. Pure `:app` +
`:spatial` work; no driver, tracker, or IPC change.

- Enumerate top-level windows on the UxSpace virtual monitor each frame
  (`EnumWindows` + `MonitorFromWindow`, filtered to visible non-iconic
  app windows with a titlebar or sized client area).
- Maintain a focus-history list fed by a `SetWinEventHook` listener for
  `EVENT_SYSTEM_FOREGROUND`. Most-recently-focused window sits closest;
  older ones recede. Newly-appearing windows enter at the front.
- For each window: `uvRect = windowRect / monitorRect`; XY in 3D
  proportional to its position on the back plane;
  `Z = backZ − δ × (focusListLength − index)`. Default `δ = 1.5 cm`,
  user-tunable in the dev UI.
- Back plane stays as a fallback for area not covered by any enumerated
  window (wallpaper, taskbar, desktop icons).
- Zoom (see *Beyond W5* below) collapses to single-surface behaviour when
  active, to avoid the "zoom one window vs. zoom the back plane"
  ambiguity.
- **Global toggle:** `Win+Shift+D` flips pseudo-3D on/off
  (`RegisterHotKey(MOD_WIN | MOD_SHIFT, 'D')` — cleaner than the LL hook
  for a non-wheel chord; the dev window receives `WM_HOTKEY`). Mirrors
  the in-overlay checkbox so it works while the wearer is looking
  through the glasses, not the dev window.

### Dev UI additions (introduced in W1.5, kept through later milestones)

These are general developer ergonomics, surfaced now because W1.5 adds a
third global hotkey (after `Win+Shift+wheel` zoom and `Win+Shift+Z` band
cycle) and a "which monitor is the cursor on?" question that becomes
load-bearing for focus tracking.

1. **Hotkeys panel** — a collapsible section (or separate ImGui window)
   in the dev UI listing every binding the app responds to, with a
   one-line description and whether it is global (works system-wide via
   `SetWindowsHookEx` / `RegisterHotKey`) or local (only while the dev
   window has keyboard focus, via `ImGui::IsKeyPressed`). At W1.5 the
   list is:
   - `Win+Shift+Z` (global) — cycle screen band.
   - `Win+Shift+Wheel` (global) — zoom over the UxSpace virtual monitor.
   - `Win+Shift+D` (global) — toggle pseudo-3D layering.
   Adding a new hotkey in later milestones means adding one row to this
   panel.
2. **Display layout window** — a new ImGui window that draws every
   physical and virtual monitor as a proportional rectangle in its
   actual desktop coordinates (via `EnumDisplayMonitors` +
   `GetMonitorInfoW`). Renders the live cursor position as a dot.
   Colour-coded: real displays one tint, the UxSpace virtual monitor
   another, the detected Viture output a third. Each rect labelled with
   monitor name + resolution. Optional overlay (checkbox) drawing the
   focus-ordered window rects on top of the UxSpace monitor, for
   eyeballing the focus-history list. Stays useful through W3
   (multi-monitor) and W5 (hotplug debugging).

Known limitations accepted for this milestone:

- Slight side-angle stereo views show edge ghosting from the back plane,
  which has window pixels baked in. Real fix needs a desktop-only render
  — out of scope.
- Partially occluded windows sample the occluder's pixels in their UV
  rect, rendering at the wrong depth. Mitigated by small δ; real fix
  needs alpha masking — out of scope.

Composes with later milestones with no rework: W2's camera transform
applies uniformly across all surfaces; W3 gives each virtual monitor its
own window-layer stack.

Done when: with three windows open on the UxSpace virtual monitor, the
dev SBS preview shows distinct per-eye parallax for the most recently
focused window vs. older ones; alt-tabbing snaps focus to closest depth
within ~100 ms; `Win+Shift+D` (or the overlay checkbox) restores
single-quad behaviour bit-for-bit; the Hotkeys panel lists all three
bindings; the Display layout window correctly tracks the cursor across
monitor boundaries.

## W2 — Head tracking, 6DOF

- `:viture` wraps the SDK. A dedicated thread polls pose and double-buffers
  into an atomic `HeadPose`.
- `Camera.FREE` consumes the inverse pose; `Camera.PINNED` ignores it.
- Recenter hotkey stores the inverse-pose origin.
- If `xr_device_provider_is_product_support_native_dof()` returns 0, FREE
  collapses to rotation only and the UI hides translation-dependent controls.

Done when: in FREE mode, the quad is world-locked as the head moves; recenter
re-anchors it directly forward.

## W3 — Multi-monitor and configurable EDID

- Driver supports up to 3 virtual monitors. Count and per-monitor EDID modes
  controlled at runtime over the `:shared` named-pipe channel.
- `ScreenLayout` strategies emit per-monitor transforms: `Single`,
  `ArcOfThree`, `Stack`.
- `:app` creates/destroys driver monitors as the active layout changes.

Done when: switching layout (still via code or a debug hotkey) reshapes the
workspace and the OS sees the correct number of displays.

## W4 — Settings overlay and presets

- ImGui overlay (toggled with a hotkey, rendered in the 3D scene) for:
  layout picker, view mode toggle, arc curvature, monitor resolution,
  recenter, brightness.
- Profiles persisted to JSON next to the exe.
- SpaceWalker-style preset list seeded with `Single`, `ArcOfThree`, `Stack`,
  `Theater`. Each preset bundles a `ScreenLayout`, default monitor count, and
  default per-monitor mode.

Done when: the user can switch layouts and resolutions from inside the
glasses with no external tools.

## W5 — Lifecycle and polish

- USB hotplug: glasses disconnect and reconnect cleanly; `:viture` re-attaches
  without restarting `:app`.
- Driver lifecycle teardown: orderly virtual-monitor removal on `:app` exit.
- Performance pass: late-latched pose, GPU sync, frame pacing to the glasses'
  native refresh.
- Tray icon, optional autostart, About dialog showing the splash asset.

Done when: the app survives a glasses unplug + replug, exits without orphaned
virtual monitors, and holds steady frame pacing under load.

## Beyond W5

- Second vendor provider — `:xreal`, `:rokid`, or OpenXR.
- 3D widgets in the scene that are not "a captured monitor" — recenter
  button, layout switcher, mini-HUD — rendered as world-space quads.
- **Land new app windows on the UxSpace display when glasses are
  connected (extend mode).** No first-class Windows API exists for "open
  new windows here" — apps choose their placement themselves, usually
  from the primary display, the launcher's monitor, or a remembered last
  position. Approaches, ordered cheapest → most invasive:
  - *Promote UxSpace to primary while glasses are connected.*
    `ChangeDisplaySettingsEx` with `DM_POSITION` + `CDS_SET_PRIMARY` shifts
    the primary; most apps then open there. Cheap, but side-effects:
    Windows taskbar default-follows the primary, apps that remember "the
    primary" stay anchored after the change, and the revert on disconnect
    leaves windows behind on the now-detached UxSpace display.
  - *WinEvent hook for window creation.* Listen for
    `EVENT_OBJECT_CREATE` / `EVENT_OBJECT_SHOW` via `SetWinEventHook` and
    `MoveWindow` newly-created top-level windows to the UxSpace monitor.
    More precise than primary-flipping (won't move tray icons, dialogs,
    or apps already running); cost is a process-wide hook callback and
    heuristics for "what counts as an app window" (no owner, has a
    titlebar, sized > some minimum). Apps that compute their placement
    *after* `SHOW` (Electron, Qt with custom logic) can fight us.
  - *Per-monitor "default placement" preference baked into a shim.* Like
    PowerToys FancyZones but auto-applied for our display. Out of scope.
  - *Defer to user keybinds: Win+Shift+Arrow.* Don't change behaviour;
    document the gesture. Worst UX but zero implementation.

  Recommended path: try the *promote-to-primary* approach first behind a
  setting (W4 settings overlay), measure how often the WinEvent hook is
  actually needed, and only add the hook if the primary-flip leaves real
  gaps.
- **Zoom into a portion of a captured screen.**
  - *Gesture:* `Win+Shift+wheel` when the cursor is inside a UxSpace
    virtual monitor. Wheel up zooms in, wheel down zooms out. Default
    modifier set is rebindable (W4) — fallback `Win+Alt+wheel` if a
    conflict surfaces from PowerToys / vendor utilities; Win+Shift+wheel
    itself is not a Windows default.
  - *Range:* 1.0× – 4.0× (i.e. up to 400%), step 0.25 or smooth-lerped.
    Past 4× the AR-glass pixel grid dominates and detail stops improving.
  - *Persistence:* zoom level **persists** after release; user scrolls
    back to 1× to reset. Behaves like a sticky magnifier rather than a
    momentary gesture.
  - *Implementation:*
    - Add `XMFLOAT4 uvRect{0,0,1,1}` to [Surface3D](../spatial/include/uxspace/spatial/Surface3D.h);
      extend [Renderer](../spatial/include/uxspace/spatial/Renderer.h)'s
      per-draw cbuffer with it and remap `uv = lerp(uvRect.xy, uvRect.zw, vIn.uv)`
      in the pixel shader. No extra buffers / passes.
    - Capture the gesture via a global low-level mouse hook
      (`SetWindowsHookEx WH_MOUSE_LL`). The hook lives in `:app`, checks
      `LWIN/RWIN + SHIFT` via `GetAsyncKeyState`, verifies the cursor is
      inside the UxSpace monitor's `DesktopCoordinates`, then consumes
      the `WM_MOUSEWHEEL` event so the focused app doesn't also page.
    - Cursor → normalized texture coords: `(cursorGlobal - monitorTopLeft) / monitorSize`.
      Zoom Z gives `uvRect = (cursorUV ± 0.5/Z)`, clamped to `[0,1]`.
    - Sampler stays linear; switching to point-sampling above some Z
      threshold for crispness is a tuning knob in W4.
  - *Composition:* a `:spatial` concern; the zoom state lives on the
    `Surface3D`, so it composes with `ScreenLayout` (W3) and the screen
    band already in place. Per-monitor zoom in multi-monitor layouts.
- Per-eye distortion (only if a future Viture model needs it; current models
  do not).
- Audio routing through the glasses' built-in speakers as a default device
  while UxSpace is running.

## Cross-reference

Where these milestones map to Android VSpace milestones:

| Windows | Android | Notes                                              |
|---------|---------|----------------------------------------------------|
| W0      | M0 + M1 | Android M0 was a restructure; M1 was launching an app onto a virtual display. On Windows the equivalent is the IddCx driver providing the display in the first place. |
| W1      | (n/a)   | Android already had glasses-as-display for free via Android's external-display API. Windows needs explicit detection + exclusive fullscreen. |
| W1.5    | (n/a)   | Android renders each app to its own Presentation/SurfaceTexture, so per-window depth is implicit. On Windows the OS composites first; we decompose the composited framebuffer back into per-window quads. |
| W2      | M2      | Identical in spirit. Carina gives 6DOF where Android Gen2 gave 3DOF. |
| W3      | M3      | Identical — up to 3 screens, arc placement. |
| W4      | DeX UI  | Different scope: Android built a full desktop; Windows only needs the settings overlay because the OS already provides the desktop. |
| W5      | M5      | Identical — layout persistence, USB hotplug, lifecycle teardown. |
