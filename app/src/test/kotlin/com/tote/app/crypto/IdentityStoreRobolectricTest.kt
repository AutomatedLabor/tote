package com.tote.app.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric integration test for [IdentityStore].
 *
 * Verifies the full SharedPreferences I/O path with a real Android Context.
 * Cannot test the AndroidKeyStore path (Robolectric has no real AndroidKeyStore),
 * but verifies the IdentityStore's plaintext fallback + decryptIfEncrypted
 * heuristic works correctly.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityStoreRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear all IdentityStore state
        context.getSharedPreferences("tote_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("tote_kill_switch", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `getOrCreateIdentity_generates_a_fresh_identity_on_first_call`() {
        val store = IdentityStore(context)
        val identity = store.getOrCreateIdentity()
        assertThat(identity.ed25519Pub).hasLength(32)
        assertThat(identity.x25519Pub).hasLength(32)
        assertThat(identity.seed).hasLength(32)
    }

    @Test
    fun `getOrCreateIdentity_returns_the_same_identity_on_subsequent_calls`() {
        val store = IdentityStore(context)
        val id1 = store.getOrCreateIdentity()
        val id2 = store.getOrCreateIdentity()
        // Same instance (cached) — same byte arrays
        assertThat(id1.ed25519Pub.contentEquals(id2.ed25519Pub)).isTrue()
        assertThat(id1.seed.contentEquals(id2.seed)).isTrue()
    }

    @Test
    fun `getOrCreateIdentity_persists_across_process_restart`() {
        val store1 = IdentityStore(context)
        val originalId = store1.getOrCreateIdentity()

        // New IdentityStore instance (simulates process restart — SharedPreferences persists)
        val store2 = IdentityStore(context)
        val restoredId = store2.getOrCreateIdentity()

        // The seed should be the same (decrypted from SharedPreferences, possibly via plaintext fallback)
        assertThat(restoredId.seed.contentEquals(originalId.seed)).isTrue()
    }

    @Test
    fun `getOrCreateEpochState_bootstraps_a_random_pairingRoot_if_none_stored`() {
        val store = IdentityStore(context)
        val identity = store.getOrCreateIdentity()
        val state = store.getOrCreateEpochState(identity)

        // A 32-byte root is generated
        assertThat(state.currentKey).hasLength(32)
    }

    @Test
    fun `savePairingRoot_overwrites_previous_root`() {
        val store = IdentityStore(context)
        val identity = store.getOrCreateIdentity()
        val state1 = store.getOrCreateEpochState(identity)
        val root1 = state1.pairingRoot.copyOf()
        val key1 = state1.currentKey.copyOf()

        val newRoot = ByteArray(32) { 0x42.toByte() }
        store.savePairingRoot(newRoot)

        val state2 = store.getOrCreateEpochState(identity)
        // The new state's pairingRoot should be the newRoot we saved.
        // (currentKey is HKDF-derived from pairingRoot — tested via the
        // pairingRoot field, not currentKey.)
        assertThat(state2.pairingRoot.contentEquals(newRoot)).isTrue()
        assertThat(state2.pairingRoot.contentEquals(root1)).isFalse()
        // The derived currentKey should also change (different input → different output).
        assertThat(state2.currentKey.contentEquals(key1)).isFalse()
    }

    @Test
    fun `saveRecoverySalt_then_getRecoverySalt_round_trips`() {
        val store = IdentityStore(context)
        val salt = ByteArray(16) { (it + 1).toByte() }
        store.saveRecoverySalt(salt)

        val recovered = store.getRecoverySalt()
        assertThat(recovered).isNotNull()
        assertThat(recovered!!.contentEquals(salt)).isTrue()
    }

    @Test
    fun `getRecoverySalt_returns_null_if_never_stored`() {
        val store = IdentityStore(context)
        assertThat(store.getRecoverySalt()).isNull()
    }

    @Test
    fun `zeroize_wipes_all_state`() {
        val store = IdentityStore(context)
        val identity = store.getOrCreateIdentity()
        store.getOrCreateEpochState(identity)
        store.saveRecoverySalt(ByteArray(16))

        store.zeroize()

        // After zeroize, the SharedPreferences should be empty
        val prefs = context.getSharedPreferences("tote_identity", Context.MODE_PRIVATE)
        assertThat(prefs.all).isEmpty()
        assertThat(store.getRecoverySalt()).isNull()
    }

    @Test
    fun `zeroize_then_getOrCreateIdentity_generates_a_fresh_identity`() {
        val store = IdentityStore(context)
        val originalId = store.getOrCreateIdentity()
        store.zeroize()

        val freshId = store.getOrCreateIdentity()
        // Fresh seed should NOT equal the original seed (extremely unlikely collision)
        assertThat(freshId.seed.contentEquals(originalId.seed)).isFalse()
    }
}
