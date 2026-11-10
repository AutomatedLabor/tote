package com.tote.app.session

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Persistent registry of devices paired with this one.
 *
 * Each entry stores:
 * - deviceId: the peer's Ed25519 public key (32 bytes, hex-encoded for display)
 * - displayName: a user-friendly label (auto-generated from device fingerprint short hash, or
 *   user-set)
 * - x25519Pub: the peer's X25519 public key (32 bytes) — needed for future direct
 *   ECDH on session-send (post-relay-handoff, future work)
 * - pairedAt: epoch millis when pairing completed
 * - lastSeenAt: epoch millis of last relay exchange (updated on send/receive)
 *
 * Per ADR-019: persisted as a single JSON blob in app-private SharedPreferences.
 * AndroidKeyStore AES-GCM wrapping is a v1.0.0 TODO (same as IdentityStore).
 *
 * Thread-safe: all reads/writes go through `@Synchronized` methods.
 */
class PeerDeviceRegistry private constructor(private val context: Context) {

    private val TAG = "PeerDeviceRegistry"
    private val PREFS = "tote_peers"
    private val KEY_PEERS = "peers_json"
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cache: List<Peer>? = null

    data class Peer(
        val deviceId: ByteArray,      // 32 bytes Ed25519 pub
        val x25519Pub: ByteArray,      // 32 bytes X25519 pub
        val displayName: String,
        val pairedAt: Long,
        val lastSeenAt: Long,
    )

    @Synchronized
    fun list(): List<Peer> {
        cache?.let { return it }
        val json = prefs.getString(KEY_PEERS, null) ?: run {
            cache = emptyList()
            return emptyList()
        }
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<Peer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Peer(
                        deviceId = Base64.getDecoder().decode(o.getString("deviceId")),
                        x25519Pub = Base64.getDecoder().decode(o.getString("x25519Pub")),
                        displayName = o.getString("displayName"),
                        pairedAt = o.getLong("pairedAt"),
                        lastSeenAt = o.getLong("lastSeenAt"),
                    )
                )
            }
            cache = out
            out
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse peers JSON — wiping and starting fresh", t)
            prefs.edit().remove(KEY_PEERS).apply()
            cache = emptyList()
            emptyList()
        }
    }

    @Synchronized
    fun upsert(peer: Peer) {
        val current = list().toMutableList()
        val existingIdx = current.indexOfFirst { it.deviceId.contentEquals(peer.deviceId) }
        if (existingIdx >= 0) {
            // Update — keep the original pairedAt, refresh lastSeenAt
            current[existingIdx] = peer.copy(pairedAt = current[existingIdx].pairedAt)
        } else {
            current.add(peer)
        }
        persist(current)
    }

    @Synchronized
    fun remove(deviceId: ByteArray) {
        val current = list().filterNot { it.deviceId.contentEquals(deviceId) }
        persist(current)
    }

    @Synchronized
    fun find(deviceIdHex: String): Peer? {
        val target = deviceIdHex.lowercase().replace(" ", "").replace(":", "")
        return list().find { it.deviceId.toHex() == target }
    }

    @Synchronized
    fun find(deviceId: ByteArray): Peer? {
        return list().find { it.deviceId.contentEquals(deviceId) }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_PEERS).apply()
        cache = emptyList()
        Log.w(TAG, "PeerDeviceRegistry CLEARED")
    }

    private fun persist(peers: List<Peer>) {
        val arr = JSONArray()
        for (p in peers) {
            arr.put(JSONObject().apply {
                put("deviceId", Base64.getEncoder().encodeToString(p.deviceId))
                put("x25519Pub", Base64.getEncoder().encodeToString(p.x25519Pub))
                put("displayName", p.displayName)
                put("pairedAt", p.pairedAt)
                put("lastSeenAt", p.lastSeenAt)
            })
        }
        prefs.edit().putString(KEY_PEERS, arr.toString()).apply()
        cache = peers
        Log.i(TAG, "Persisted ${peers.size} peer(s)")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        @Volatile private var instance: PeerDeviceRegistry? = null
        fun getInstance(context: Context): PeerDeviceRegistry =
            instance ?: synchronized(this) {
                instance ?: PeerDeviceRegistry(context.applicationContext).also { instance = it }
            }
    }
}
