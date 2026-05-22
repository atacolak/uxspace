# Settings

VSpace has no settings surface yet — choices like the wallpaper are hard-coded. This is the
plan for a settings store and a settings screen, starting with the wallpaper.

## Current state

The desktop wallpaper is fixed: `DesktopPresentation.buildWallpaper()` decodes the bundled
asset `workspace_background.jpg` into an `ImageView` with `scaleType = CENTER_CROP`. There is
no way to change it or how it is displayed.

---

## Planned: wallpaper choice and display mode

**Goal.** Let the user pick a wallpaper and choose how it fills the desktop —
center-crop, stretch, fit (letterbox), centered, or tiled.

### 1. A settings store

A `WorkspaceSettings` object backed by `SharedPreferences` (app-wide, survives restarts):

- `wallpaperSource` — a bundled preset id, or a `content://` URI of a user-picked image.
- `wallpaperMode` — one of the display modes below.

It exposes typed getters/setters and a change listener so the desktop can react.

### 2. Wallpaper source

- **Bundled presets** — a handful of wallpapers in `assets/`, shown as a picker grid.
- **From the device** — the Android photo picker (`ActivityResultContracts.PickVisualMedia`,
  no storage permission needed). Persist the returned URI with
  `takePersistableUriPermission`, or copy the image into app storage so it survives even if
  the source is deleted. Copying is the safer default.

### 3. Display mode

| Mode | How |
|---|---|
| Center-crop (default) | `ImageView.ScaleType.CENTER_CROP` |
| Stretch | `ScaleType.FIT_XY` — fills, distorts aspect |
| Fit | `ScaleType.FIT_CENTER` — whole image, letterboxed, no distortion |
| Centered | `ScaleType.CENTER` — native size, no scaling |
| Tiled | not a `ScaleType` — set a `BitmapDrawable` with `tileModeX/Y = REPEAT` as the view background, or paint a `BitmapShader` |

### 4. Settings screen

A settings screen reached from the phone control panel (`MainActivity`) — a gear entry in
the toolbar or a row under the status banner. It holds:

- the wallpaper picker (preset grid + "Choose from device"),
- the display-mode selector (radio list),
- **taskbar visibility** — always-show (default) vs auto-hide (see
  [TASKBAR.md](TASKBAR.md)),
- room to grow — screen-size default, theme colours ([[VSpaceTheme]]), etc.

It is plain phone UI (no glasses needed), so it can be a normal `Activity` or a
`BottomSheetDialog` from `MainActivity`.

### 5. Applying a change

The desktop lives on its own `UiScreen` ([DesktopPresentation]). When the wallpaper or mode
changes, `DesktopPresentation` rebuilds its wallpaper view. Wiring options:

- `WorkspaceSettings` exposes a listener; `DesktopPresentation` registers one in `onCreate`
  and re-applies the wallpaper when it fires — mirrors the existing
  `WorkspaceController.onAppLaunched` / `onAppClosed` hooks.
- The renderer does not need to know about wallpaper; this stays entirely in `:app`.

**Touch points.** New `WorkspaceSettings` (SharedPreferences); new settings screen in `:app`;
`DesktopPresentation.buildWallpaper()` reads `WorkspaceSettings` and re-applies on change;
a wallpaper-decode helper that handles asset ids and `content://` URIs and the tiled mode.
