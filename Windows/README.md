# UxSpace — Windows

> **U✦Space** — *Your space. Any screen.*

The Windows half of [**UxSpace**](../README.md). Turns **any AR/XR glasses** into a
head-tracked spatial workspace by capturing **up to three virtual monitors** and
rendering them into the glasses in stereo, fixed in space as you look around. Built and
tested on VITURE glasses; head tracking sits behind a vendor-agnostic interface (see
below).

Unlike the Android side — which builds its own desktop — the Windows side leans on the
OS: **Windows is the desktop.** UxSpace adds virtual monitors (via a custom display
driver), captures them, and floats them in 3D. The only bespoke UI is an in-scene
settings overlay.

The Android counterpart (`../Android`) is the canonical reference for the shared
`tracking` / `viture` / `spatial` layering — see its `docs/ARCHITECTURE.md` when a
layer's contract is unclear.

## Status — W3 in progress

Past planning; actively building. Milestones (see [`docs/ROADMAP.md`](docs/ROADMAP.md)):

| | Milestone | State |
|---|-----------|-------|
| W0 | Driver + capture skeleton (one virtual monitor over a named pipe) | ✅ done |
| W1 | Glasses output — full-screen stereo, eye-offset views | ✅ done |
| W1.5 | Per-window pseudo-3D layering (`Ctrl+Alt+D`) | ✅ done |
| W2 | Head tracking, 6DOF — `PINNED` / `FREE`, recenter | ✅ done |
| **W3** | **Multi-monitor (up to 3) + configurable EDID, layout transforms** | 🚧 **current** |
| W4 | ImGui settings overlay + presets, JSON profiles | ⬜ planned |
| W5 | Lifecycle & polish — USB hotplug, teardown, perf, tray | ⬜ planned |

What works today: the driver creates/recycles virtual monitors on request, `:app`
connects over the pipe and captures them, renders the quads in stereo into the glasses,
6DOF head pose drives the camera, and the dev UI drives monitor count and layout. Expect
diagnostics, a dev window, and rough edges — this is mid-milestone.

## Hotkeys

The global hotkey prefix is **`Ctrl+Alt`** (matches the Android counterpart):

| Key | Action |
|-----|--------|
| `Ctrl+Alt+X` | Toggle `PINNED` ↔ `FREE` view |
| `Ctrl+Alt+R` | SDK recenter (hard) |
| `Ctrl+Alt+C` | Anchor the workspace at the current head pose |
| `Ctrl+Alt+Z` | Screen size +0.1 (wraps) |
| `Ctrl+Alt+D` | Toggle per-window pseudo-3D layering |
| `Ctrl+Alt+Wheel` / `+` / `-` | Zoom into the captured screen (`PINNED` only) |

## Stack

C++17, Win32, DirectX 11, Dear ImGui, IddCx (WDK) for the virtual display driver, and a
head-tracking provider for 6DOF — currently the VITURE Windows SDK (the reference
implementation; the `:tracking` interface keeps the app vendor-agnostic).

## Build layout

A Visual Studio 2022 solution combines **CMake-generated** app modules with a
**hand-written WDK `.vcxproj`** for the driver:

- Root [`CMakeLists.txt`](CMakeLists.txt) wires the app modules and uses
  `include_external_msproject()` to pull `driver/UxSpaceDriver.vcxproj` into the
  generated `.sln` (so WDK property pages survive). Keep this split — don't put
  `:driver` under CMake, don't put the app modules under raw MSBuild.

### Modules

| Module | Role |
|--------|------|
| [`app/`](app/) | The executable — capture, DX11 stereo rendering, head-pose camera, dev UI, pipe client. |
| [`driver/`](driver/) | IddCx user-mode virtual display driver (a fork of Microsoft's `IddSampleDriver`). Owns its own lifecycle; `:app` is a client over a named pipe. |
| [`shared/`](shared/) | Cross-process contracts — the `ipc.h` named-pipe protocol and `log.h`. Stands alone. |
| [`tracking/`](tracking/) | The `HeadTracker` interface and view-mode types. Vendor-agnostic. |
| [`viture/`](viture/) | The VITURE SDK implementation of `HeadTracker`. Depends only on `:tracking`. |
| [`spatial/`](spatial/) | `ScreenLayout` strategies emitting per-monitor transforms (`Single`, …). |
| [`packaging/`](packaging/) | WiX MSI installers (`wix/`) and driver install/uninstall scripts. |

**Dependency rule (acyclic):** `app` → (`spatial`, `tracking`, `viture`, `shared`);
`viture` → `tracking`; `driver`, `tracking`, `shared` stand alone. Only `app` knows
VITURE exists — swapping in another vendor (`xreal`/`rokid`/OpenXR) must touch nothing
outside `app`.

## The VITURE SDK is not committed

Proprietary — mirrors the Android side's git-ignored `jniLibs`. Place it at
`../Viture/SDK/Windows` (resolved automatically) or vendor a copy into `viture/sdk/`;
override either with `-DUXSPACE_VITURE_SDK_DIR=<path>`. Five runtime DLLs ship next to
the exe: `glasses.dll`, `carina_vio.dll`, `glew32.dll`, `libusb-1.0.dll`,
`opencv_world4100.dll`.

## Dev-machine prerequisites

- **Full Visual Studio 2022 IDE (Community/Pro/Enterprise), not just Build Tools** — the
  `WindowsUserModeDriver10.0` toolset ships in a WDK VSIX that only installs into a full
  IDE. Install VS Community *first* (with the **Desktop development with C++** workload),
  then the WDK; if you did it in the other order, re-run the WDK installer. (EWDK is the
  supported headless/CI alternative.)
- **Driver development needs test-signing mode** (`bcdedit /set testsigning on`) and a
  self-signed certificate.

## Hardware

Any AR/XR glasses that present as an external display and can supply a head pose.
Capability tiers the app adapts to:

- **6DOF** — full experience; `FREE` view keeps the workspace fixed in world space.
- **3DOF (rotation only)** — same `HeadTracker` interface; `FREE` collapses to rotation
  and the UI hides translation controls.
- **No head tracker / any external display** — `PINNED` mode keeps working.

**Reference hardware (developed and tested on):**

- **VITURE Carina / Luma** — 6DOF via the SDK's bundled VIO (`carina_vio.dll` + OpenCV).
- **VITURE Gen1 / Gen2** — 3DOF.

Other vendors (XREAL, Rokid, OpenXR, …) are a deliberate extension point — only `:app`
knows VITURE exists, so a new provider lands without touching the rest of the app.

## Naming

- Code identifier: `UxSpace`
- UI wordmark: `U✦Space`
- Tagline: *Your space. Any screen.*

## License

`driver/` is a fork of Microsoft's `IddSampleDriver` (MIT, preserved in
[`driver/UPSTREAM_LICENSE`](driver/UPSTREAM_LICENSE)) — see
[`CLAUDE.md`](CLAUDE.md) for the rename/fork lineage. The VITURE SDK is proprietary and
is not part of this repository.

## More

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module layering, dependency rules,
  what carries over from Android, the new `:driver` module.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — W0–W5 milestones in detail.
- [`CLAUDE.md`](CLAUDE.md) — architecture invariants, build landmines, fork lineage.

## Contact

Maintained by Demian Vladi — [demianvladi@gmail.com](mailto:demianvladi@gmail.com).
