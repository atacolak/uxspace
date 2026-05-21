# VitureKit-Android

A clean, idiomatic Kotlin library that wraps the official VITURE Android SDK and exposes
head-tracking / IMU data as coroutine `Flow`s — with proper lifecycle scoping and verified
Samsung DeX support.

> **Status:** 1.0 development. The core API, the head-tracked cursor reference app, and a
> side-by-side stereo sample are implemented and build against AGP 9 / Kotlin 2.2.

## Why

The official VITURE Android SDK is low-level: manual USB lifecycle, raw callback
registration, byte-level IMU parsing, no `Flow` interface. Every app that wants head-tracked
UI, gesture input, or stereo rendering re-implements the same boilerplate — and nothing makes
the IMU pleasant to use inside a Samsung DeX session. VitureKit is the thin layer that smooths
all of that over, the way EasyVXR does on Linux.

## Modules

| Module          | What it is                                                              |
|-----------------|-------------------------------------------------------------------------|
| `viturekit`     | The library — `VitureSession`, IMU `Flow`s, device control, DeX support |
| `app`           | Reference app: a head-tracked cursor with dwell-to-select targets       |
| `sample-stereo` | A minimal side-by-side (SBS) stereo renderer, head-tracked via OpenGL   |

## Quick start

```kotlin
// 1. Create a session over a backend, and bind it to your Activity/Fragment lifecycle.
val session = VitureSession.create(context, StubVitureGlasses())
    .bindToLifecycle(this)

// 2. Start connecting — the USB permission dialog is handled for you.
session.connect()

// 3. Collect IMU samples as a Flow.
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        session.imu.collect { reading ->
            val yaw = reading.euler.yawDeg
            val pitch = reading.euler.pitchDeg
            // ... drive your UI / renderer ...
        }
    }
}
```

`VitureSession` also exposes `connectionState`, `displayMode`, `imuEnabled` and `latestImu`
as `StateFlow`s, plus one-shot `events`. It pauses the IMU when the lifecycle stops and
releases the device on `onDestroy`, so there are no leaked USB handles across configuration
changes.

## The SDK seam — stub vs. real hardware

VitureKit never references the closed-source VITURE SDK directly. Everything goes through the
[`VitureGlasses`](viturekit/src/main/kotlin/com/viturekit/VitureGlasses.kt) interface, and you
choose the backend:

- **`StubVitureGlasses`** — a synthetic backend that generates a gentle head-sway motion. It
  needs no hardware and no SDK, so the sample apps (and unit tests) run anywhere, including
  inside DeX. Both samples use it by default.
- **A real-SDK adapter** — for physical glasses. The official SDK's redistribution terms mean
  you must obtain it yourself; VitureKit deliberately does not bundle it.

To wire up real hardware:

1. Drop the official VITURE Android SDK `.aar` into `viturekit/libs/` and add
   `implementation(files("libs/viture-sdk.aar"))` to `viturekit/build.gradle.kts`.
2. Copy [`viturekit/integration/RealVitureGlasses.kt.template`](viturekit/integration/RealVitureGlasses.kt.template)
   to `viturekit/src/main/kotlin/com/viturekit/integration/RealVitureGlasses.kt` and fill in
   the `TODO`s against your SDK version.
3. Create the session with it: `VitureSession.create(context, RealVitureGlasses(context))`.

## Samsung DeX

DeX is still ordinary Android, so VitureKit works there without special handling. The library
watches the system display set and emits `VitureEvent.DisplaysChanged` when the user enters or
leaves a DeX session; `VitureSession.isDexActive` and `DexEnvironment` report current state.

## Requirements

- Android Studio with **AGP 9.0+** (Kotlin support is built in — no Kotlin plugin applied)
- **Gradle 9.x**, **JDK 17+**
- `compileSdk` 36, `minSdk` 26

`local.properties` must point `sdk.dir` at your Android SDK.

## Build

```sh
./gradlew assembleDebug          # build all three modules
./gradlew :viturekit:testDebugUnitTest   # run the library unit tests
./gradlew :app:installDebug      # install the cursor demo
./gradlew :sample-stereo:installDebug    # install the stereo sample
```

## Roadmap

This repository implements milestones M2–M4 of the
[project one-pager](viture-android-imu-library-onepager.md): the core API, the reference app,
and the stereo sample. Remaining for 1.0: validation against physical Pro / Luma hardware
(M1 hardware spike) and Maven Central publication (M5).

## License

Apache-2.0 — see [LICENSE](LICENSE). Permissive on purpose, so the library can be vendored
into other projects.
