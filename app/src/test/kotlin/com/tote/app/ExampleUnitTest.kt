package com.tote.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Trivial unit test so the JUnit report step in CI has something to chew on.
 *
 * Uses Google Truth (`assertThat(...).isEqualTo(...)`); the Truth dependency
 * is declared in gradle/libs.versions.toml and wired as `testImplementation`
 * in app/build.gradle.kts.
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertThat(1 + 1).isEqualTo(2)
    }
}
