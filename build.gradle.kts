// Top-level build file. Plugin versions are declared here with `apply false` and
// consumed from the version catalog in module build files.
//
// NOTE: `org.jetbrains.kotlin.plugin.compose` was specced at version "1.9.24",
// but that plugin was introduced with Kotlin 2.0.0 — version 1.9.24 does NOT
// exist on the Gradle Plugin Portal. For Kotlin 1.9.24 we wire Compose via
// `composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }` in
// app/build.gradle.kts. See planning/ANDROID-SCAFFOLD.md (deviation note) and
// switch to the compose plugin when Kotlin is bumped to 2.0+.
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
