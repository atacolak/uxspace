# VSpace

An Android app that turns VITURE glasses into a **spatial multi-screen workspace**. Plug the
phone into the glasses, launch installed Android apps onto up to **3 virtual screens**, and —
when the glasses support DOF — turn your head to look across them.

> **Status:** early development. See the roadmap below for what works today.

## How it works

The glasses are a USB-C external display. VSpace owns that display with a `Presentation`
hosting an OpenGL scene, and draws the virtual screens as quads floating in 3D space:

```
VITURE glasses ──USB-C──▶ phone
   │  external Display
   ▼
Presentation + GLSurfaceView          ← 3D scene; camera = inverse head pose
   │  draws up to 3 textured quads (the screens), world-fixed
   ▼
each quad sampled from a SurfaceTexture
   ▲
   │ Surface
VirtualDisplay ◀── startActivity(setLaunchDisplayId) ── an installed app
```

Each virtual screen is an Android `VirtualDisplay`; an installed app is launched onto it and
its output is textured onto the screen's quad. Head pose (from the native VITURE SDK) drives
the camera so the screens stay fixed in space.

## Constraints (read before expecting magic)

This is a stock, non-rooted Android app, which bounds what is possible:

- **Launching third-party apps is best-effort.** Android restricts placing arbitrary apps on
  a virtual display. Many apps work; some bounce back to the phone screen or misbehave,
  depending on the app and Android version.
- **Input needs a Bluetooth mouse/keyboard.** Injecting touch into another app's virtual
  display requires a privileged permission, so a paired BT pointer is the interaction path.

## Modules

| Module             | What it is                                                          |
|--------------------|---------------------------------------------------------------------|
| `app`              | The VSpace workspace app                                            |
| `viturekit`        | Internal head-tracking layer — `VitureSession`, pose `Flow`s        |
| `viturekit-native` | (M2) JNI bridge to the proprietary native VITURE SDK                |

## Roadmap

- **M0** — Repo restructured around the workspace app. ✅
- **M1** — Launch one installed app onto a virtual screen rendered on the glasses.
- **M2** — Head tracking via the native VITURE SDK; screens become world-fixed.
- **M3** — Up to 3 screens with configurable placement and an app picker.
- **M4** — Bluetooth mouse/keyboard input routing.
- **M5** — Layout persistence, USB hotplug, lifecycle teardown.

## The VITURE SDK

The native VITURE SDK (`.so` + C headers, proprietary) is **not** committed — see
`SDK/Android/android/LICENSE`. Place it under `SDK/Android/` locally; M2 wires it in via a
JNI bridge. It is only needed for head tracking and display-mode control — M1 needs no SDK.

## Requirements

- Android Studio with **AGP 9.0+** (Kotlin support is built in — no Kotlin plugin applied)
- **Gradle 9.x**, **JDK 17+**, `compileSdk` 36, `minSdk` 26

`local.properties` must point `sdk.dir` at your Android SDK.

## Build

```sh
./gradlew assembleDebug      # build the app
./gradlew :app:installDebug  # install on a connected phone
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
