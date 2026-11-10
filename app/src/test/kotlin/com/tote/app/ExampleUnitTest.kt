package com.tote.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Trivial smoke test so the JUnit report step in CI has something to chew on
 * even before the crypto-module tests are wired up.
 *
 * NB: Uses JUnit's [assertEquals] instead of Google Truth's [assertThat]
 * because the assertThat overloads for primitive Int are ambiguous under
 * Kotlin when both JUnit + Truth are on the test classpath. Truth assertions
 * live alongside this in the crypto-module test files (no ambiguity there
 * because we always pass String or ByteArray, which are unambiguous).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(2, 1 + 1)
    }
}
