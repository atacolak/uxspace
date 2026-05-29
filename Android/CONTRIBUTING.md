# Contributing to VitureKit-Android

Thanks for your interest in improving VitureKit.

## Ground rules

- **Keep the library a thin wrapper.** VitureKit wraps the official VITURE SDK; it does not
  reverse-engineer or reimplement it. New device features belong behind the
  [`VitureGlasses`](viturekit/src/main/kotlin/com/viturekit/VitureGlasses.kt) seam.
- **The public API is `Flow`-first.** Prefer `StateFlow`/`SharedFlow` and coroutine-scoped
  APIs over callbacks. Java interop is welcome but is not the design target.
- **No leaked handles.** Anything that acquires a USB or native resource must release it via
  the `VitureSession` lifecycle.

## Development setup

- Android Studio with AGP 9.0+ (Kotlin is built into AGP — no Kotlin plugin is applied).
- JDK 17+, Gradle 9.x, `compileSdk` 36.
- Point `sdk.dir` in `local.properties` at your Android SDK.

## Before opening a pull request

1. `./gradlew assembleDebug` — all modules compile.
2. `./gradlew :viturekit:testDebugUnitTest` — library unit tests pass.
3. New library behaviour comes with a test. Tests run against `StubVitureGlasses`, so they
   need no hardware.
4. Public declarations carry KDoc.

## Testing without hardware

`StubVitureGlasses` is a complete synthetic backend. Use it for unit tests and for running
the sample apps. Changes that can only be verified on physical glasses should say so in the
pull request description.

## Commit style

Small, focused commits with imperative subject lines (e.g. "Add IMU frequency override").
