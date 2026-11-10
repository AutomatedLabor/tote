package com.tote.app.relay

import android.util.Log
import com.tote.app.crypto.SHA256
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for the Tote relay server.
 *
 * Per CRYPTO-SPEC-FINAL §6 + the Go relay spec (`tote-relay/main.go`):
 * - POST /envelope — upload a blob to a recipient's inbox
 * - GET /inbox/{deviceId} — list pending blobs
 * - GET /envelope/{id} — download a specific blob
 * - DELETE /envelope/{id} — ack after applying
 * - WS /inbox/{deviceId}/events — push notifications for new blobs
 * - GET /healthz — liveness probe
 *
 * PoW (per relay spec): every POST must include a 20-bit SHA-256 prefix
 * hashcash header `X-Tote-PoW: <nonce>`. The relay validates by hashing the
 * request body + nonce and checking the top 20 bits are zero.
 *
 * Rate limit: 30 requests per minute per IP. Local token-bucket enforces this
 * client-side so we don't burn through the limit accidentally.
 *
 * Payload cap: 4MB. Enforced client-side before send.
 */
class RelayClient(private val config: RelayConfig) {
    private val TAG = "RelayClient"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val rateLimitTokens = AtomicInteger(30)
    private var lastRefillTime = System.currentTimeMillis()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var eventsWebSocket: WebSocket? = null

    /**
     * Compute a 20-bit SHA-256 prefix-hashcash nonce for the given payload.
     *
     * Visible for testing.
     */
    fun computePowNonce(payload: ByteArray): String {
        // Top 20 bits of SHA-256(payload || nonce) must be zero.
        // That means hash[0] == 0, hash[1] == 0, and (hash[2] & 0xF0) == 0.
        var nonce = 0L
        val nonceBytes = ByteArray(8)
        while (true) {
            for (i in 0 until 8) {
                nonceBytes[i] = (nonce ushr ((7 - i) * 8)).toByte()
            }
            val hash = SHA256.hash(payload + nonceBytes)
            if (hash[0] == 0.toByte() && hash[1] == 0.toByte() && (hash[2].toInt() and 0xF0) == 0) {
                return nonce.toString(16)
            }
            nonce++
            if (nonce < 0) {
                throw IllegalStateException("PoW overflow (2^63 iterations)")
            }
        }
    }

    /**
     * POST /envelope — upload an encrypted blob to a recipient's inbox.
     *
     * @param recipientId The recipient's device ID (Ed25519 pubkey, 32 bytes)
     * @param blob The SessionBlob bytes
     * @param idempotencyKey Dedup key (HMAC of epochKey || senderId || seq || profileId || ct)
     * @param ttl Time-to-live in seconds (max 30 days = 2592000)
     * @return true if uploaded successfully
     */
    fun postEnvelope(
        recipientId: ByteArray,
        blob: ByteArray,
        idempotencyKey: ByteArray,
        ttl: Int = 7 * 24 * 60 * 60,
    ): Boolean {
        if (blob.size > MAX_PAYLOAD_BYTES) {
            Log.e(TAG, "Payload exceeds 4MB cap: ${blob.size} bytes")
            return false
        }
        if (!consumeRateToken()) {
            Log.w(TAG, "Rate limited — try again later")
            return false
        }

        val powNonce = computePowNonce(blob)
        val url = "${config.baseUrl}/envelope"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("recipient_id", recipientId.toHex())
            .addFormDataPart("blob", blob.toBase64())
            .addFormDataPart("idempotency_key", idempotencyKey.toHex())
            .addFormDataPart("ttl", ttl.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("X-Tote-PoW", powNonce)
            .addHeader("X-Tote-Device-Id", config.localDeviceId.toBase64())
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 201 -> {
                        Log.i(TAG, "POST /envelope → ${response.code} (${blob.size} bytes)")
                        true
                    }
                    429 -> {
                        Log.w(TAG, "Rate limited by relay")
                        false
                    }
                    else -> {
                        Log.e(TAG, "POST /envelope → ${response.code}")
                        false
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "POST /envelope failed: ${t.message}")
            false
        }
    }

    /**
     * GET /inbox/{deviceId} — list pending envelopes in this device's inbox.
     */
    fun listInbox(): List<InboxEntry>? {
        if (!consumeRateToken()) return null
        val url = "${config.baseUrl}/inbox/${config.localDeviceId.toHex()}"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Tote-Device-Id", config.localDeviceId.toBase64())
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    Log.e(TAG, "GET /inbox → ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                parseInboxResponse(body)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "GET /inbox failed: ${t.message}")
            null
        }
    }

