package com.tote.app.webview

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Picks the device-tier-appropriate `maxLiveTabs` value.
 *
 * Per ADR-014 (truly-live tabs): we want to keep as many WebViews live as
 * memory allows, because destroying+recreating a WebView forces a reload
 * (defeating the "truly-live" promise). But too many live WebViews will OOM
 * the process — especially on low-end devices.
 *
 * Heuristic (from TRULY-LIVE-TABS-DEEP-DIVE.md):
 * ```
 *   memClass = ActivityManager.memoryClass (MB per process)
 *   budget   = max(0, memClass - 200 MB)   // 200MB reserved for non-WebView heap
 *   tabs     = clamp(budget / 90 MB, 2, 10)
 * ```
 * Each paused WebView (with JS frozen via `onPause()`) costs ~50-90MB.
 *
 * Device tier defaults:
 *   - Low    (memClass ≤ 192 MB, isLowRamDevice=true)  → 2 tabs
 *   - Mid    (memClass 193-512 MB)                      → 3 tabs
 *   - High   (memClass ≥ 513 MB)                        → 5 tabs
 *   - largeHeap (declared in manifest) adds +50% headroom → 8 tabs on high-end
 *
 * Per build-learnings: this MUST be measured on real hardware before v1.0.0
 * ship (Spike 0.8 is the gate). The formula above is the planning-time
 * approximation.
 */
object DeviceTierHelper {
    private const val TAG = "DeviceTierHelper"

    /** Memory overhead per live WebView (MB), conservative estimate. */
    private const val MB_PER_WEBVIEW = 90

    /** Reserved heap for non-WebView allocations (MB). */
    private const val MB_RESERVED = 200

    /** Hard floor — never go below 2 tabs regardless of memory pressure. */
    private const val MIN_TABS = 2

    /** Hard ceiling — even on flagship devices we don't go above 10. */
    private const val MAX_TABS = 10

    data class DeviceTier(
        val memClassMb: Int,
        val isLowRamDevice: Boolean,
        val isLargeHeap: Boolean,
        val maxLiveTabs: Int,
        val tierName: String,
    )

    /**
     * Detect the current device's tier.
     */
    fun detect(context: Context): DeviceTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLowRam = am.isLowRamDevice
        // Determine if the app's manifest declares largeHeap=true. ActivityManager
        // doesn't directly expose this; we read it from the ApplicationInfo flags.
        val isLargeHeap = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0
        val memClass = if (isLargeHeap) am.largeMemoryClass else am.memoryClass
        val tabs = computeMaxTabs(memClass, isLowRam, isLargeHeap)
        val tierName = when {
            isLowRam || memClass <= 192 -> "LOW"
            memClass <= 512 -> "MID"
            else -> "HIGH"
        }
        Log.i(TAG, "tier=$tierName memClass=${memClass}MB largeHeap=$isLargeHeap lowRam=$isLowRam → maxLiveTabs=$tabs")
        return DeviceTier(
            memClassMb = memClass,
            isLowRamDevice = isLowRam,
            isLargeHeap = isLargeHeap,
            maxLiveTabs = tabs,
            tierName = tierName,
        )
    }

    /**
     * Compute the recommended maxLiveTabs for the given memory class.
     *
     * Visible for testing.
     */
    fun computeMaxTabs(memClassMb: Int, isLowRam: Boolean, isLargeHeap: Boolean): Int {
        if (isLowRam) return MIN_TABS  // Low-Ram devices get the floor.
        val budget = (memClassMb - MB_RESERVED).coerceAtLeast(0)
        var tabs = budget / MB_PER_WEBVIEW
        if (isLargeHeap) tabs = (tabs * 3) / 2  // +50% on largeHeap devices
        return tabs.coerceIn(MIN_TABS, MAX_TABS)
    }
}
