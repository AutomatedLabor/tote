package com.tote.app.session

import android.content.Context
import android.util.Log
import com.tote.app.crypto.KeystoreWrapper
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
 * Per ADR-019: persisted as a single JSON blob in app-private SharedPreferences,
 * wrapped via [KeystoreWrapper] (AndroidKeyStore AES-GCM) so a stolen backup
 * can't recover the peer list.
 *
 * If AndroidKeyStore is unavailable (rare, broken TEE), we fall back to
 * plaintext storage with a warning.
 *
 * Thread-safe: all reads/writes go through `@Synchronized` methods.
 */
class PeerDeviceRegistry private constructor(private val context: Context) {

    private val TAG = "PeerDeviceRegistry"
    private val PREFS = "tote_peers"
    private val KEY_PEERS = "peers_json"
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keystore = KeystoreWrapper(context)

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
        val stored = prefs.getString(KEY_PEERS, null) ?: run {
            cache = emptyList()
            return emptyList()
        }
        return try {
            // Decrypt via KeystoreWrapper if encrypted (heuristic: starts with non-'['
            // byte = encrypted; starts with '[' = plaintext JSON from older version)
            val raw = Base64.getDecoder().decode(stored)
            val json = decryptIfEncrypted(raw).toString(Charsets.UTF_8)
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
        val jsonStr = peersToJson(peers)
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        val ciphertext = encryptIfAvailable(jsonBytes)
        val b64 = Base64.getEncoder().encodeToString(ciphertext)
        prefs.edit().putString(KEY_PEERS, b64).apply()
        cache = peers
        Log.i(TAG, "Persisted ${peers.size} peer(s) (encrypted=${ciphertext.size > jsonBytes.size})")
    }

    companion object {
        @Volatile private var instance: PeerDeviceRegistry? = null
        fun getInstance(context: Context): PeerDeviceRegistry =
            instance ?: synchronized(this) {
                instance ?: PeerDeviceRegistry(context.applicationContext).also { instance = it }
            }

        /**
         * Serialize a list of peers to JSON. Exposed as a static method so
         * JVM tests can verify the round-trip without needing a Context.
         *
         * Format:
         * ```
         * [{"deviceId":"<base64>","x25519Pub":"<base64>",
         *   "displayName":"<str>","pairedAt":<long>,"lastSeenAt":<long>}, ...]
         * ```
         */
        fun peersToJson(peers: List<Peer>): String {
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
            return arr.toString()
        }

        /**
         * Deserialize a JSON string back into a list of peers. Inverse of
         * [peersToJson]. Exposed for JVM tests.
         *
         * @throws org.json.JSONException if the JSON is malformed
         */
        fun peersFromJson(json: String): List<Peer> {
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
            return out
        }
    }

    /**
     * Encrypt via KeystoreWrapper, fall back to plaintext if keystore unavailable.
     */
    private fun encryptIfAvailable(plaintext: ByteArray): ByteArray {
        return try {
            keystore.encrypt(plaintext)
        } catch (t: Throwable) {
            Log.w(TAG, "AndroidKeyStore unavailable — storing peers in plaintext (INSECURE): ${t.message}")
            plaintext
        }
    }

    /**
     * Decrypt if encrypted; detect plaintext by trying to parse as JSON first.
     */
    private fun decryptIfEncrypted(stored: ByteArray): ByteArray {
        // Heuristic: if the bytes start with '[' (0x5B) it's plaintext JSON
        // from an older version; otherwise treat as encrypted.
        return if (stored.isNotEmpty() && stored[0] == '['.code.toByte()) {
            stored
        } else {
            try {
                keystore.decrypt(stored)
            } catch (t: Throwable) {
                Log.w(TAG, "Decryption failed — treating as plaintext: ${t.message}")
                stored
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

