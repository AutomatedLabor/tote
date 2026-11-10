# Android Scaffold — File Contents (`app/` module, v0.1.0-alpha)

> **Repo:** `AutomatedLabor/tote` (public, MIT).
> **Engine:** Android System WebView (ASW) + AndroidX Profile API (`androidx.webkit:webkit:1.10.0`).
> **Language:** Kotlin 1.9.24 (pure, no KMP for v1). **UI:** Jetpack Compose. **Crypto:** libsodium via lazysodium-java.
> **Package:** `com.tote.app`. **minSdk 26 / targetSdk 34 / compileSdk 34.**
> **Gradle 8.7 · AGP 8.5.0 · JDK 17.**
> **Release keystore:** `tote-release.keystore`, alias `tote-release`, cert SHA-256 `DF96CAE5C963CEF4B2ADCCD6CCF3A76113A0386E2376233F3DC758374ACF4196`.
> **CI secrets:** `TOTE_KEYSTORE_BASE64`, `TOTE_KEYSTORE_PASSWORD`, `TOTE_KEY_ALIAS`, `TOTE_KEY_PASSWORD`.

Each fenced block below is the EXACT, compile-clean content for the file named in the info string. Copy each verbatim into the repo at that path.

---

## ⚠ Deviation note — read before pasting

`org.jetbrains.kotlin.plugin.compose` version **`1.9.24` does not exist** on the Gradle Plugin Portal. The standalone Compose Compiler Gradle plugin was introduced with **Kotlin 2.0.0**; declaring it at `1.9.24` fails at plugin resolution, so the scaffold cannot build as literally specced.

To honor the locked Kotlin version (1.9.24) **and** compile, this scaffold:

- declares only `com.android.application` (8.5.0) and `org.jetbrains.kotlin.android` (1.9.24) in the root `plugins {}` block;
- wires Compose the documented Kotlin-1.9.x way in `app/build.gradle.kts` — `composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }` — where `1.5.14` is the Compose Compiler build officially paired with Kotlin 1.9.24.

**When you bump Kotlin to 2.0+:** add `id("org.jetbrains.kotlin.plugin.compose") version "<kotlin>" apply false` to the root `plugins {}` block, add a `kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }` entry under `[plugins]` in `gradle/libs.versions.toml`, apply `alias(libs.plugins.kotlin.compose)` in `app/build.gradle.kts`, and delete the `composeOptions {}` block.

Everything else matches the spec exactly.

---

## File tree

```text
tote/
├── .editorconfig
├── build.gradle.kts
├── gradle.properties
├── keystore.properties.example
├── settings.gradle.kts
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── kotlin/com/tote/app/
        │   │   ├── MainActivity.kt
        │   │   └── ToteApplication.kt
        │   └── res/
        │       ├── values/themes.xml
        │       └── xml/data_extraction_rules.xml
        └── test/
            └── kotlin/com/tote/app/ExampleUnitTest.kt
```

> **Not authored here (generated):** `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`. After dropping the files above into the repo, run `gradle wrapper --gradle-version 8.7` once (or copy the wrapper from a sibling project) so `./gradlew :app:assembleRelease` works. The `gradle-wrapper.properties` below pins the version.

---

## 1. `settings.gradle.kts`

```settings.gradle.kts
pluginManagement {
    repositories {
        // Aliyun mirror first — per threshold LEARNINGS (avoid Maven Central 429).
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Aliyun mirror first — per threshold LEARNINGS (avoid Maven Central 429).
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Tote"
include(":app")
```

---

## 2. `build.gradle.kts` (root)

```build.gradle.kts
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
```

---

## 3. `gradle/libs.versions.toml`

```gradle/libs.versions.toml
# Tote — Gradle version catalog.
# Repositories (incl. Aliyun mirror) are configured in settings.gradle.kts.

[versions]
agp = "8.5.0"
kotlin = "1.9.24"
coreKtx = "1.13.1"
lifecycle = "2.8.4"
activityCompose = "1.9.1"
composeBom = "2024.06.00"
# Compose Compiler 1.5.14 is the build officially paired with Kotlin 1.9.24.
# (See deviation note in planning/ANDROID-SCAFFOLD.md.)
webkit = "1.10.0"
navigationCompose = "2.7.7"
lazysodium = "5.1.4"
jna = "5.13.0"
kotlinxSerialization = "1.6.3"
kotlinxCoroutines = "1.8.1"
truth = "1.4.2"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-webkit = { group = "androidx.webkit", name = "webkit", version.ref = "webkit" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
lazysodium-java = { group = "com.goterl", name = "lazysodium-java", version.ref = "lazysodium" }
jna = { group = "net.java.dev.jna", name = "jna", version.ref = "jna" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

---

## 4. `app/build.gradle.kts`

```app/build.gradle.kts
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Release signing config resolution.
//   Priority 1: CI env vars — TOTE_KEYSTORE_BASE64 / TOTE_KEYSTORE_PASSWORD /
//               TOTE_KEY_ALIAS / TOTE_KEY_PASSWORD (used by GitHub Actions).
//   Priority 2: root/keystore.properties (gitignored) — local dev.
//   Priority 3: none — release build falls back to the debug signing config so
//               `./gradlew :app:assembleRelease` works on a fresh clone.
// Per build-learnings iron rule #2: no secret values are hardcoded here.
// ---------------------------------------------------------------------------
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