    /**
     * GET /envelope/{id} — download a specific envelope's blob.
     */
    fun getEnvelope(envelopeId: String): ByteArray? {
        if (!consumeRateToken()) return null
        val url = "${config.baseUrl}/envelope/$envelopeId"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-Tote-Device-Id", config.localDeviceId.toBase64())
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    Log.e(TAG, "GET /envelope/$envelopeId → ${response.code}")
                    return null
                }
                response.body?.bytes()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "GET /envelope/$envelopeId failed: ${t.message}")
            null
        }
    }

    /**
     * DELETE /envelope/{id} — ack after applying.
     */
    fun ackEnvelope(envelopeId: String): Boolean {
        if (!consumeRateToken()) return false
        val url = "${config.baseUrl}/envelope/$envelopeId"
        val request = Request.Builder()
            .url(url)
            .delete()
            .addHeader("X-Tote-Device-Id", config.localDeviceId.toBase64())
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                response.code in 200..299
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DELETE /envelope/$envelopeId failed: ${t.message}")
            false
        }
    }

    /**
     * GET /healthz — liveness probe.
     */
    fun healthz(): Boolean {
        val url = "${config.baseUrl}/healthz"
        val request = Request.Builder().url(url).get().build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                response.code == 200
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Connect to the WebSocket for push notifications on new envelopes.
     */
    fun connectEvents(onEnvelope: (InboxEntry) -> Unit) {
        if (eventsWebSocket != null) {
            Log.w(TAG, "Events WebSocket already connected")
            return
        }
        val url = "${config.baseUrlWs}/inbox/${config.localDeviceId.toHex()}/events"
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Tote-Device-Id", config.localDeviceId.toBase64())
            .build()
        eventsWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                Log.i(TAG, "WebSocket connected")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val entry = parseInboxEntry(text)
                    onEnvelope(entry)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to parse WS message: ${t.message}")
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                Log.i(TAG, "WebSocket closed: $code $reason")
                eventsWebSocket = null
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.ERROR
                Log.e(TAG, "WebSocket failure: ${t.message}")
                eventsWebSocket = null
            }
        })
    }

    fun disconnect() {
        eventsWebSocket?.close(1000, "client disconnect")
        eventsWebSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Consume one rate-limit token. Refills 30 tokens per minute.
     */
    fun consumeRateToken(): Boolean {
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastRefillTime
        val newTokens = (elapsedMs * 30 / 60_000).toInt()
        if (newTokens > 0) {
            rateLimitTokens.updateAndGet { (it + newTokens).coerceAtMost(30) }
            lastRefillTime = now
        }
        val newVal = rateLimitTokens.decrementAndGet()
        return newVal >= 0
    }

    /**
     * Parse the JSON response of GET /inbox/{id}.
     *
     * Two envelope shapes are supported:
     *   {"id":"…","size":N,"received_at":N,"sender_id":"<hex>"}
     *   {"id":"…","size":N,"received_at":N}
     *
     * The sender_id is optional (older relays don't expose it).
     */
    private fun parseInboxResponse(body: String): List<InboxEntry> {
        val entries = mutableListOf<InboxEntry>()
        val regex = Regex("""\{"id":"([^"]+)","size":(\d+),"received_at":(\d+)(?:,"sender_id":"([^"]*)")?\}""")
        for (m in regex.findAll(body)) {
            entries.add(InboxEntry(
                id = m.groupValues[1],
                size = m.groupValues[2].toInt(),
                receivedAt = m.groupValues[3].toLong(),
                senderId = m.groupValues[4].takeIf { it.isNotEmpty() },
            ))
        }
        return entries
    }

    private fun parseInboxEntry(json: String): InboxEntry {
        val regex = Regex("""\{"id":"([^"]+)","size":(\d+),"received_at":(\d+)(?:,"sender_id":"([^"]*)")?\}""")
        val m = regex.find(json) ?: throw IllegalArgumentException("Bad inbox entry JSON: $json")
        return InboxEntry(
            id = m.groupValues[1],
            size = m.groupValues[2].toInt(),
            receivedAt = m.groupValues[3].toLong(),
            senderId = m.groupValues[4].takeIf { it.isNotEmpty() },
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun ByteArray.toBase64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    companion object {
        const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024  // 4MB
    }
}

data class RelayConfig(
    val baseUrl: String,           // e.g. "https://relay.tote.app/v1"
    val baseUrlWs: String,         // e.g. "wss://relay.tote.app/v1"
    val localDeviceId: ByteArray,  // Ed25519 pubkey (32 bytes)
) {
    // Custom equals to avoid ByteArray identity comparison.
    override fun equals(other: Any?): Boolean = other is RelayConfig &&
        baseUrl == other.baseUrl && localDeviceId.contentEquals(other.localDeviceId)
    override fun hashCode(): Int = baseUrl.hashCode() * 31 + localDeviceId.contentHashCode()
}

data class InboxEntry(
    val id: String,
    val size: Int,
    val receivedAt: Long,
    /**
     * Sender's local device ID (hex-encoded Ed25519 pubkey, 64 chars).
     * Populated by the relay's envelope metadata; null if the relay doesn't
     * expose it. Used by [SessionSyncService] to look up the sender's pubkey
     * in [PeerDeviceRegistry] for signature verification on receive.
     */
    val senderId: String? = null,
)

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
