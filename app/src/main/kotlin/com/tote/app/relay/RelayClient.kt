package com.tote.app.relay

import android.util.Log
import com.tote.app.crypto.HKDF
import com.tote.app.crypto.SHA256
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for the Tote relay server.
 *
 * Per CRYPTO-SPEC-FINAL §6 + the Go relay spec (`tote-relay/main.go`):
 * - POST /envelope — upload a blob
 * - GET /inbox/{deviceId} — list pending blobs
 * - DELETE /envelope/{id} — ack after applying
 * - WS /inbox/{deviceId}/events — push notifications for new blobs
 * - GET /healthz — liveness probe
 *
 * PoW (per relay spec): every request must include a 20-bit SHA-256 prefix
 * hashcash header `X-Tote-PoW: <nonce>`. The relay validates by hashing the
 * request body + nonce and checking the top 20 bits are zero.
 *
 * Rate limit: 30 requests per minute per IP. Local token-bucket enforces this
 * client-side so we don't burn through the limit accidentally.
 *
 * Payload cap: 4MB. Enforced client-side before send.
 *
 * TLS: required in production. For local dev the relay may listen on plain
 * HTTP — controlled via [RelayConfig.useTls].
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
        // We're looking for a nonce such that the SHA-256 hash of (payload || nonce)
        // has its top 20 bits = 0. That means hash[0], hash[1], and the top 4 bits
        // of hash[2] are all zero (hash[2] & 0xF0 == 0).
        val targetTopByte = 2    // first 2 bytes must be zero
        val maskThirdByte = 0xF0.toByte()  // top 4 bits of 3rd byte must be zero
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
)

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