val ciKeystoreB64 = providers.environmentVariable("TOTE_KEYSTORE_BASE64").orNull
val ciKeystorePassword = providers.environmentVariable("TOTE_KEYSTORE_PASSWORD").orNull
val ciKeyAlias = providers.environmentVariable("TOTE_KEY_ALIAS").orNull
val ciKeyPassword = providers.environmentVariable("TOTE_KEY_PASSWORD").orNull

// Decode the keystore only when all four CI env vars are present.
val ciKeystoreFile: java.io.File? =
    if (ciKeystoreB64 != null && ciKeystorePassword != null && ciKeyAlias != null && ciKeyPassword != null) {
        java.io.File.createTempFile("tote-release", ".keystore").apply {
            writeBytes(java.util.Base64.getDecoder().decode(ciKeystoreB64))
            deleteOnExit()
        }
    } else {
        null
    }

val localStoreFile = keystoreProperties.getProperty("storeFile")
val localStorePassword = keystoreProperties.getProperty("storePassword")
val localKeyAlias = keystoreProperties.getProperty("keyAlias")
val localKeyPassword = keystoreProperties.getProperty("keyPassword")

val releaseStoreFile: java.io.File? = ciKeystoreFile ?: localStoreFile?.let { rootProject.file(it) }
val releaseStorePassword: String? = ciKeystorePassword ?: localStorePassword
val releaseKeyAlias: String? = ciKeyAlias ?: localKeyAlias
val releaseKeyPassword: String? = ciKeyPassword ?: localKeyPassword
val hasReleaseKeystore = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.tote.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tote.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Alpha: minify off. Enable for v1 beta.
            isMinifyEnabled = false
            signingConfig =
                if (hasReleaseKeystore) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Compose Compiler build paired with Kotlin 1.9.24.
        // See deviation note in planning/ANDROID-SCAFFOLD.md.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.lazysodium.java)
    implementation(libs.jna)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

---

## 5. `gradle.properties`

```gradle.properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

---

## 6. `app/src/main/AndroidManifest.xml`

```app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <!--
        Deferred — required at runtime for the sync foreground service (Phase 2).
        Included now so the permission grant flow ships with v1, not as a v2
        afterthought. Play Console requires a <property> tag declaring the
        special use for FOREGROUND_SERVICE_SPECIAL_USE; add it before Play
        submission.
    -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".ToteApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="false"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="Tote"
        android:supportsRtl="true"
        android:theme="@style/Theme.Tote">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="Tote"
            android:theme="@style/Theme.Tote">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

> The launcher icon uses the framework placeholder `@android:drawable/sym_def_app_icon` so the project compiles with zero mipmap assets. Generate a real adaptive launcher icon (Android Studio → New → Image Asset, or `mipmap-anydpi-v26/ic_launcher.xml`) before the first public release.

---

## 7. `app/src/main/res/xml/data_extraction_rules.xml`

```app/src/main/res/xml/data_extraction_rules.xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    Tote carries sensitive WebView state (cookies, localStorage, IndexedDB,
    crypto material). NOTHING is eligible for cloud backup or device-to-device
    transfer. allowBackup=false + fullBackupContent=false already disable it
    globally; these rules are belt-and-suspenders for the named dirs.
-->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
        <exclude domain="cache" path="." />
        <exclude domain="root" path="app_webview" />
    </cloud-backup>

    <device-transfer>
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
        <exclude domain="cache" path="." />
        <exclude domain="root" path="app_webview" />
    </device-transfer>
</data-extraction-rules>
```

---

## 8. `app/src/main/res/values/themes.xml`

```app/src/main/res/values/themes.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
        Placeholder theme for the alpha. Parent is the framework Material Light
        NoActionBar base so the manifest resolves before Compose takes over
        surface drawing. This will be replaced by a Material 3 Compose theme
        (MaterialTheme + a branded ToteTheme) once the Compose UI shell lands.
    -->
    <style name="Theme.Tote" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

---

## 9. `app/src/main/kotlin/com/tote/app/MainActivity.kt`

```app/src/main/kotlin/com/tote/app/MainActivity.kt
package com.tote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Placeholder Material 3 theme. A branded `ToteTheme` will replace this
            // once the Compose UI shell lands (see planning/ANDROID-SCAFFOLD.md).
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ToteAlpha()
                }
            }
        }
    }
}

