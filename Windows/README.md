# UxSpace — Windows

The Windows half of the **UxSpace** project. Turns Viture AR glasses into a
head-tracked spatial workspace on Windows, with up to three virtual monitors
arranged in selectable layouts.

The Android counterpart of UxSpace lives at `..\Android` and is the canonical
reference for the shared layering — see its `docs/ARCHITECTURE.md`.

## Status

Planning. No code yet. See:

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module layering, what carries
  over from the Android side, what disappears on Windows, what is new.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — W0–W5 milestones.

## Naming

- Code identifier: `UxSpace`
- UI wordmark: `U✦Space`
- Tagline: *Your space. Any screen.*

## Stack

C++17, Win32, DirectX 11, Dear ImGui, IddCx (WDK) for the virtual display
driver, Viture Windows SDK for 6DOF head tracking. Visual Studio 2022 solution
at the root combines CMake-generated app modules with a hand-written WDK
`.vcxproj` for the driver.

## Hardware target

- **Viture Carina** — 6DOF via the SDK's bundled VIO (`carina_vio.dll` +
  OpenCV). Primary target.
- **Viture Gen1 / Gen2** — 3DOF (rotation only). Same `HeadTracker` interface;
  `Camera.FREE` collapses to rotation, settings hide translation controls.
- Any external display works in `Camera.PINNED` mode even with no head tracker.
