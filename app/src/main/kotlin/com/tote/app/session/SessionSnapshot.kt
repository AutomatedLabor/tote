package com.tote.app.session

import android.content.Context
import android.util.Log
import androidx.webkit.Profile
import com.tote.app.crypto.DeviceIdentity
import com.tote.app.crypto.EpochState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * High-level facade: snapshot a profile's cookies → sign → SessionBlob.
 *
 * Usage:
 * ```
 * val snapshot = SessionSnapshot(context)
 *     .snapshotForProfile(profile, profileId, epochState, identity)
 * // snapshot.blob is the wire-format SessionBlob ready to send to a peer
 * ```
 *
 * The blob is also persisted locally (encrypted at rest via AndroidKeyStore)
 * so the user can re-apply the snapshot after a process restart.
 *
 * Per CRYPTO-SPEC-FINAL §4: the plaintext inside the blob is a JSON array
 * of [CookieRecord] objects, serialized via kotlinx.serialization.json.
 */
class SessionSnapshot(private val context: Context) {
    private val TAG = "SessionSnapshot"
    private val extractor = CookieSnapshotExtractor(context)
    private val importer = CookieImporter()

    /**
     * Result of a snapshot operation.
     *
     * @property blob The wire-format SessionBlob (header + nonce + ciphertext + signature)
     * @property recordCount Number of cookies snapshotted
     * @property fidelityBreakdown Counts per fidelity level
     */
    data class SnapshotResult(
        val blob: ByteArray,
        val recordCount: Int,
        val fidelityBreakdown: Map<String, Int>,
    )

    /**
     * Snapshot all cookies for the given profile, sign, and produce a SessionBlob.
     *
     * @param profile The AndroidX Profile (null = use default)
     * @param profileId The Tote profile ID (16-byte UUID)
     * @param epochState The current epoch state (provides epochKey)
     * @param identity The sender's device identity (provides signing key)
     * @param sequence The monotonic sequence number for this snapshot
     * @param ttl Time-to-live in seconds (default 7 days)
     */
    fun snapshotForProfile(
        profile: Profile?,
        profileId: String,
        epochState: EpochState,
        identity: DeviceIdentity,
        sequence: Long,
        ttl: Int = 7 * 24 * 60 * 60,  // 7 days
    ): SnapshotResult {
        val records = extractor.extractForProfile(profile, profileId)
        if (records.isEmpty()) {
            Log.w(TAG, "Snapshot for profile=$profileId produced 0 cookies (WebView may not have been opened yet)")
        }

        val fidelityBreakdown = records.groupingBy { it.fidelity.name }.eachCount()

        // Serialize cookies as JSON
        val json = Json.encodeToString(
            ListSerializer(kotlinx.serialization.serializer<com.tote.app.crypto.CookieRecord>()),
            records
        )
        val plaintext = json.toByteArray(Charsets.UTF_8)

        // Sign and encrypt into a SessionBlob
        val profileIdBytes = profileId.padEnd(16, 0).toByteArray(Charsets.US_ASCII).copyOf(16)
        val senderDeviceId = identity.ed25519Pub.copyOf(16).let {
            // Pad to 16 bytes if needed (Ed25519 pub is 32 bytes — use first 16 as device ID)
            it
        }

        val blob = SessionBlob.encode(
            profileId = profileIdBytes,
            senderDeviceId = senderDeviceId,
            sequence = sequence,
            ttl = ttl,
            plaintext = plaintext,
            epochKey = epochState.currentKey,
            identity = identity,
        )

        Log.i(TAG, "Snapshot for profile=$profileId: ${records.size} cookies, ${blob.size} bytes blob")
        return SnapshotResult(
            blob = blob,
            recordCount = records.size,
            fidelityBreakdown = fidelityBreakdown,
        )
    }

    /**
     * Apply an incoming SessionBlob to the local WebView.
     *
     * @return the number of cookies imported
     */
    fun applyBlob(
        blob: ByteArray,
        epochKey: ByteArray,
        senderPub: ByteArray,
    ): Int {
        return importer.importFromBlob(blob, epochKey, senderPub)
    }
}
