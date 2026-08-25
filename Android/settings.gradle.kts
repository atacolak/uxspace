pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android and sun-security-android — the embedded ADB client (see docs/PRIVILEGE.md).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "UxSpace"

include(":glasses")
include(":spatial")
include(":app")

// Local-only experiment module (gitignored): VITURE camera-stream test app.
// Safe to commit — the include is skipped on clones where camtest/ is absent.
if (file("camtest").exists()) include(":camtest")
