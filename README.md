# UxSpace

An Android app that turns VITURE glasses (or any external display) into a **DeX-style
spatial workspace** — a desktop with a taskbar and an app drawer, floating in front of you,
driven with the phone as a trackpad.

> **Status:** in active development. The desktop, app drawer, head tracking, and
> phone-as-trackpad work today; see the roadmap.

## How it works

The glasses are a USB-C external display. UxSpace owns that display with a `Presentation`
hosting an OpenGL scene:

```
VITURE glasses ──USB-C──▶ phone
   │  external Display
   ▼
Presentation + GLSurfaceView            ← 3D scene; camera = inverse head pose
   ├─ the desktop  — a UiScreen: a real Android UI (taskbar, app drawer,
   │                 wallpaper) on its own VirtualDisplay, textured onto a quad
   └─ app windows  — each a VirtualDisplay running an installed app, on a quad
```

The desktop is a real Android view hierarchy on `Theme.DeviceDefault` — on a Samsung
device it is styled as One UI, the same way Samsung DeX is. Launched apps render onto
their own virtual displays. Head pose (from the native VITURE SDK) drives the camera: in
**free** view the workspace stays fixed in space as you look around; in **pinned** view it
follows your head. With no head tracker, pinned view and multiple screens still work on any
external display.

## Constraints

A stock, non-rooted Android app, which bounds what is possible:

- **Launching third-party apps needs shell-uid privilege** — placing an app on a virtual
  display is not allowed for normal apps. UxSpace activates its own shell-uid helper on
  first run through Android 11's Wireless Debugging (`docs/PRIVILEGE.md`): the user enables
  Wireless Debugging once, types the 6-digit pairing code into UxSpace, and from then on
  UxSpace starts its helper itself on every launch — no separate app required.
- **The phone is the input device** — a trackpad (one finger moves the cursor, two fingers
  scroll, a tap clicks) and, later, a keyboard. A Bluetooth mouse/keyboard also works.

## Modules

| Module    | What it is                                              |
|-----------|---------------------------------------------------------|
| `app`     | The phone control panel and the desktop UI layout       |
| `spatial` | The 3D workspace — camera, screens, the GL renderer     |
| `glasses` | VITURE head tracking and the native-SDK JNI bridge      |

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the layered design and roadmap.

## Roadmap

- **M0** — Repo restructured around the workspace app. ✅
- **M1** — Launch an installed app onto a virtual screen on the glasses. ✅
- **M2** — Head tracking via the native VITURE SDK; screens become world-fixed. ✅
- **M3** — Up to 3 screens with arc placement. ✅
- **DeX UI** — A real One UI desktop: taskbar, app drawer, cursor, two-finger scroll. ✅
- **M4** — Input: route the keyboard and clicks into launched app windows.
- **M5** — Layout persistence, USB hotplug, lifecycle teardown.

## The VITURE SDK

The native VITURE SDK (`.so` + C headers, proprietary) is **not** committed. Vendor it
locally into `glasses/src/main/jniLibs` and `glasses/src/main/cpp/include` (both
git-ignored) for the native build. It is needed only for head tracking — without it,
UxSpace still runs on any external display.

## Requirements

- Android Studio with **AGP 9.0+** (Kotlin support is built in — no Kotlin plugin applied)
- **Gradle 9.x**, **JDK 17+**, `compileSdk` 36, `minSdk` 26
- **NDK 30** + **CMake 4.x** for the native build

`local.properties` must point `sdk.dir` at your Android SDK.

## Build

```sh
./gradlew assembleDebug      # build the app
./gradlew :app:installDebug  # install on a connected phone
```

## Acknowledgements

UxSpace stands on the shoulders of the **[Shizuku](https://shizuku.rikka.app/)** project by
[RikkaApps](https://github.com/RikkaApps). Shizuku pioneered the mechanism that makes apps
like this one possible on a stock, non-rooted Android phone: borrow ADB-shell privilege at
runtime, bootstrapped from the device itself over Wireless Debugging — no PC, no root.
UxSpace shipped on top of the Shizuku app first; the in-app pairing flow now built into
UxSpace is modelled directly on Shizuku's. **Thank you.**

Thanks also to **[libadb-android](https://github.com/MuntashirAkon/libadb-android)** by
Muntashir Al-Islam — the embedded ADB client that lets UxSpace pair and connect to its own
device's wireless-debugging service without a separate Shizuku install.

## License

Apache-2.0 — see [LICENSE](LICENSE).
