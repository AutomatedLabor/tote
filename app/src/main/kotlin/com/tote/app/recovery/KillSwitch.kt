package com.tote.app.recovery

import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Kill switch — the dual-mode panic-button feature.
 *
 * Per CRYPTO-SPEC-FINAL §5 + SURVIVOR-UX.md:
 *
 * ## Nuke mode (24-hour cooldown, cancellable)
 * - Triggered by the user pressing "Nuke everything" in Settings.
 * - 24-hour countdown timer starts. User can cancel within that window.
 * - After 24h, ALL sensitive data is destroyed:
 *   - pairingRoot + all derived keys (epoch keys, pairing keys)
 *   - All PairingLinks
 *   - All profile cookies (WebView SQLite files for every profile)
 *   - The AndroidKeyStore master key (forces re-onboarding on next launch)
 * - Status: visible countdown shown in Settings.
 *
 * ## Duress mode (≤5 minutes, NOT cancellable)
 * - Triggered by entering the duress code (≥8 chars) at the lock screen.
 * - The app immediately "switches" to a duress-safe mode:
 *   - Hides the duress-sensitive profiles (configured by user in Settings)
 *   - Shows only the decoy profiles
 *   - Zeroises the duress-sensitive profile cookies + their pairingRoots
 *   - Continues to "work" — but the sensitive data is gone.
 * - Status: NOT cancellable. Once entered, the duress-sensitive profiles are
 *   gone. This is a hard trade-off — but it's the right trade-off for
 *   survivor scenarios.
 *
 * NOTE: This is a state-machine, not the actual implementation. The actual
 * data destruction is performed by [KillSwitchExecutor] which knows about
 * the WebView SQLite files, the AndroidKeyStore, etc.
 */
class KillSwitch {
    private val TAG = "KillSwitch"

    enum class Mode {
        /** No kill switch active. */
        IDLE,
        /** Nuke countdown active — can be cancelled within 24h. */
        NUKE_PENDING,
        /** Nuke executed — data destroyed. */
        NUKE_EXECUTED,
        /** Duress triggered — sensitive profiles destroyed, decoys preserved. */
        DURESS_ACTIVATED,
    }

    enum class Trigger {
        USER_NUKE_BUTTON,
        USER_DURESS_CODE,
        AUTOMATIC_AFTER_REBOOT,  // (future) auto-nuke if device reboots during duress
    }

    /** Current mode. Backed by AtomicLong for thread-safe reads. */
    private val modeOrdinal = AtomicLong(Mode.IDLE.ordinal.toLong())

    val currentMode: Mode
        get() = Mode.entries[modeOrdinal.get().toInt()]

    /** When the nuke was triggered (epoch millis). 0 if not triggered. */
    private val nukeTriggeredAt = AtomicLong(0L)

    /** The 24-hour nuke cooldown (millis). */
    private val nukeCooldownMs = 24L * 60 * 60 * 1000

    /** Duress cooldown: 5 minutes (millis). */
    private val duressCooldownMs = 5L * 60 * 1000

    /**
     * Trigger the nuke countdown.
     *
     * Idempotent: if already triggered, returns the original trigger time.
     */
    fun triggerNuke(now: Long = System.currentTimeMillis()): Long {
        val existing = nukeTriggeredAt.get()
        if (existing != 0L) {
            Log.w(TAG, "Nuke already triggered at $existing — ignoring re-trigger")
            return existing
        }
        nukeTriggeredAt.set(now)
        modeOrdinal.set(Mode.NUKE_PENDING.ordinal.toLong())
        Log.w(TAG, "NUKE TRIGGERED at $now — 24h countdown starts. User can cancel.")
        return now
    }

    /**
     * Cancel a pending nuke. Returns true if cancelled, false if not pending
     * or if the cooldown has already elapsed.
     */
    fun cancelNuke(now: Long = System.currentTimeMillis()): Boolean {
        if (currentMode != Mode.NUKE_PENDING) return false
        val triggered = nukeTriggeredAt.get()
        if (triggered == 0L) return false
        val elapsed = now - triggered
        if (elapsed > nukeCooldownMs) {
            Log.w(TAG, "Cancel attempted but cooldown already elapsed (${elapsed}ms > $nukeCooldownMs ms)")
            return false
        }
        nukeTriggeredAt.set(0L)
        modeOrdinal.set(Mode.IDLE.ordinal.toLong())
        Log.i(TAG, "Nuke CANCELLED — no data destroyed")
        return true
    }

    /**
     * Check if the nuke cooldown has elapsed. If so, transitions to NUKE_EXECUTED
     * and returns true (caller should call [KillSwitchExecutor.executeNuke]).
     */
    fun checkNukeExpired(now: Long = System.currentTimeMillis()): Boolean {
        if (currentMode != Mode.NUKE_PENDING) return false
        val triggered = nukeTriggeredAt.get()
        if (triggered == 0L) return false
        val elapsed = now - triggered
        if (elapsed <= nukeCooldownMs) return false
        modeOrdinal.set(Mode.NUKE_EXECUTED.ordinal.toLong())
        Log.e(TAG, "NUKE COOLDOWN ELAPSED — executing nuke NOW")
        return true
    }

    /**
     * Trigger duress mode.
     *
     * Validates the entered code is ≥8 characters and matches the stored duress
     * code. On match, transitions to DURESS_ACTIVATED and returns true.
     *
     * NOT cancellable — once activated, the duress-sensitive profiles are gone.
     */
    fun triggerDuress(enteredCode: String, expectedCode: String): Boolean {
        require(expectedCode.length >= 8) { "Duress code must be ≥8 chars" }
        if (enteredCode != expectedCode) return false
        // Constant-time compare (best effort)
        var diff = 0
        for (i in enteredCode.indices) {
            diff = diff or (enteredCode[i].code xor expectedCode[i].code)
        }
        if (diff != 0) return false
        modeOrdinal.set(Mode.DURESS_ACTIVATED.ordinal.toLong())
        Log.w(TAG, "DURESS ACTIVATED — sensitive profiles will be zeroised")
        return true
    }

    /**
     * Time remaining on the nuke cooldown (millis), or 0 if not pending or expired.
     */
    fun nukeTimeRemaining(now: Long = System.currentTimeMillis()): Long {
        if (currentMode != Mode.NUKE_PENDING) return 0L
        val triggered = nukeTriggeredAt.get()
        if (triggered == 0L) return 0L
        val expires = triggered + nukeCooldownMs
        return (expires - now).coerceAtLeast(0L)
    }
}
