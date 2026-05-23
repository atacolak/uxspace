# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status: planning-only

There is no source code, build system, or tests in this repo yet. Only documentation. Before suggesting commands or writing code, read both:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module layering, acyclic dependency rules, what carries over from the Android counterpart, what is new (the `:driver` module).
- [docs/ROADMAP.md](docs/ROADMAP.md) — W0–W5 milestones; each milestone leaves a working build, so scope code to the active milestone.

The sibling Android project (`..\Android`, gitignored here) is the canonical reference for the shared `:tracking` / `:viture` / `:spatial` layering. When in doubt about a layer's contract, the Android `docs/ARCHITECTURE.md` is authoritative.

## Architecture invariants

These rules come from the docs and must hold across all future code:

- **Module graph is acyclic.** Dependencies flow only as: `:app` → (`:spatial`, `:tracking`, `:viture`, `:shared`); `:viture` → `:tracking`; `:driver`, `:tracking`, `:shared` stand alone. See [docs/ARCHITECTURE.md §3](docs/ARCHITECTURE.md).
- **Only `:app` knows Viture exists.** `:tracking` defines `HeadTracker`; `:viture` is one implementation. Swapping in `:xreal`/`:rokid`/OpenXR must require zero changes outside `:app`. ([§10](docs/ARCHITECTURE.md))
- **Graceful degradation is mandatory.** If `xr_device_provider_is_product_support_native_dof()` returns 0, `Camera.FREE` uses rotation only and the UI hides translation controls. With no tracker at all, FREE is hidden entirely — PINNED + multi-monitor + stereo output must keep working. ([§8](docs/ARCHITECTURE.md))
- **`:driver` owns its own lifecycle.** `:app` is a client of an already-loaded driver instance over a named pipe defined in `:shared`. Do not couple driver init to app startup. ([§6, §11](docs/ARCHITECTURE.md))
- **Windows is the desktop.** Do not rebuild UI that the OS provides — no cursor overlay, no app-launcher, no "desktop". The only user-facing UI is the ImGui settings overlay rendered into the 3D scene. ([§1, §5](docs/ARCHITECTURE.md))
- **Cursor-glide onto IddCx outputs can be transiently blocked by a long-running DDA consumer on that output.** Symptom: physical mouse refuses to cross from a real display onto UxSpace; `SetCursorPos`/`Win+Shift+arrow` still work. Verified by `SendInput` clamping at the boundary while `:app` was running and DDA-capturing the output; restarting `:app` cleared it. Mechanism not yet pinned down (suspected: long-lived `IDXGIOutputDuplication` interacts badly with cursor routing). Workaround: restart the DDA consumer. Real fix likely needs the app to recycle the duplication periodically (or unduplicate when no rendering is happening); follow up when this resurfaces.

## Stack and build layout

- C++17, Win32, DirectX 11, Dear ImGui, IddCx (WDK), Viture Windows SDK.
- Root [CMakeLists.txt](CMakeLists.txt) wires the **CMake-generated** projects and uses `include_external_msproject()` to pull the **hand-written `.vcxproj`** for `:driver` into the same generated `.sln` (so WDK property pages survive).
- The Viture SDK is **not committed** — proprietary, mirrors the Android side's gitignored `jniLibs`. Five runtime DLLs ship next to the exe: `glasses.dll`, `carina_vio.dll`, `glew32.dll`, `libusb-1.0.dll`, `opencv_world4100.dll`.
- Driver development requires test-signing mode (`bcdedit /set testsigning on`) and a self-signed cert.

When you add a build system, follow this split exactly. Don't put `:driver` under CMake; don't put the app modules under raw MSBuild.

## Local dev setup landmines

- **Driver builds need full VS 2022 IDE, not just Build Tools.** The `WindowsUserModeDriver10.0` platform toolset is registered by a VSIX that the WDK MSI only auto-installs into Community/Pro/Enterprise IDE instances — it skips Build Tools. If you see `MSB8020: The build tools for WindowsUserModeDriver10.0 cannot be found`, install VS Community 2022 with the `Microsoft.VisualStudio.Workload.NativeDesktop` workload, then re-run the WDK MSI so it picks up the new IDE. The Marketplace WDK VSIX cannot be downloaded directly (returns 403 to non-browser clients).
- **Install order matters:** install VS Community *first*, then the WDK. If you install in the reverse order, re-run the WDK installer afterwards.
- **EWDK is the supported alternative** for headless/CI builds (avoids the IDE dependency entirely). Not used in interactive dev on this machine.
- Viture SDK lives at `..\Viture\SDK\Windows` (sibling to `Windows\`). It will be vendored under `viture/sdk/` once we touch `:viture`. Both paths are in `.gitignore`.

## Fork lineage

[driver/](driver/) is a fork of `microsoft/Windows-driver-samples` `video/IndirectDisplay/IddSampleDriver` (commit `c7ad9e8`). Microsoft's MIT license is preserved in [driver/UPSTREAM_LICENSE](driver/UPSTREAM_LICENSE). Identifier rename: `Microsoft::IndirectDisp` → `UxSpace::Driver`; `IddSample*` callback prefixes → `UxSpace*`; new ProjectGuid + WPP trace GUID; new `Root\UxSpaceDriver` HardwareID; new `UxSpaceDriverGroup` device group. Code/structure inside `Driver.cpp` is otherwise upstream verbatim — when reading it, the Microsoft sample's README and Driver.cpp docstrings still apply.

## Assets

A single source image `assets/source/icon_splash_src.png` produces both the multi-resolution `icon.ico` (top half, 1:1) and `splash.png` (bottom half). The splash is reused as a 3D quad during startup — keep them in sync. UI wordmark is `U✦Space`; code identifier is `UxSpace`.
