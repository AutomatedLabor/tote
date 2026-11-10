package com.tote.app.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [CookieRecord].
 *
 * Validates the Chromium SQLite row → CookieRecord conversion and the
 * CookieRecord → Set-Cookie header string round-trip. Critical for cookie
 * fidelity (ADR-016): if we lose attributes during transfer, sessions break.
 */
class CookieRecordTest {

    @Test
    fun `fromChromiumRow with full attributes produces FULL fidelity`() {
        val row = mapOf<String, Any?>(
            "name" to "session_id",
            "value" to "abc123token",
            "host_key" to ".example.com",
            "path" to "/",
            "expires_utc" to 0L,  // session cookie in Chromium format
            "is_secure" to 1L,
            "is_httponly" to 1L,
            "samesite" to 1L,  // LAX
            "priority" to 2L,  // MEDIUM
            "is_partitioned" to 0L,
            "partition_key" to null,
            "source_port" to 443L,
            "source_scheme" to 1L,
            "last_update_utc" to 0L,
            "creation_utc" to 0L,
        )
        val rec = CookieRecord.fromChromiumRow(row)
        assertThat(rec.name).isEqualTo("session_id")
        assertThat(rec.value).isEqualTo("abc123token")
        assertThat(rec.host).isEqualTo(".example.com")
        assertThat(rec.path).isEqualTo("/")
        assertThat(rec.isSecure).isTrue()
        assertThat(rec.isHttpOnly).isTrue()
        assertThat(rec.sameSite).isEqualTo(SameSite.LAX)
        assertThat(rec.priority).isEqualTo(CookiePriority.MEDIUM)
        assertThat(rec.isPartitioned).isFalse()
        assertThat(rec.fidelity).isEqualTo(CookieFidelity.FULL)
        assertThat(rec.provenance).isEqualTo(CookieProvenance.SQLITE_SNAPSHOT)
    }

    @Test
    fun `fromChromiumRow with empty value + non-empty encrypted_value marks VALUE_ONLY_ATTRS_LOST`() {
        val row = mapOf<String, Any?>(
            "name" to "session_id",
            "value" to "",  // OSCrypt path: value column empty
            "encrypted_value" to ByteArray(48) { 0xAB.toByte() },  // OSCrypt blob
            "host_key" to ".example.com",
            "path" to "/",
            "expires_utc" to 0L,
            "is_secure" to 1L,
            "is_httponly" to 0L,
            "samesite" to 0L,
            "priority" to 0L,
            "is_partitioned" to 0L,
            "partition_key" to null,
            "source_port" to 443L,
            "source_scheme" to 1L,
            "last_update_utc" to 0L,
            "creation_utc" to 0L,
        )
        val rec = CookieRecord.fromChromiumRow(row)
        assertThat(rec.value).isEmpty()
        assertThat(rec.host).isEqualTo(".example.com")
        // Fidelity is FULL because we have the value (it's empty but the code treats empty as hasValue=false, then hasEncrypted=true)
        // Wait — let me re-check the code. `hasValue = value.isNotEmpty()`. If value is empty, hasValue is false. Then `hasEncrypted = true`.
        // So fidelity = VALUE_ONLY_ATTRS_LOST.
        assertThat(rec.fidelity).isEqualTo(CookieFidelity.VALUE_ONLY_ATTRS_LOST)
    }

    @Test
    fun `fromChromiumRow with no value + no encrypted_value marks IN_MEMORY_ONLY`() {
        val row = mapOf<String, Any?>(
            "name" to "ephemeral",
            "value" to "",
            "encrypted_value" to ByteArray(0),
            "host_key" to ".example.com",
            "path" to "/",
            "expires_utc" to null,
            "is_secure" to 0L,
            "is_httponly" to 0L,
            "samesite" to 0L,
            "priority" to 0L,
            "is_partitioned" to 0L,
            "partition_key" to null,
            "source_port" to null,
            "source_scheme" to 0L,
            "last_update_utc" to null,
            "creation_utc" to null,
        )
        val rec = CookieRecord.fromChromiumRow(row)
        assertThat(rec.value).isEmpty()
        assertThat(rec.fidelity).isEqualTo(CookieFidelity.IN_MEMORY_ONLY)
    }

    @Test
    fun `toSetCookieString produces a syntactically valid Set-Cookie header`() {
        val rec = CookieRecord(
            name = "session_id",
            value = "abc123token",
            host = ".example.com",
            path = "/",
            expiresUtc = System.currentTimeMillis() + 86_400_000L,  // 1 day
            isSecure = true,
            isHttpOnly = true,
            sameSite = SameSite.LAX,
            isPartitioned = false
        )
        val s = rec.toSetCookieString()
        assertThat(s).contains("session_id=abc123token")
        assertThat(s).contains("Domain=.example.com")
        assertThat(s).contains("Path=/")
        assertThat(s).contains("Secure")
        assertThat(s).contains("HttpOnly")
        assertThat(s).contains("SameSite=Lax")
        assertThat(s).contains("Max-Age=")
    }

    @Test
    fun `toSetCookieString for partitioned cookie includes Partitioned attribute`() {
        val rec = CookieRecord(
            name = "__Host-token",
            value = "v=1",
            host = "example.com",
            path = "/",
            isSecure = true,
            isHttpOnly = true,
            sameSite = SameSite.NONE,
            isPartitioned = true,
            partitionKey = "(https,example.com)"
        )
        val s = rec.toSetCookieString()
        assertThat(s).contains("Partitioned")
        assertThat(s).contains("SameSite=None")
    }

    @Test
    fun `toSetCookieString for session cookie omits Max-Age`() {
        val rec = CookieRecord(
            name = "ephemeral",
            value = "x",
            host = "example.com",
            path = "/",
            expiresUtc = null,  // session cookie
            isSecure = true,
            isHttpOnly = false,
            sameSite = SameSite.UNSPECIFIED,
        )
        val s = rec.toSetCookieString()
        assertThat(s).doesNotContain("Max-Age")
    }

    @Test
    fun `toSetCookieString for UNSPECIFIED sameSite omits SameSite attribute`() {
        val rec = CookieRecord(
            name = "k",
            value = "v",
            host = "example.com",
            path = "/",
            sameSite = SameSite.UNSPECIFIED,
        )
        val s = rec.toSetCookieString()
        assertThat(s).doesNotContain("SameSite")
    }

    @Test
    fun `toSetCookieString for STRICT sameSite emits SameSite=Strict`() {
        val rec = CookieRecord(
            name = "k",
            value = "v",
            host = "example.com",
            path = "/",
            sameSite = SameSite.STRICT,
        )
        val s = rec.toSetCookieString()
        assertThat(s).contains("SameSite=Strict")
    }

    @Test
    fun `fromChromiumRow with missing keys falls back to defaults without throwing`() {
        val row = mapOf<String, Any?>(
            "name" to "k",
            "value" to "v",
        )
        val rec = CookieRecord.fromChromiumRow(row)
        assertThat(rec.name).isEqualTo("k")
        assertThat(rec.value).isEqualTo("v")
        assertThat(rec.host).isEmpty()
        assertThat(rec.path).isEqualTo("/")
        assertThat(rec.isSecure).isFalse()
        assertThat(rec.isHttpOnly).isFalse()
        assertThat(rec.sameSite).isEqualTo(SameSite.UNSPECIFIED)
        assertThat(rec.priority).isEqualTo(CookiePriority.UNSPECIFIED)
        assertThat(rec.isPartitioned).isFalse()
        assertThat(rec.fidelity).isEqualTo(CookieFidelity.FULL)
    }
}
