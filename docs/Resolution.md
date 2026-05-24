# Screen Resolution Table

Per-screen pixel resolution (backing `VirtualDisplay`) and world-space size for each
layout. Calculated at the startup defaults — FOV 55°, scene distance 4 m, glasses
surface ≈ 16:9 → desktop usable area ≈ 7.40 m × 3.81 m (after the 8.5 % taskbar reserve
at the bottom).

| Layout         | Screen      | Backing px    | World size (m)      | Yaw    | centerZOffset | Notes                                |
| -------------- | ----------- | ------------- | ------------------- | ------ | ------------- | ------------------------------------ |
| **SINGLE**     | 0           | 1920 × 1080   | 6.51 × 3.66         | 0°     | 0             | Flat, fills view                     |
| **SINGLE_WIDE**| 0           | 3840 × 1200   | arc 6.28 × 1.96     | curved | 0             | 90° arc on r=4 m cylinder; 24-strip mesh |
| **TWO_SBS**    | 0 (L)       | 1920 × 1080   | 3.55 × 2.00         | +10°   | 0             | Inner edges butt-join at world x ≈ 0 |
|                | 1 (R)       | 1920 × 1080   | 3.55 × 2.00         | −10°   | 0             |                                      |
| **THREE_SBS**  | 0 (L)       | 1920 × 1080   | 2.37 × 1.33         | +33°   | +0.65         | Surround arc: inner edges butt-join the centre at (±1.18, 0, −4) |
|                | 1 (M)       | 1920 × 1080   | 2.37 × 1.33         | 0°     | 0             | Flat, central                        |
|                | 2 (R)       | 1920 × 1080   | 2.37 × 1.33         | −33°   | +0.65         |                                      |
| **THREE_VHV**  | 0 (V)       | 1080 × 1920   | 1.78 × 3.16         | +36.4° | +0.53         | Portrait; own narrow taskbar; inner edge butt-joins H at (±1.85, 0, −4) |
|                | 1 (H)       | 1920 × 1080   | 3.70 × 2.08         | 0°     | 0             | Flat, central; full-width taskbar    |
|                | 2 (V)       | 1080 × 1920   | 1.78 × 3.16         | −36.4° | +0.53         | Portrait; own narrow taskbar         |

## Per-layout zoom calibration

The toolbar zoom button + pinch HUD always show the user a **displayed** % that means
the same perceived size across layouts — so 100 % on Wide reads as the same visible
fill as 100 % on Single. The renderer multiplies internally by `layout.zoomBaseScale`
before applying to the projection matrix.

| Layout         | zoomBaseScale | Why                                                      |
| -------------- | -------------:| -------------------------------------------------------- |
| SINGLE         | 1.00          | Baseline                                                 |
| SINGLE_WIDE    | 1.87          | World height 1.96 m ≈ 54 % of Single's 3.66 m → boost     |
| TWO_SBS        | 1.83          | Per-screen height 2.00 m ≈ 55 % of Single's              |
| THREE_SBS      | 2.75          | Per-screen height 1.33 m ≈ 36 % of Single's              |
| THREE_VHV      | 1.76          | H-screen height 2.08 m ≈ 57 % of Single's                |

## Notes

- **Backing display = the slot's declared `contentWidthPx × contentHeightPx`** —
  apps launched onto Single Wide really see a 3840×1200 monitor, and apps on the V
  screens of V/H/V see a 1080×1920 portrait monitor. Layout-aware apps lay out for the
  true aspect; legacy apps just see "a monitor of that resolution."
- **World size** is the on-plane metric extent at the screen's centre depth — flat
  screens are at z = −4 m (or shifted by `centerZOffset`); the curved Single Wide is
  parameterised by arc length on a cylinder of radius 4 m.
- **Yaw + centerZOffset** of side screens are derived from the geometric butt-join
  constraint with the central (or H) screen's edge. With both, adjacent screens meet
  at exactly the same world point in X *and* Z, so there's no perspective overlap or
  Z-induced gap on the cylinder surround.
- **PINNED mode always renders SINGLE.** The user's chosen layout (`presetState` /
  `WorkspaceController.layout`) is preserved across lock/unlock but only takes effect
  in FREE. Lock-mode is single-display focus.
- **Display creation pre-condition.** Backing `VirtualDisplay`s are created with the
  `TRUSTED` flag through `PrivilegedService` (the shell-uid helper) so apps can be
  launched onto them via `launchOnDisplay`.
