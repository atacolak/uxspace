plugins {
    // Kotlin support is built into AGP 9 — no separate Kotlin plugin needed.
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vspace"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.vspace"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

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

    buildFeatures {
        viewBinding = true
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }

    // Builds libglasses_bridge.so — the JNI bridge to the native VITURE SDK.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // The internal head-tracking layer — used from M2 onward; M1 uses only its model types.
    implementation(project(":viturekit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Shizuku — borrows ADB-shell privileges (no root) to launch apps onto a virtual
    // display and inject input into it.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
