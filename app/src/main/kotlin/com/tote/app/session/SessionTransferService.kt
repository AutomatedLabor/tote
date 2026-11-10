package com.tote.app.session

import android.content.Context
import android.util.Log
import androidx.webkit.Profile
import androidx.webkit.WebViewCompat
import com.tote.app.crypto.DeviceIdentity
import com.tote.app.crypto.EpochState
import com.tote.app.relay.InboxEntry
import com.tote.app.relay.RelayClient
import com.tote.app.relay.RelayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * High-level orchestration: snapshot → upload to relay; or poll relay → apply.
 *
 * This is the "transfer that session" verb. Wires together:
 * - [SessionSnapshot] (extract + sign + encrypt)
 * - [RelayClient] (upload to recipient's inbox)
 * - [EpochState] (current epoch key)
 * - [DeviceIdentity] (sender's signing key)
 *
 * Usage (sender side):
 * ```
 * val service = SessionTransferService(context, relayConfig, identity, epochState)
 * val result = service.sendSession(profileId, profile, recipientDeviceId)
 * if (result.success) {
 *     // Session blob is now sitting in recipient's inbox on the relay
 * }
 * ```
 *
 * Usage (receiver side):
 * ```
 * val incoming = service.pollInbox()
 * for (entry in incoming) {
 *     val blob = service.downloadEnvelope(entry.id)
 *     val applied = service.applyIncoming(blob, senderPub)
 *     if (applied > 0) {
 *         service.ackEnvelope(entry.id)
 *     }
 * }
 * ```
 */
class SessionTransferService(
    private val context: Context,
    private val relayConfig: RelayConfig,
    private val identity: DeviceIdentity,
    private val epochState: EpochState,
) {
    private val TAG = "SessionTransferService"
    private val snapshot = SessionSnapshot(context)
    private val relay = RelayClient(relayConfig)

    private val _lastSendResult = MutableStateFlow<SendResult?>(null)
    val lastSendResult: StateFlow<SendResult?> = _lastSendResult.asStateFlow()

    private val _lastReceiveResult = MutableStateFlow<ReceiveResult?>(null)
    val lastReceiveResult: StateFlow<ReceiveResult?> = _lastReceiveResult.asStateFlow()

    /**
     * Snapshot the current profile's cookies, sign, encrypt, and upload to the
     * recipient's inbox on the relay.
     *
     * @param profileId The Tote profile ID (UUID)
     * @param profile The AndroidX Profile (null = use default profile path)
     * @param recipientDeviceId The recipient's Ed25519 pubkey (32 bytes)
     * @param sequence Monotonic sequence number for this transfer
     * @return [SendResult] with success/failure + blob size
     */
    suspend fun sendSession(
        profileId: String,
        profile: Profile?,
        recipientDeviceId: ByteArray,
        sequence: Long,
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            // 1. Snapshot + sign + encrypt
            val snap = snapshot.snapshotForProfile(
                profile = profile,
                profileId = profileId,
                epochState = epochState,
                identity = identity,
                sequence = sequence,
            )
            Log.i(TAG, "Snapshot produced: ${snap.recordCount} cookies, ${snap.blob.size} bytes blob")

            // 2. Compute idempotency key for the relay
            val idemKey = com.tote.app.session.SessionBlob.idempotencyKey(
                epochKey = epochState.currentKey,
                senderDeviceId = identity.ed25519Pub,
                seq = sequence,
                profileId = profileId.toByteArray(Charsets.US_ASCII).copyOf(16),
                ct = snap.blob,
            )

            // 3. Upload to relay
            val uploaded = relay.postEnvelope(
                recipientId = recipientDeviceId,
                blob = snap.blob,
                idempotencyKey = idemKey,
            )

            val result = SendResult(
                success = uploaded,
                blobSize = snap.blob.size,
                cookieCount = snap.recordCount,
                fidelityBreakdown = snap.fidelityBreakdown,
                error = if (uploaded) null else "Relay upload failed",
            )
            _lastSendResult.value = result
            result
        } catch (t: Throwable) {
            Log.e(TAG, "sendSession failed", t)
            val result = SendResult(
                success = false,
                blobSize = 0,
                cookieCount = 0,
                fidelityBreakdown = emptyMap(),
                error = t.message ?: "Unknown error",
            )
            _lastSendResult.value = result
            result
        }
    }

    /**
     * Poll the relay for pending envelopes in this device's inbox.
     */
    suspend fun pollInbox(): List<InboxEntry> = withContext(Dispatchers.IO) {
        relay.listInbox() ?: emptyList()
    }

    /**
     * Download a specific envelope's blob from the relay.
     */
    suspend fun downloadEnvelope(envelopeId: String): ByteArray? = withContext(Dispatchers.IO) {
        relay.getEnvelope(envelopeId)
    }

    /**
     * Apply an incoming SessionBlob to the local WebView.
     *
     * @param blob The wire-format SessionBlob bytes
     * @param senderPub The sender's Ed25519 pubkey (32 bytes) for signature verification
     * @return The number of cookies imported
     */
    suspend fun applyIncoming(blob: ByteArray, senderPub: ByteArray): Int = withContext(Dispatchers.IO) {
        try {
            val count = snapshot.applyBlob(blob, epochState.currentKey, senderPub)
            Log.i(TAG, "Applied incoming blob: $count cookies imported")
            _lastReceiveResult.value = ReceiveResult(success = true, cookieCount = count, error = null)
            count
        } catch (t: Throwable) {
            Log.e(TAG, "applyIncoming failed", t)
            _lastReceiveResult.value = ReceiveResult(success = false, cookieCount = 0, error = t.message ?: "Unknown error")
            0
        }
    }

    /**
     * Ack an envelope on the relay (DELETE /envelope/{id}).
     */
    suspend fun ackEnvelope(envelopeId: String): Boolean = withContext(Dispatchers.IO) {
        relay.ackEnvelope(envelopeId)
    }

    /**
     * Check relay health.
     */
    suspend fun healthz(): Boolean = withContext(Dispatchers.IO) {
        relay.healthz()
    }

    /** Result of a send operation. */
    data class SendResult(
        val success: Boolean,
        val blobSize: Int,
        val cookieCount: Int,
        val fidelityBreakdown: Map<String, Int>,
        val error: String?,
    )

    /** Result of a receive operation. */
    data class ReceiveResult(
        val success: Boolean,
        val cookieCount: Int,
        val error: String?,
    )
}