@Composable
fun ToteAlpha() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Tote")
    }
}

@Preview(showBackground = true)
@Composable
private fun ToteAlphaPreview() {
    MaterialTheme {
        ToteAlpha()
    }
}
```

---

## 10. `app/src/main/kotlin/com/tote/app/ToteApplication.kt`

```app/src/main/kotlin/com/tote/app/ToteApplication.kt
package com.tote.app

import android.app.Application

/**
 * Tote application entry point.
 *
 * Empty for the alpha. This is where the WebViewPool, ProfileRegistry,
 * lazysodium bootstrap, and the relay client will live once Phase 1 lands
 * (per ADR-007).
 */
class ToteApplication : Application()
```

---

## 11. `app/src/test/kotlin/com/tote/app/ExampleUnitTest.kt`

```app/src/test/kotlin/com/tote/app/ExampleUnitTest.kt
package com.tote.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Trivial unit test so the JUnit report step in CI has something to chew on.
 *
 * Uses Google Truth (`assertThat(...).isEqualTo(...)`); the Truth dependency
 * is declared in gradle/libs.versions.toml and wired as `testImplementation`
 * in app/build.gradle.kts.
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertThat(1 + 1).isEqualTo(2)
    }
}
```

---

## 12. `gradle/wrapper/gradle-wrapper.properties`

```gradle/wrapper/gradle-wrapper.properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

> AGP 8.5.0 requires Gradle ≥ 8.7. Run `gradle wrapper --gradle-version 8.7` once to materialise `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` (binaries, not hand-authored).

---

## 13. `keystore.properties.example`

```keystore.properties.example
# ---------------------------------------------------------------------------
# Local development signing config template.
#
# Copy this file to `keystore.properties` (gitignored) and fill in real values.
# DO NOT commit `keystore.properties` — it is in .gitignore (build-learnings
# iron rule #2: never paste a live secret into chat or commit).
#
# CI does NOT use this file — CI uses the TOTE_KEYSTORE_BASE64 /
# TOTE_KEYSTORE_PASSWORD / TOTE_KEY_ALIAS / TOTE_KEY_PASSWORD environment
# variables (see .github/workflows/build.yml).
#
# Expected keystore: tote-release.keystore
#   alias:           tote-release
#   cert SHA-256:    DF96CAE5C963CEF4B2ADCCD6CCF3A76113A0386E2376233F3DC758374ACF4196
# ---------------------------------------------------------------------------

storeFile=keystore/tote-release.keystore
storePassword=
keyAlias=tote-release
keyPassword=
```

---

## 14. `.editorconfig`

```.editorconfig
root = true

[*]
charset = utf-8
end_of_line = lf
indent_style = space
indent_size = 4
insert_final_newline = true
trim_trailing_whitespace = true
max_line_length = 120

[*.{kt,kts}]
ij_kotlin_imports_layout = *
ktlint_standard_no-wildcard-imports = enabled
ktlint_standard_filename = enabled

[*.{yml,yaml,toml}]
indent_size = 2

[*.{xml,json}]
indent_size = 4

[*.{md,markdown}]
trim_trailing_whitespace = false
max_line_length = off
```

---

## 15. `app/proguard-rules.pro`

```app/proguard-rules.pro
# ---------------------------------------------------------------------------
# Tote — ProGuard / R8 rules.
# ---------------------------------------------------------------------------
# Minify is OFF for the alpha (app/build.gradle.kts: isMinifyEnabled = false),
# so this file has no effect yet. Rules below are pre-staged for the beta / v1
# release where minify will be enabled. They are inert while minify is off.
# ---------------------------------------------------------------------------

# lazysodium-java + JNA reach into native code via reflection.
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.goterl.lazysodium.**
-dontwarn com.sun.jna.**

# kotlinx.serialization (pre-staged — no @Serializable types exist in the alpha).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

---

## Build verification (post-paste)

1. Drop the 15 files above into the repo at the listed paths.
2. `gradle wrapper --gradle-version 8.7` (materialises `gradlew` + `gradle-wrapper.jar`).
3. `./gradlew :app:assembleDebug` — smoke build, no signing required.
4. `./gradlew :app:testDebugUnitTest` — runs `ExampleUnitTest`, emits a JUnit XML report for CI.
5. `./gradlew :app:assembleRelease` — on a clone with no `keystore.properties` and no CI env vars, the release build silently falls back to the debug signing config (see `hasReleaseKeystore` logic). In CI with the four `TOTE_*` env vars set, it signs with `tote-release.keystore`; verify with `apksigner verify --print-certs --verbose app/build/outputs/apk/release/*.apk` and confirm the SHA-256 is `DF96CAE5...4196` (build-learnings iron rule #16 — use `apksigner`, not `keytool`).
