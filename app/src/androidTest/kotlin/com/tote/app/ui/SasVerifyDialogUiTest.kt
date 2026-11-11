package com.tote.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for the SAS verify dialog.
 *
 * Per build-learnings iron rule #15: verifies the dialog renders the
 * 6-digit SAS + 3-emoji SAS + the match/mismatch buttons, and that
 * tapping them invokes the right callbacks.
 *
 * NOTE: This test uses Compose's `createComposeRule` which requires
 * `androidx.compose.ui:ui-test-junit4`. Added to androidTestImplementation
 * in build.gradle.kts.
 */
@RunWith(AndroidJUnit4::class)
class SasVerifyDialogUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `sas_verify_dialog_renders_digits_and_emojis`() {
        val sasDigits = "123456"
        val sasEmojis = listOf("🦊", "🐙", "🦄")

        composeTestRule.setContent {
            SasVerifyDialog(
                sasDigits = sasDigits,
                sasEmojis = sasEmojis,
                onMatch = {},
                onMismatch = {},
                onDismiss = {},
            )
        }

        // The dialog title should be displayed
        composeTestRule.onNodeWithText("Confirm pairing").assertIsDisplayed()
        // The 6-digit SAS should be displayed
        composeTestRule.onNodeWithText(sasDigits).assertIsDisplayed()
        // Both buttons should be displayed
        composeTestRule.onNodeWithText("They match").assertIsDisplayed()
        composeTestRule.onNodeWithText("They don't match").assertIsDisplayed()
    }

    @Test
    fun `sas_verify_dialog_onMatch_callback_is_invoked`() {
        var matchInvoked = false
        composeTestRule.setContent {
            SasVerifyDialog(
                sasDigits = "000000",
                sasEmojis = listOf("🦊", "🐙", "🦄"),
                onMatch = { matchInvoked = true },
                onMismatch = {},
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithText("They match").performClick()
        assert(matchInvoked) { "Expected onMatch to be invoked" }
    }

    @Test
    fun `sas_verify_dialog_onMismatch_callback_is_invoked`() {
        var mismatchInvoked = false
        composeTestRule.setContent {
            SasVerifyDialog(
                sasDigits = "000000",
                sasEmojis = listOf("🦊", "🐙", "🦄"),
                onMatch = {},
                onMismatch = { mismatchInvoked = true },
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithText("They don't match").performClick()
        assert(mismatchInvoked) { "Expected onMismatch to be invoked" }
    }
}
