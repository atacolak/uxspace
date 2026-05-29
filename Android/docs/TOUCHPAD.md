# Touchpad gestures

The phone is VSpace's input device: a blind relative touchpad (`TrackpadView`) under an
icon toolbar. The user looks at the glasses, not the phone.

## Current gestures

| Gesture | Action |
|---|---|
| One finger drag | Move the cursor (relative, fraction of pad width) |
| One finger tap | Click at the cursor |
| One finger press-and-hold, then move | Drag the window under the cursor (grab by its frame) |
| Two finger drag | Scroll the screen under the cursor |

Path: `TrackpadView` → `WorkspaceController` → `WorkspaceRenderer`. Scroll is delivered
into the app/desktop as an `ACTION_SCROLL` / `AXIS_VSCROLL` motion event.

---

## Planned: speed-sensitive scrolling

**Goal.** Make two-finger scrolling faster or slower with the *speed* of the swipe — a
quick flick scrolls far, a slow drag scrolls precisely. Today the scroll delta is purely
proportional to finger displacement, so a fast and a slow swipe of the same length scroll
the same amount.

**Plan.**

1. **Measure velocity in `TrackpadView`.** In the two-finger branch of `onTouchEvent`,
   keep the previous centroid-Y and its `event.eventTime`. On each `ACTION_MOVE`,
   `velocity = (y - lastY) / (eventTime - lastTime)` in pad-fraction per millisecond.
2. **Acceleration curve.** Convert speed to a gain:
   `gain = 1 + min(|velocity| * ACCEL_K, MAX_BOOST)` — roughly 1× when slow, up to ~3–4×
   when fast. Tune `ACCEL_K` / `MAX_BOOST` on device.
3. **Apply the gain** to the reported delta: `onScroll(rawFraction * gain)`. The rest of
   the path is unchanged.
4. **Optional momentum.** On the two-finger lift, if the last velocity is above a
   threshold, keep emitting decaying scroll deltas for a few hundred ms (a fling), so the
   content keeps gliding. This needs a small animator/`Choreographer` loop in
   `TrackpadView` or the renderer; treat it as a follow-up.

**Touch points.** `TrackpadView` (velocity + gain); constants `ACCEL_K`, `MAX_BOOST`.
Nothing below `TrackpadView` needs to change for steps 1–3.

---

## Planned: pinch-to-zoom a screen

**Goal.** Two-finger pinch zooms the workspace screen (window) under the cursor in and
out. A two-finger tap restores that screen's zoom to 100%.

**Plan.**

1. **Disambiguate the two-finger gesture in `TrackpadView`.** With two fingers down,
   track both the centroid (translation → scroll) and the spread — the distance between
   the pointers (change → zoom). Per `ACTION_MOVE`, compare |Δspread| against |Δcentroid|
   and dispatch the dominant one, so a single two-finger drag is unambiguously a scroll
   *or* a pinch, not both.
2. **New callbacks.** `onZoom(scaleFactor: Float)` — the ratio of the current spread to
   the previous spread; and `onTwoFingerTap()` — two fingers down then up within the tap
   time and slop, with no significant spread or centroid change.
3. **Controller + renderer.** Add `WorkspaceController.zoomScreen(factor)` and
   `resetZoom()`. The renderer finds the `AppWindow` whose content quad is under the
   cursor and multiplies its size by an accumulating zoom factor (clamped, e.g. 0.5×–3×).
   `AppWindow.layout` applies the factor on top of the NORMAL / MAXIMIZED size.
   `resetZoom()` sets the factor of the window under the cursor back to 1.
4. **Two-finger tap → reset.** `onTwoFingerTap` → `WorkspaceController.resetZoom()`.

**Open question.** Zoom can either scale the *window quad* in the scene (cheap; the app
keeps rendering at its native resolution, so it gets blurrier as it grows) or resize the
app's `VirtualDisplay` (sharp at any size, but the app sees a configuration change on
every step). Start with quad scaling; revisit if sharpness matters.

**Touch points.** `TrackpadView` (spread tracking, `onZoom`, `onTwoFingerTap`);
`WorkspaceController` (`zoomScreen`, `resetZoom`); `WorkspaceRenderer` (per-window zoom
factor, pick the window under the cursor); `AppWindow` (apply the factor in `layout`).
