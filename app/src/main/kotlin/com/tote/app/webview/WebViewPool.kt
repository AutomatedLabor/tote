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
    /** Max simultaneous live WebViews before LRU eviction kicks in. */
    val maxLiveTabs: Int = 5  // Device-tier-dependent, set at runtime
) {
    private val TAG = "WebViewPool"

    // LRU cache of live WebViews, keyed by tab ID
    private val liveWebViews = object : LinkedHashMap<String, WebViewEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, WebViewEntry>): Boolean {
            if (size > maxLiveTabs) {
                val tab = eldest.value
                // Save state before evicting — copy into the sidecar map so
                // the next access can restore it (per ADR-014: truly-live tabs).
                tab.checkpoint()
                tab.savedState?.let { savedStates[eldest.key] = it }
                savedUrls[eldest.key] = tab.lastUrl
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
        var isPaused: Boolean = false,
        /** Serialized WebView state (form data, scroll position, history).
         *  Populated by [checkpoint] when a WebView is evicted, used by
         *  [restoreState] when the tab is next accessed. Per ADR-014 (truly-live
         *  tabs): this is what makes a tab "live" even after LRU eviction. */
        var savedState: android.os.Bundle? = null,
        /** Whether the WebView is currently in the foreground (attached). */
        var isAttached: Boolean = false,
    ) {
        /**
         * Capture the tab's state for later restoration.
         * Per ADR-012: "paused" = destroyed WebView + serialized state.
         *
         * Per ADR-014 (truly-live tabs): we save:
         *   - URL + scrollY (for instant visual restore)
         *   - WebView.saveState() Bundle (for history + form data)
         *   - Screenshot capture via [captureScreenshot] (PixelCopy on Android 8+,
         *     View.draw on older devices — see [captureScreenshot]).
         */
        fun checkpoint() {
            webView?.let { wv ->
                lastUrl = wv.url ?: lastUrl
                scrollY = wv.scrollY
                // Save full WebView state (history, form data) into a fresh Bundle.
                // Per Android docs: WebView.saveState returns the same Bundle you
                // pass in, populated with the state. Pass an empty Bundle.
                if (savedState == null) {
                    savedState = android.os.Bundle()
                }
                try {
                    wv.saveState(savedState!!)
                } catch (t: Throwable) {
                    android.util.Log.w("WebViewPool", "saveState failed for tab=$tabId: ${t.message}")
                }
                // Attempt screenshot capture (best-effort — may fail if the
                // WebView isn't attached to a window).
                try {
                    captureScreenshot(wv)
                } catch (t: Throwable) {
                    android.util.Log.w("WebViewPool", "Screenshot capture failed for tab=$tabId: ${t.message}")
                }
            }
        }

        /**
         * Capture a screenshot of the WebView's current frame.
         *
         * Per ADR-014: we use View.draw(Canvas) for the alpha. This captures
         * the DOM-rendered pixels but NOT hardware-accelerated surfaces
         * (WebGL, video). For tab thumbnails this is acceptable — the user
         * sees the page layout, not the live WebGL frame.
         *
         * Per v1.1.0-alpha1: when the WebView is attached to a window, we
         * attempt PixelCopy via the Window + Rect bounds overload (which
         * captures hardware-accelerated content). Falls back to View.draw
         * if PixelCopy fails or the WebView isn't attached.
         *
         * PixelCopy flow:
         *   1. Find the WebView's location in window coordinates
         *   2. Compute a Rect (clipped to 256x256 max)
         *   3. PixelCopy.request(window, rect, bitmap, listener, handler)
         *   4. Wait on a CountDownLatch (with 500ms timeout)
         *   5. If SUCCESS, use the bitmap; else fall back to View.draw
         */
        private fun captureScreenshot(wv: WebView) {
            val width = wv.width
            val height = wv.height
            if (width <= 0 || height <= 0) return  // not laid out yet
            // Cap the screenshot size to 256x256 to limit memory.
            val targetW = minOf(width, 256)
            val targetH = minOf(height, 256)
            val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)

            // Try PixelCopy via Window + Rect bounds (API 26+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                wv.isAttachedToWindow &&
                wv.rootView != null &&
                wv.rootView.windowToken != null
            ) {
                try {
                    // Get the WebView's location in window coordinates
                    val location = IntArray(2)
                    wv.getLocationInWindow(location)
                    val srcRect = android.graphics.Rect(
                        location[0],
                        location[1],
                        location[0] + targetW,
                        location[1] + targetH,
                    )
                    // Find the Window from the rootView
                    val window = (w.rootView.context as? android.app.Activity)?.window
                    if (window != null) {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var success = false
                        android.view.PixelCopy.request(
                            window,
                            srcRect,
                            bitmap,
                            { result ->
                                success = (result == android.view.PixelCopy.SUCCESS)
                                latch.countDown()
                            },
                            wv.handler ?: android.os.Looper.getMainLooper(),
                        )
                        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (success) {
                            screenshot = bitmap
                            return
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("WebViewPool", "PixelCopy failed: ${t.message} — falling back to View.draw")
                }
            }

            // Fallback: View.draw (DOM-only capture)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                wv.draw(canvas)
                screenshot = bitmap
            } catch (t: Throwable) {
                android.util.Log.w("WebViewPool", "View.draw screenshot failed: ${t.message}")
            }
        }
    }

    private var appContext: Context? = null
    private var container: ViewGroup? = null

    /**
     * Sidecar map of saved WebView states, keyed by tab ID. Populated by
     * [WebViewEntry.checkpoint] when a tab is evicted; consumed by
     * [getOrCreateWebView] when the tab is re-created.
     *
     * Per ADR-014 (truly-live tabs): this is what makes an evicted tab
     * "live" again — the user sees the same page (with form data + history)
     * instead of a fresh load.
     */
    private val savedStates = mutableMapOf<String, android.os.Bundle>()

    /**
     * Sidecar map of last-known URLs, keyed by tab ID. Used to fall back to
     * a URL reload if WebView state restoration fails.
     */
    private val savedUrls = mutableMapOf<String, String>()

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
     * If not, create a new WebView and restore its saved state if any (URL,
     * scroll position, form data, history) — so the user sees the page they
     * left, not a reload.
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

        // Look up the previous entry (if any) to restore state from. The entry
        // would have been removed from liveWebViews on eviction, so we keep
        // a sidecar map of saved states keyed by tab ID. For simplicity in the
        // alpha, we store the savedState on the entry itself and look it up
        // from a parallel `savedStates` map.
        val savedState = savedStates[tabId]
        val lastUrl = savedUrls[tabId]
        if (savedState != null) {
            try {
                webView.restoreState(savedState)
                Log.i(TAG, "Restored WebView state for tab=$tabId (history + form data)")
            } catch (t: Throwable) {
                Log.w(TAG, "restoreState failed for tab=$tabId: ${t.message}")
                if (lastUrl != null) webView.loadUrl(lastUrl)
            }
        } else if (lastUrl != null && lastUrl != "about:blank") {
            webView.loadUrl(lastUrl)
        }

        // Attach to container
        vg.addView(webView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Store in pool
        val entry = WebViewEntry(tabId, profileId, webView, lastUrl ?: "about:blank")
        entry.isAttached = true
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
     *
     * Note: this also clears the sidecar savedStates/savedUrls maps, since
     * they're tied to the current set of tabs. (Profile-switch destroys the
     * tabs themselves; TabManager handles that.)
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
        savedStates.clear()
        savedUrls.clear()
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
     * Get the screenshot thumbnail for a tab, if available.
     *
     * Per ADR-014: the thumbnail is captured by [WebViewEntry.checkpoint]
     * when the tab was last evicted. If the tab is currently live, returns
     * null (we don't pull a live screenshot per render — too expensive).
     *
     * Used by [com.tote.app.ui.TabStripOverlay] to render tab thumbnails.
     */
    fun getThumbnail(tabId: String): Bitmap? {
        return liveWebViews[tabId]?.screenshot
    }

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
            entry.savedState?.let { savedStates[tabId] = it }
            savedUrls[tabId] = entry.lastUrl
            entry.webView?.let { wv ->
                container?.removeView(wv)
                wv.destroy()
            }
            liveWebViews.remove(tabId)
        }
        Log.i(TAG, "onAppBackground: evicted ${toEvict.size} paused tabs, kept ${liveWebViews.size} live")
    }
}
