package com.tote.app.session

import android.util.Log
import android.webkit.CookieManager
import com.tote.app.crypto.CookieRecord

/**
 * Imports a list of [CookieRecord] into the in-process WebView via
 * [CookieManager.setCookie].
 *
 * Per ADR-016: we use `CookieManager.setCookie(url, setCookieString)` per host,
 * where `setCookieString` is reconstructed via [CookieRecord.toSetCookieString].
 * This preserves all attributes that CookieManager understands (Secure,
 * HttpOnly, SameSite, Max-Age, Partitioned, Path, Domain).
 *
 * Limitations:
 * - `priority` is Chromium-specific and not exposed via CookieManager.
 * - `partition_key` is preserved as the Partitioned attribute but the actual
 *   partitionKey topology must match on the destination device.
 * - `source_port` / `source_scheme` are inferred by CookieManager on write.
 *
 * These limitations are documented in CRYPTO-SPEC-FINAL §6 and accepted for v1
 * (the cookie still works for session restoration).
 */
class CookieImporter {
    private val TAG = "CookieImporter"

    /**
     * Import all cookies in [records] into the WebView.
     *
     * @return the count of cookies successfully set.
     */
    fun import(records: List<CookieRecord>): Int {
        val cm = CookieManager.getInstance()
        var count = 0
        for (record in records) {
            try {
                val url = buildUrlForHost(record.host)
                val setCookie = record.toSetCookieString()
                cm.setCookie(url, setCookie)
                count++
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to import cookie '${record.name}' for host '${record.host}': ${t.message}")
            }
        }
        cm.flush()
        Log.i(TAG, "Imported $count/${records.size} cookies (flushed to disk)")
        return count
    }

    /**
     * Build a URL for a host. CookieManager.setCookie expects a full URL.
     *
     * If the record has `isSecure=true`, use `https://`; otherwise `http://`.
     * Strip leading `.` from the host (CookieManager handles domain matching
     * separately).
     */
    private fun buildUrlForHost(host: String, isSecure: Boolean = true): String {
        val cleanHost = host.removePrefix(".")
        return if (isSecure) "https://$cleanHost" else "http://$cleanHost"
    }

    /**
     * Convenience overload that takes a [SessionBlob] directly.
     *
     * Decodes the blob, deserializes the JSON, and imports each cookie.
     */
    fun importFromBlob(
        blob: ByteArray,
        epochKey: ByteArray,
        senderPub: ByteArray,
    ): Int {
        val plaintext = SessionBlob.decode(blob, epochKey, senderPub)
        val json = String(plaintext, Charsets.UTF_8)
        val records = deserializeCookieList(json)
        return import(records)
    }

    /**
     * Deserialize a JSON array of CookieRecords.
     *
     * Uses kotlinx.serialization to round-trip the [CookieRecord] data class.
     */
    private fun deserializeCookieList(json: String): List<CookieRecord> {
        return kotlinx.serialization.json.Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(
                kotlinx.serialization.serializer<CookieRecord>()
            ),
            json
        )
    }
}
