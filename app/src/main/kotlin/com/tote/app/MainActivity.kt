package com.tote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tote.app.ui.ToteNavHost
import com.tote.app.ui.ToteTheme
import com.tote.app.webview.BrowserViewModel
import com.tote.app.webview.ToteMemoryObserver
import com.tote.app.session.SessionSyncService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel: BrowserViewModel by viewModels()
        val app = application as ToteApplication

        // Wire the app-wide ProfileRegistry into the ViewModel.
        viewModel.profileRegistry = app.profileRegistry
        viewModel.appContext = applicationContext

        // Register this session's WebViewPool with the memory observer so
        // ProcessLifecycle gets routed to its onAppBackground().
        ToteMemoryObserver.currentPool = viewModel.webViewPool

        setContent {
            ToteTheme {
                val context = LocalContext.current
                val onboardingComplete = remember {
                    context.getSharedPreferences("tote_onboarding", 0)
                        .getBoolean("complete", false)
                }

                ToteNavHost(
                    viewModel = viewModel,
                    onboardingComplete = onboardingComplete,
                )
            }
        }

        // Start the foreground sync service so incoming sessions are applied
        // even while the user is browsing. Per ADR-014.
        try {
            SessionSyncService.start(applicationContext)
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "Failed to start SessionSyncService: ${t.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Belt-and-suspenders: destroy WebViewPool explicitly.
        // per ADR-017 (ViewModel.onCleared is unreliable on some OEM ROMs)
        val viewModel by viewModels<BrowserViewModel>()
        viewModel.webViewPool.destroyAll()
        ToteMemoryObserver.currentPool = null
    }
}
