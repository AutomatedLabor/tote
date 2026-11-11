package com.tote.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the QR scan → pairing → SAS verify → persist flow.
 *
 * Runs on a real device or emulator (unlike the JVM unit tests + Robolectric
 * tests, which run on the JVM). Triggered via:
 *   ./gradlew :app:connectedAndroidTest
 *
 * Per build-learnings iron rule #15: this test exists to verify the wiring
 * (Context → Activity → ViewModel → WebViewPool → TabManager) end-to-end,
 * not just the unit-level pieces.
 *
 * NOTE: This file is a SCAFFOLD. It verifies the app launches + the
 * ToteApplication bootstraps correctly. Full Espresso UI tests for the
 * pairing flow are TODO at v1.0.0 ship — they require camera-injection
 * tooling (we can't easily test the QR scanner without mocking CameraX).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @Test
    fun `app_context_package_name_is_correct`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(context.packageName).isEqualTo("com.tote.app")
    }

    @Test
    fun `app_launches_without_crash`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertThat(activity).isNotNull()
                assertThat(activity::class.java.simpleName).isEqualTo("MainActivity")
            }
        }
    }

    @Test
    fun `tote_application_bootstraps_lazysodium_successfully`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ToteApplication
        // Lazysodium is loaded eagerly in ToteApplication.onCreate.
        // If it failed, the app wouldn't have started — this test passing
        // means libsodium is loaded.
        // The crypto smoke test happens via the JVM Argon2KdfTest.smokeTest.
        assertThat(app.profileRegistry).isNotNull()
        assertThat(app.identityStore).isNotNull()
    }
}
