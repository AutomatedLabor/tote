import java.io.File
import java.util.Base64
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
val ciKeystoreFile: File? =
    if (ciKeystoreB64 != null && ciKeystorePassword != null && ciKeyAlias != null && ciKeyPassword != null) {
        File.createTempFile("tote-release", ".keystore").apply {
            writeBytes(Base64.getDecoder().decode(ciKeystoreB64))
            deleteOnExit()
        }
    } else {
        null
    }

val localStoreFile = keystoreProperties.getProperty("storeFile")
val localStorePassword = keystoreProperties.getProperty("storePassword")
val localKeyAlias = keystoreProperties.getProperty("keyAlias")
val localKeyPassword = keystoreProperties.getProperty("keyPassword")

val releaseStoreFile: File? = ciKeystoreFile ?: localStoreFile?.let { rootProject.file(it) }
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
        versionCode = 5
        versionName = "0.5.0-alpha"

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
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.google.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.lazysodium.java)
    implementation(libs.jna)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp)
}
