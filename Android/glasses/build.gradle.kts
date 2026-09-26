plugins {
    // Kotlin support is built into AGP 9 — no separate Kotlin plugin needed.
    alias(libs.plugins.android.library)
}

val vitureSdkSo = file("src/main/jniLibs/arm64-v8a/libglasses.so")
val hasVitureSdk = vitureSdkSo.isFile
if (!hasVitureSdk) {
    logger.warn(
        "VITURE SDK not vendored ({} missing). Native head tracking disabled. " +
            "Download the official Glasses SDK from https://www.viture.com/developer",
        vitureSdkSo,
    )
}

android {
    namespace = "com.uxspace.glasses"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        if (hasVitureSdk) {
            // The VITURE SDK ships arm64 binaries; this phone is arm64.
            ndk {
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (hasVitureSdk) {
        ndkVersion = "30.0.14904198"
        // Builds libglasses_bridge.so — the JNI bridge to the native VITURE SDK.
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
