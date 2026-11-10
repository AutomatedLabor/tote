package com.tote.app.webview

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import com.tote.app.profile.ProfileRegistry
import java.util.LinkedHashMap

/**
 * Manages a pool of WebView instances for tab-switching without reload (per ADR-014).
 *
 * Retains detached WebView instances (removeView from old, addView to new).
 * Never calls destroy() on tab-switch — only on:
 * 1. Profile-switch (destroy all profile tabs, recreate for new profile)
 * 2. onTrimMemory(TRIM_MEMORY_RUNNING_LOW) — LRU eviction
 * 3. BrowserActivity.onDestroy() (belt-and-suspenders)
 *
 * Per ADR-017: WebViews are created with applicationContext (not Activity context)
 * to decouple from Activity lifecycle for memory management.
 */
class WebViewPool(
    private val maxLiveTabs: Int = 5  // Device-tier-dependent, set at runtime
) {
    private val TAG = "WebViewPool"

    // LRU cache of live WebViews, keyed by tab ID
    private val liveWebViews = object : LinkedHashMap<String, WebViewEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, WebViewEntry>): Boolean {
            if (size > maxLiveTabs) {
                val tab = eldest.value
                // Save state before evicting
                tab.checkpoint()
                tab.webView?.destroy()
                Log.i(TAG, "LRU evicted tab: ${eldest.key} (pool size now ${size - 1})")
                return true
            }
            return false
        }
    }

    data class WebViewEntry(
        val tabId: String,
        val profileId: String,
        var webView: WebView?,
        var lastUrl: String = "about:blank",
        var screenshot: Bitmap? = null,
        var scrollY: Int = 0,
        var isPaused: Boolean = false
    ) {
        /**
         * Capture the tab's state for later restoration.
         * Per ADR-012: "paused" = destroyed WebView + serialized state.
         */
        fun checkpoint() {
            webView?.let { wv ->
                lastUrl = wv.url ?: lastUrl
                scrollY = wv.scrollY
                // Screenshot capture would go here (via PixelCopy or View.draw)
            }
        }
    }

    private var appContext: Context? = null
    private var container: ViewGroup? = null

    /**
     * Initialize the pool with the application context and the container
     * where WebViews will be attached.
     */
    fun initialize(context: Context, viewGroup: ViewGroup) {
        appContext = context.applicationContext
        container = viewGroup
    }

    /**
     * Get or create a WebView for a tab.
     * If the tab is in the live pool, return it (instant switch, no reload).
     * If not, create a new WebView (will need to reload the URL).
     */
    fun getOrCreateWebView(tabId: String, profileId: String): WebView? {
        val ctx = appContext ?: return null
        val vg = container ?: return null

        // Check if already live
        liveWebViews[tabId]?.let { entry ->
            entry.isPaused = false
            return entry.webView
        }

        // Create new WebView with applicationContext
        val webView = WebView(ctx)

        // Set profile BEFORE first load (per ADR-012: setProfile is one-shot pre-first-load)
        val profileRegistry = ProfileRegistry.getInstance(ctx)
        if (profileRegistry.isMultiProfileSupported()) {
            try {
                WebViewCompat.setProfile(webView, profileId)
            } catch (e: Exception) {
                Log.w(TAG, "setProfile failed: ${e.message}")
            }
        }

        // Basic WebView configuration
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }

        // Disable debugging in release builds
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(false)
        }

        // Attach to container
        vg.addView(webView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Store in pool
        val entry = WebViewEntry(tabId, profileId, webView)
        liveWebViews[tabId] = entry

        Log.i(TAG, "Created WebView for tab=$tabId profile=$profileId (pool size: ${liveWebViews.size})")
        return webView
    }

    /**
     * Detach a WebView from the container without destroying it.
     * Used when switching tabs — the WebView stays in memory.
     */
    fun detachWebView(tabId: String) {
        val entry = liveWebViews[tabId] ?: return
        entry.webView?.let { wv ->
            entry.lastUrl = wv.url ?: entry.lastUrl
            entry.scrollY = wv.scrollY
            entry.isPaused = true
            wv.onPause()
            container?.removeView(wv)
        }
    }

    /**
     * Re-attach a previously detached WebView.
     */
    fun attachWebView(tabId: String): WebView? {
        val entry = liveWebViews[tabId] ?: return null
        val wv = entry.webView ?: return null
        container?.addView(wv)
        entry.isPaused = false
        wv.onResume()
        return wv
    }

    /**
     * Destroy ALL WebViews. Called by:
     * 1. Profile-switch (destroy all, recreate for new profile)
     * 2. BrowserActivity.onDestroy() (belt-and-suspenders)
     * 3. ViewModel.onCleared()
     */
    fun destroyAll() {
        for ((tabId, entry) in liveWebViews) {
            entry.checkpoint()
            entry.webView?.let { wv ->
                container?.removeView(wv)
                wv.destroy()
            }
        }
        liveWebViews.clear()
        Log.i(TAG, "Destroyed all WebViews (pool cleared)")
    }

    /**
     * Destroy all WebViews for a specific profile (used during profile-switch).
     */
    fun destroyProfile(profileId: String) {
        val toRemove = liveWebViews.entries.filter { it.value.profileId == profileId }
        for ((tabId, entry) in toRemove) {
            entry.checkpoint()
            entry.webView?.let { wv ->
                container?.removeView(wv)
                wv.destroy()
            }
            liveWebViews.remove(tabId)
        }
        Log.i(TAG, "Destroyed ${toRemove.size} WebViews for profile=$profileId")
    }

    /**
     * Get the count of live (non-destroyed) WebViews.
     */
    fun liveCount(): Int = liveWebViews.size

    /**
     * Check if a tab is currently live (in the pool, not destroyed).
     */
    fun isLive(tabId: String): Boolean = liveWebViews.containsKey(tabId)

    /**
     * App went to background. Aggressively evict all paused tabs to free memory.
     *
     * Per ADR-014: the active tab is preserved (so the user comes back to a
     * live page); everything else is destroyed+state-saved so it can be
     * restored on demand.
     *
     * Called by [ToteMemoryObserver] on ProcessLifecycle onStop.
     */
    fun onAppBackground() {
        val active = liveWebViews.entries.firstOrNull { !it.value.isPaused }
        val toEvict = liveWebViews.entries.filter { it.key != active?.key }
        for ((tabId, entry) in toEvict) {
            entry.checkpoint()
            entry.webView?.let { wv ->
                container?.removeView(wv)
                wv.destroy()
            }
            liveWebViews.remove(tabId)
        }
        Log.i(TAG, "onAppBackground: evicted ${toEvict.size} paused tabs, kept ${liveWebViews.size} live")
    }
}
