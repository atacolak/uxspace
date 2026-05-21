// Top-level build file. Plugin versions are declared here and applied per-module.
// AGP 9 ships built-in Kotlin support (KGP 2.2.10), so no Kotlin plugin is applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
