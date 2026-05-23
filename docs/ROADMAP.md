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
| W2      | M2      | Identical in spirit. Carina gives 6DOF where Android Gen2 gave 3DOF. |
| W3      | M3      | Identical — up to 3 screens, arc placement. |
| W4      | DeX UI  | Different scope: Android built a full desktop; Windows only needs the settings overlay because the OS already provides the desktop. |
| W5      | M5      | Identical — layout persistence, USB hotplug, lifecycle teardown. |
