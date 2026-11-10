package com.tote.app.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit test for [DeviceTierHelper].
 *
 * Tests the pure compute logic (no Android framework calls). The Android
 * framework integration (ActivityManager.memoryClass etc.) is tested on
 * real devices via Spike 0.8.
 */
class DeviceTierHelperTest {

    @Test
    fun `LOW-RAM device always returns MIN_TABS (2) regardless of memClass`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 256, isLowRam = true, isLargeHeap = false)
        assertThat(tabs).isEqualTo(2)
    }

    @Test
    fun `LOW-RAM device with largeHeap still returns MIN_TABS`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 128, isLowRam = true, isLargeHeap = true)
        assertThat(tabs).isEqualTo(2)
    }

    @Test
    fun `memClass of 192 MB returns MIN_TABS (no budget after reservation)`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 192, isLowRam = false, isLargeHeap = false)
        assertThat(tabs).isEqualTo(2)
    }

    @Test
    fun `memClass of 256 MB without largeHeap returns 2 tabs (just barely above budget)`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 256, isLowRam = false, isLargeHeap = false)
        assertThat(tabs).isEqualTo(2)
    }

    @Test
    fun `memClass of 512 MB without largeHeap returns 3 tabs (MID tier)`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 512, isLowRam = false, isLargeHeap = false)
        assertThat(tabs).isEqualTo(3)
    }

    @Test
    fun `memClass of 512 MB with largeHeap returns 4 tabs (largeHeap +50% headroom)`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 512, isLowRam = false, isLargeHeap = true)
        assertThat(tabs).isEqualTo(4)
    }

    @Test
    fun `memClass of 1024 MB without largeHeap returns 9 tabs (HIGH tier)`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 1024, isLowRam = false, isLargeHeap = false)
        assertThat(tabs).isEqualTo(9)
    }

    @Test
    fun `memClass of 1024 MB with largeHeap returns MAX_TABS (10) — capped`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 1024, isLowRam = false, isLargeHeap = true)
        assertThat(tabs).isEqualTo(10)
    }

    @Test
    fun `memClass of 8192 MB still capped at MAX_TABS (10)`() {
        val tabs = DeviceTierHelper.computeMaxTabs(memClassMb = 8192, isLowRam = false, isLargeHeap = true)
        assertThat(tabs).isEqualTo(10)
    }

    @Test
    fun `MIN_TABS and MAX_TABS bounds hold across all sensible inputs`() {
        // Sanity sweep: for any memClass from 64 to 8192, with/without largeHeap,
        // the result must be in [MIN_TABS, MAX_TABS].
        for (memClass in listOf(64, 128, 192, 256, 384, 512, 768, 1024, 2048, 4096, 8192)) {
            for (largeHeap in listOf(true, false)) {
                for (lowRam in listOf(true, false)) {
                    val tabs = DeviceTierHelper.computeMaxTabs(memClass, lowRam, largeHeap)
                    assertThat(tabs).isAtLeast(2)
                    assertThat(tabs).isAtMost(10)
                }
            }
        }
    }
}
