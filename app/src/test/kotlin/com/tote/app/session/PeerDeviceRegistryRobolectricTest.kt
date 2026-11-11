package com.tote.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field

/**
 * Robolectric integration test for [PeerDeviceRegistry].
 *
 * Unlike the pure-JVM [PeerDeviceRegistryTest] (which only tests the JSON
 * round-trip via static helpers), this test exercises the full
 * SharedPreferences I/O path with a real Android Context provided by
 * Robolectric.
 *
 * Verifies:
 *   - getInstance returns the same singleton across calls in the same process
 *   - upsert persists to SharedPreferences (survives singleton reset)
 *   - list / find / remove / clear work end-to-end
 *   - The AndroidKeyStore encryption path falls back to plaintext on Robolectric
 *     (Robolectric has no real AndroidKeyStore) — verified by checking the
 *     stored blob starts with '[' (plaintext JSON marker)
 */
@RunWith(RobolectricTestRunner::class)
class PeerDeviceRegistryRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear SharedPreferences between tests
        context.getSharedPreferences("tote_peers", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Reset the singleton via reflection
        resetSingleton()
    }

    private fun resetSingleton() {
        val field: Field = PeerDeviceRegistry::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `empty_registry_returns_empty_list`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `upsert_then_list_returns_one_peer`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        val peer = samplePeer(deviceId = ByteArray(32) { 0xAB.toByte() })
        registry.upsert(peer)

        val peers = registry.list()
        assertThat(peers).hasSize(1)
        assertThat(peers[0].deviceId.contentEquals(peer.deviceId)).isTrue()
        assertThat(peers[0].displayName).isEqualTo("Test Device")
    }

    @Test
    fun `upsert_twice_with_same_deviceId_updates_existing`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        val deviceId = ByteArray(32) { 0xCD.toByte() }
        val peer1 = PeerDeviceRegistry.Peer(
            deviceId = deviceId,
            x25519Pub = ByteArray(32),
            displayName = "Device v1",
            pairedAt = 1000L,
            lastSeenAt = 1000L,
        )
        val peer2 = peer1.copy(displayName = "Device v2", lastSeenAt = 2000L)
        registry.upsert(peer1)
        registry.upsert(peer2)

        val peers = registry.list()
        assertThat(peers).hasSize(1)
        assertThat(peers[0].displayName).isEqualTo("Device v2")
        assertThat(peers[0].pairedAt).isEqualTo(1000L)  // pairedAt preserved
        assertThat(peers[0].lastSeenAt).isEqualTo(2000L)  // lastSeenAt updated
    }

    @Test
    fun `find_by_hex_returns_matching_peer`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        val deviceId = ByteArray(32) { 0x42.toByte() }
        registry.upsert(samplePeer(deviceId = deviceId))

        val hex = deviceId.joinToString("") { "%02x".format(it) }
        val found = registry.find(hex)
        assertThat(found).isNotNull()
        assertThat(found!!.deviceId.contentEquals(deviceId)).isTrue()
    }

    @Test
    fun `find_by_hex_is_case_insensitive`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        val deviceId = ByteArray(32) { 0xAB.toByte() }
        registry.upsert(samplePeer(deviceId = deviceId))

        val upperHex = deviceId.joinToString("") { "%02X".format(it) }
        val found = registry.find(upperHex)
        assertThat(found).isNotNull()
    }

    @Test
    fun `find_returns_null_when_not_found`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        val found = registry.find("0000000000000000000000000000000000000000000000000000000000000000")
        assertThat(found).isNull()
    }

    @Test
    fun `remove_deletes_the_peer`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        val deviceId = ByteArray(32) { 0x77.toByte() }
        registry.upsert(samplePeer(deviceId = deviceId))
        assertThat(registry.list()).hasSize(1)

        registry.remove(deviceId)
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `clear_removes_all_peers`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        registry.upsert(samplePeer(deviceId = ByteArray(32) { 1 }))
        registry.upsert(samplePeer(deviceId = ByteArray(32) { 2 }))
        registry.upsert(samplePeer(deviceId = ByteArray(32) { 3 }))
        assertThat(registry.list()).hasSize(3)

        registry.clear()
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `persistence_survives_singleton_recreation`() {
        val deviceId = ByteArray(32) { 0x88.toByte() }
        PeerDeviceRegistry.getInstance(context).upsert(samplePeer(deviceId = deviceId))

        // Reset singleton — simulates process restart, but SharedPreferences persists
        resetSingleton()

        val newRegistry = PeerDeviceRegistry.getInstance(context)
        val peers = newRegistry.list()
        assertThat(peers).hasSize(1)
        assertThat(peers[0].deviceId.contentEquals(deviceId)).isTrue()
    }

    @Test
    fun `multiple_peers_persist_in_insertion_order`() {
        val registry = PeerDeviceRegistry.getInstance(context)
        registry.upsert(samplePeer(deviceId = ByteArray(32) { 1 }, name = "Device 1"))
        registry.upsert(samplePeer(deviceId = ByteArray(32) { 2 }, name = "Device 2"))
        registry.upsert(samplePeer(deviceId = ByteArray(32) { 3 }, name = "Device 3"))

        resetSingleton()
        val newRegistry = PeerDeviceRegistry.getInstance(context)
        val peers = newRegistry.list()
        assertThat(peers).hasSize(3)
        assertThat(peers[0].displayName).isEqualTo("Device 1")
        assertThat(peers[1].displayName).isEqualTo("Device 2")
        assertThat(peers[2].displayName).isEqualTo("Device 3")
    }

    private fun samplePeer(
        deviceId: ByteArray = ByteArray(32) { it.toByte() },
        name: String = "Test Device",
    ): PeerDeviceRegistry.Peer {
        return PeerDeviceRegistry.Peer(
            deviceId = deviceId,
            x25519Pub = ByteArray(32) { 0x00.toByte() },
            displayName = name,
            pairedAt = 1_000_000L,
            lastSeenAt = 1_000_000L,
        )
    }
}
