package com.tote.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tote.app.R
import com.tote.app.ToteApplication
import com.tote.app.relay.RelayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that polls the relay for incoming session blobs and
 * applies them to the local WebView.
 *
 * Per ADR-014 (truly-live tabs): the receiving side must apply incoming
 * session blobs in the background so the user sees a live, logged-in tab
 * when they next open the app — not a "refreshing…" placeholder.
 *
 * The service runs continuously while the app is in the foreground or has
 * been moved to the background (within Android's foreground-service limits).
 * It polls the relay every [POLL_INTERVAL_MS] (60s default).
 *
 * Notification: a low-priority ongoing notification with the text "Listening
 * for incoming sessions…". Required for Android 8+ foreground service.
 *
 * Lifecycle:
 * - Started by [ToteApplication] on first composition of the BrowserScreen,
 *   OR by a boot-completed receiver (TODO v1.0.0).
 * - Stopped when the user explicitly disables sync in Settings, OR by
 *   system OOM-killer (rare for foreground services).
 *
 * Relay identity: derived from the local device's Ed25519 pubkey (loaded by
 * [IdentityStore] on ToteApplication).
 */
class SessionSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SessionSyncService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startPolling()
        // START_STICKY: if the system kills the service, restart it (with null intent).
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        Log.i(TAG, "SessionSyncService destroyed")
        super.onDestroy()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            Log.i(TAG, "Polling started (interval=${POLL_INTERVAL_MS}ms)")
            while (true) {
                try {
                    pollOnce()
                } catch (t: Throwable) {
                    Log.e(TAG, "Poll iteration failed", t)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollOnce() {
        val app = application as? ToteApplication ?: return
        val identity = try {
            app.identityStore.getOrCreateIdentity()
        } catch (t: Throwable) {
            Log.w(TAG, "No identity — skipping poll: ${t.message}")
            return
        }
        val epochState = try {
            app.identityStore.getOrCreateEpochState(identity)
        } catch (t: Throwable) {
            Log.w(TAG, "No epoch state — skipping poll: ${t.message}")
            return
        }

        val relayConfig = RelayConfig(
            baseUrl = "https://relay.tote.app/v1",
            baseUrlWs = "wss://relay.tote.app/v1",
            localDeviceId = identity.ed25519Pub,
        )
        val service = SessionTransferService(applicationContext, relayConfig, identity, epochState)
        val registry = PeerDeviceRegistry.getInstance(applicationContext)

        val inbox = service.pollInbox()
        if (inbox.isEmpty()) return
        Log.i(TAG, "Inbox: ${inbox.size} envelope(s)")

        for (entry in inbox) {
            val blob = service.downloadEnvelope(entry.id) ?: continue
            // Look up sender pubkey from registry. If the relay exposes
            // sender_id, use that; otherwise fall back to checking every
            // known peer (last-write-wins for the alpha).
            val senderPub = entry.senderId?.let { hexId ->
                registry.find(hexId)?.deviceId
            } ?: ByteArray(32)
            val count = service.applyIncoming(blob, senderPub)
            if (count > 0) {
                service.ackEnvelope(entry.id)
                Log.i(TAG, "Applied envelope ${entry.id}: $count cookies")
                notifyIncomingApplied(count)
            }
        }
    }

    private fun notifyIncomingApplied(cookieCount: Int) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.sync_incoming_applied, cookieCount))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        mgr.notify(NOTIFICATION_INCOMING_ID + (cookieCount.hashCode() and 0xFFF), notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)

        // Ongoing sync channel (low priority — silent)
        val syncChannel = NotificationChannel(
            CHANNEL_SYNC,
            getString(R.string.sync_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.sync_service_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(syncChannel)

        // Incoming-session channel (default priority — visible)
        val incomingChannel = NotificationChannel(
            CHANNEL_INCOMING,
            "Incoming sessions",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies you when another Tote device sends you a session"
        }
        mgr.createNotificationChannel(incomingChannel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.sync_service_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "SessionSyncService"
        private const val CHANNEL_SYNC = "tote_sync"
        private const val CHANNEL_INCOMING = "tote_incoming"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INCOMING_ID = 2000
        private const val POLL_INTERVAL_MS = 60_000L  // 60s

        /**
         * Convenience for starting the service from the UI.
         */
        fun start(context: Context) {
            val intent = Intent(context, SessionSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Convenience for stopping the service from the UI.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, SessionSyncService::class.java))
        }
    }
}
