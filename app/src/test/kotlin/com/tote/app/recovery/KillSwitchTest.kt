package com.tote.app.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [KillSwitch].
 *
 * Validates the state machine: trigger nuke → cancel, trigger → wait → expire,
 * trigger duress with correct/incorrect code.
 */
class KillSwitchTest {

    @Test
    fun `initial mode is IDLE`() {
        val ks = KillSwitch()
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.IDLE)
    }

    @Test
    fun `triggerNuke transitions to NUKE_PENDING and returns trigger time`() {
        val ks = KillSwitch()
        val now = 1_000_000L
        val triggered = ks.triggerNuke(now)
        assertThat(triggered).isEqualTo(now)
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.NUKE_PENDING)
    }

    @Test
    fun `triggerNuke is idempotent — second call returns original trigger time`() {
        val ks = KillSwitch()
        val first = ks.triggerNuke(1_000L)
        val second = ks.triggerNuke(2_000L)
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `cancelNuke returns true and resets to IDLE when within cooldown`() {
        val ks = KillSwitch()
        val triggered = ks.triggerNuke(1_000L)
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.NUKE_PENDING)
        val cancelled = ks.cancelNuke(triggered + 60_000)  // 1 minute later
        assertThat(cancelled).isTrue()
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.IDLE)
    }

    @Test
    fun `cancelNuke returns false if not pending`() {
        val ks = KillSwitch()
        assertThat(ks.cancelNuke()).isFalse()
    }

    @Test
    fun `cancelNuke returns false after cooldown elapsed`() {
        val ks = KillSwitch()
        val triggered = ks.triggerNuke(1_000L)
        val cancelled = ks.cancelNuke(triggered + 25L * 60 * 60 * 1000)
        assertThat(cancelled).isFalse()
    }

    @Test
    fun `checkNukeExpired returns false within cooldown`() {
        val ks = KillSwitch()
        ks.triggerNuke(1_000L)
        assertThat(ks.checkNukeExpired(1_000L + 60_000)).isFalse()
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.NUKE_PENDING)
    }

    @Test
    fun `checkNukeExpired returns true and transitions to NUKE_EXECUTED after cooldown`() {
        val ks = KillSwitch()
        ks.triggerNuke(1_000L)
        val expired = ks.checkNukeExpired(1_000L + 24L * 60 * 60 * 1000 + 1)
        assertThat(expired).isTrue()
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.NUKE_EXECUTED)
    }

    @Test
    fun `nukeTimeRemaining returns correct value within cooldown`() {
        val ks = KillSwitch()
        ks.triggerNuke(1_000L)
        val remaining = ks.nukeTimeRemaining(1_000L + 60 * 60 * 1000)  // 1h later
        assertThat(remaining).isEqualTo(23L * 60 * 60 * 1000)
    }

    @Test
    fun `nukeTimeRemaining returns 0 if not pending`() {
        val ks = KillSwitch()
        assertThat(ks.nukeTimeRemaining()).isEqualTo(0L)
    }

    @Test
    fun `triggerDuress returns false for wrong code`() {
        val ks = KillSwitch()
        val triggered = ks.triggerDuress("wrong-code", "expected-code-1234")
        assertThat(triggered).isFalse()
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.IDLE)
    }

    @Test
    fun `triggerDuress returns true and transitions to DURESS_ACTIVATED for correct code`() {
        val ks = KillSwitch()
        val expected = "super-secret-duress-code-1234"
        val triggered = ks.triggerDuress(expected, expected)
        assertThat(triggered).isTrue()
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.DURESS_ACTIVATED)
    }

    @Test
    fun `triggerDuress rejects codes shorter than 8 chars`() {
        val ks = KillSwitch()
        try {
            ks.triggerDuress("short", "short")
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("≥8 chars")
        }
    }

    @Test
    fun `duress is NOT cancellable — once activated, no way back`() {
        val ks = KillSwitch()
        val code = "super-secret-duress-code-1234"
        ks.triggerDuress(code, code)
        ks.cancelNuke()
        assertThat(ks.currentMode).isEqualTo(KillSwitch.Mode.DURESS_ACTIVATED)
    }
}
