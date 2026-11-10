package com.tote.app.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [PeerDeviceRegistry]'s JSON serialization round-trip.
 *
 * We can't test the SharedPreferences-backed I/O on the JVM (no Context),
 * but we CAN test that `peersToJson` → `peersFromJson` round-trips
 * correctly — including all fields, and that order is preserved.
 *
 * Note: [PeerDeviceRegistry.Peer] is a data class with [ByteArray] fields.
 * Data class `equals` uses reference equality for arrays (NOT contentEquals),
 * so we use [contentEquals] explicitly in the assertions.
 *
 * The actual SharedPreferences I/O is exercised on-device during manual
 * testing and would be the next thing to cover if we added Robolectric
 * (deliberately avoided to keep the test runner lean).
 */
class PeerDeviceRegistryTest {

    @Test
    fun `empty_list_serializes_to_empty_json_array`() {
        val json = PeerDeviceRegistry.peersToJson(emptyList())
        assertThat(json).isEqualTo("[]")
    }

    @Test
    fun `single_peer_round_trips_through_json`() {
        val peer = PeerDeviceRegistry.Peer(
            deviceId = ByteArray(32) { 0xAB.toByte() },
            x25519Pub = ByteArray(32) { 0xCD.toByte() },
            displayName = "Test Device",
            pairedAt = 1_700_000_000_000L,
            lastSeenAt = 1_700_000_001_000L,
        )
        val json = PeerDeviceRegistry.peersToJson(listOf(peer))
        val recovered = PeerDeviceRegistry.peersFromJson(json)
        assertThat(recovered).hasSize(1)
        val r = recovered[0]
        // Use contentEquals for ByteArray comparison
        assertThat(r.deviceId.contentEquals(peer.deviceId)).isTrue()
        assertThat(r.x25519Pub.contentEquals(peer.x25519Pub)).isTrue()
        assertThat(r.displayName).isEqualTo("Test Device")
        assertThat(r.pairedAt).isEqualTo(1_700_000_000_000L)
        assertThat(r.lastSeenAt).isEqualTo(1_700_000_001_000L)
    }

    @Test
    fun `multiple_peers_round_trip_preserving_order`() {
        val peers = listOf(
            samplePeer(0x01, "Device A"),
            samplePeer(0x02, "Device B"),
            samplePeer(0x03, "Device C"),
        )
        val json = PeerDeviceRegistry.peersToJson(peers)
        val recovered = PeerDeviceRegistry.peersFromJson(json)
        assertThat(recovered).hasSize(3)
        assertThat(recovered[0].displayName).isEqualTo("Device A")
        assertThat(recovered[1].displayName).isEqualTo("Device B")
        assertThat(recovered[2].displayName).isEqualTo("Device C")
    }

    @Test
    fun `peer_with_unicode_display_name_round_trips`() {
        val peer = PeerDeviceRegistry.Peer(
            deviceId = ByteArray(32),
            x25519Pub = ByteArray(32),
            displayName = "📱 My Phone ✓",
            pairedAt = 0L,
            lastSeenAt = 0L,
        )
        val json = PeerDeviceRegistry.peersToJson(listOf(peer))
        val recovered = PeerDeviceRegistry.peersFromJson(json)
        assertThat(recovered[0].displayName).isEqualTo("📱 My Phone ✓")
    }

    @Test
    fun `round_trip_preserves_32_byte_device_id_intact`() {
        val originalId = ByteArray(32) { (it * 7 + 3).toByte() }
        val peer = PeerDeviceRegistry.Peer(
            deviceId = originalId,
            x25519Pub = ByteArray(32),
            displayName = "Test",
            pairedAt = 0L,
            lastSeenAt = 0L,
        )
        val json = PeerDeviceRegistry.peersToJson(listOf(peer))
        val recovered = PeerDeviceRegistry.peersFromJson(json)
        assertThat(recovered[0].deviceId.contentEquals(originalId)).isTrue()
        assertThat(recovered[0].deviceId.size).isEqualTo(32)
    }

    @Test
    fun `malformed_json_throws_JSONException`() {
        try {
            PeerDeviceRegistry.peersFromJson("not json")
            throw AssertionError("Expected JSONException")
        } catch (t: org.json.JSONException) {
            // expected
        }
    }

    @Test
    fun `json_array_with_wrong_field_types_throws_JSONException`() {
        // displayName is a string but we put a number
        val badJson = """[{"deviceId":"AAA","x25519Pub":"BBB","displayName":42,"pairedAt":0,"lastSeenAt":0}]"""
        try {
            PeerDeviceRegistry.peersFromJson(badJson)
            throw AssertionError("Expected JSONException")
        } catch (t: org.json.JSONException) {
            // expected
        } catch (t: ClassCastException) {
            // expected — org.json may also throw ClassCastException when a string field is a number
        }
    }

    @Test
    fun `json_with_missing_field_throws_JSONException`() {
        val badJson = """[{"deviceId":"AAA"}]"""  // missing x25519Pub, displayName, etc.
        try {
            PeerDeviceRegistry.peersFromJson(badJson)
            throw AssertionError("Expected JSONException")
        } catch (t: org.json.JSONException) {
            // expected
        }
    }

    @Test
    fun `large_peer_list_round_trips`() {
        val peers = (1..100).map { i ->
            samplePeer(i.toByte(), "Device $i")
        }
        val json = PeerDeviceRegistry.peersToJson(peers)
        val recovered = PeerDeviceRegistry.peersFromJson(json)
        assertThat(recovered).hasSize(100)
        assertThat(recovered[50].displayName).isEqualTo("Device 51")
    }

    private fun samplePeer(idByte: Byte, name: String): PeerDeviceRegistry.Peer {
        return PeerDeviceRegistry.Peer(
            deviceId = ByteArray(32) { idByte },
            x25519Pub = ByteArray(32) { (idByte + 1).toByte() },
            displayName = name,
            pairedAt = 1_000_000L,
            lastSeenAt = 1_000_000L,
        )
    }
}
