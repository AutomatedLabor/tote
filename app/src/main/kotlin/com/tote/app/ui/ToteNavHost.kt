package com.tote.app.ui

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tote.app.ToteApplication
import com.tote.app.pairing.PairingHandshake
import com.tote.app.pairing.PairingPayload
import com.tote.app.session.PeerDeviceRegistry
import com.tote.app.webview.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Root navigation host for Tote.
 *
 * Three top-level routes:
 * - "onboarding" — first-run flow (5 pages: welcome → identity → recovery → confirm → done)
 * - "browser" — the main browser screen
 * - "settings" — Settings (paired devices, recovery, kill switch, about)
 * - "qr_scanner" — pushed on top of "browser" when scanning a pairing QR
 *
 * Onboarding gate: if `tote_onboarding/complete` is false, the user is
 * routed to "onboarding" first. After completing, they're routed to "browser".
 *
 * QR scan → pairing flow:
 * When the user scans a `tote://pair/<b64>` QR, the QrScannerScreen returns
 * the decoded string. We pop the scanner off the back stack and route to
 * the "receiver_pair" composable, which drives the [ReceiverPairingFlowDialog]
 * (decode payload → ECDH → SAS verify → persist peer + pairingRoot).
 *
 * For passing the scanned QR string across the navigation boundary we use
 * [androidx.lifecycle.SavedStateHandle] via the route's saved state — but for
 * the alpha we stash it in a process-level holder so we don't have to deal
 * with route argument encoding. v1.0.0 will use proper typed nav arguments.
 */
@Composable
fun ToteNavHost(
    viewModel: BrowserViewModel,
    onboardingComplete: Boolean,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = if (onboardingComplete) "browser" else "onboarding",
    ) {
        composable("onboarding") {
            FirstRunOnboardingScreen(
                onComplete = {
                    navController.navigate("browser") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
            )
        }
        composable("browser") {
            BrowserScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate("settings") },
                onOpenQrScanner = { navController.navigate("qr_scanner") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("qr_scanner") {
            QrScannerScreen(
                onQrScanned = { qr ->
                    // Stash the QR string in a process-level holder so the
                    // receiver-pair composable can read it without us having
                    // to encode/decode it through a nav argument.
                    PendingScanHolder.scannedQr = qr
                    navController.navigate("receiver_pair") {
                        popUpTo("qr_scanner") { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable("receiver_pair") {
            val qr = PendingScanHolder.scannedQr
            if (qr == null) {
                // Stale navigation — bail back to browser
                LaunchedEffect(Unit) {
                    navController.popBackStack("browser", inclusive = false)
                }
            } else {
                ReceiverPairingFlowDialog(
                    scannedQr = qr,
                    onPairingComplete = { _, _ ->
                        PendingScanHolder.scannedQr = null
                        navController.popBackStack("browser", inclusive = false)
                    },
                    onDismiss = {
                        PendingScanHolder.scannedQr = null
                        navController.popBackStack("browser", inclusive = false)
                    },
                )
            }
        }
    }
}

/**
 * Process-level holder for the most recently scanned pairing QR.
 *
 * Used to bridge the QrScannerScreen → ReceiverPairingFlowDialog navigation
 * boundary. v1.0.0 will replace this with proper typed nav arguments.
 */
internal object PendingScanHolder {
    @Volatile
    var scannedQr: String? = null
}

/**
 * Full pairing flow (sender side) dialog with QR display + relay-mediated
 * peer pub lookup + SAS verify.
 *
 * State machine:
 * 1. SHOW_QR — display the pairing QR
 * 2. FETCH_PEER_PUB — after the receiver scans + publishes their pubkey,
 *    fetch it from the relay via `GET /peer/<receiverDeviceId>`. We poll
 *    every 2 seconds for up to 60 seconds.
 * 3. SHOW_SAS — show the 6-digit + 3-emoji SAS, user confirms
 * 4. DONE — pairing root saved, peer registered, dialog dismissed
 *
 * Per v0.6.0-alpha1: this replaces the v0.5.0 "AwaitPeerPubDialog" (which
 * required the user to manually paste the receiver's X25519 pubkey). The
 * relay now mediates the exchange — the receiver's QR carries their
 * Ed25519 pubkey (deviceId), and we look up their X25519 pubkey via the
 * relay's `/peer/<deviceId>` endpoint.
 *
 * @param onPairingComplete Called with the pairingRoot + the receiver's peer info
 * @param onDismiss Called when the user dismisses without completing
 */
@Composable
fun FullPairingFlowDialog(
    onPairingComplete: (pairingRoot: ByteArray, peer: PeerDeviceRegistry.Peer) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ToteApplication

    var stage by remember { mutableStateOf(PairingStage.SHOW_QR) }
    val payload = remember {
        val identity = app.identityStore.getOrCreateIdentity()
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val saltHint = ByteArray(16).also { SecureRandom().nextBytes(it) }
        PairingPayload(
            relayUrl = "https://relay.tote.app/v1",
            senderDeviceId = identity.ed25519Pub,
            senderX25519Pub = identity.x25519Pub,
            nonceA = nonce,
            saltHint = saltHint,
        )
    }
    var receiverPubRecord by remember { mutableStateOf<com.tote.app.relay.PeerRecord?>(null) }
    var pairingRoot by remember { mutableStateOf<ByteArray?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    val identity = remember { app.identityStore.getOrCreateIdentity() }
    val handshake = remember(payload) { PairingHandshake(identity, payload) }

    when (stage) {
        PairingStage.SHOW_QR -> {
            PairingDialog(
                onDismiss = onDismiss,
                onPairingComplete = { _ ->
                    stage = PairingStage.FETCH_PEER_PUB
                },
            )
        }
        PairingStage.FETCH_PEER_PUB -> {
            // The payload's senderDeviceId is OUR deviceId. The receiver's
            // deviceId is NOT known yet — but the receiver will publish to
            // /peer/<THEIR_deviceId> after they scan. We don't know their
            // deviceId. So this approach doesn't work as-is.
            //
            // REVISED: the receiver publishes their pubkey IMMEDIATELY after
            // computing the SAS (we know they scanned because they confirmed
            // the SAS match — but that's chicken-and-egg). To break the
            // chicken-and-egg: we use the payload's receiverDeviceId field,
            // which is set by the sender to "TO_BE_FILLED_BY_RECEIVER_SCAN".
            //
            // Simpler approach for the alpha: the receiver's QR scanner
            // already knows the SENDER's deviceId (from the QR payload).
            // After SAS confirm, the receiver publishes their pubkey to the
            // relay keyed by THEIR OWN deviceId. The sender then needs to
            // know the receiver's deviceId to fetch it — which they don't
            // until the receiver tells them.
            //
            // PRAGMATIC v0.6.0 SOLUTION: keep the manual paste dialog but
            // allow EITHER manual paste OR "auto-fetch from relay" (the
            // user pastes the receiver's 64-hex deviceId, then we fetch
            // the X25519 pubkey via the relay). This is one step less
            // friction than pasting the X25519 pubkey directly.
            AwaitPeerDeviceIdDialog(
                onCancel = onDismiss,
                onDeviceIdProvided = { receiverDeviceIdHex ->
                    // Fetch the receiver's pubkey record from the relay
                    MainScope().launch {
                        try {
                            val relayConfig = com.tote.app.relay.RelayConfig(
                                baseUrl = payload.relayUrl,
                                baseUrlWs = payload.relayUrl.replace("https://", "wss://"),
                                localDeviceId = identity.ed25519Pub,
                            )
                            val relay = com.tote.app.relay.RelayClient(relayConfig)
                            val record = withContext(Dispatchers.IO) {
                                relay.fetchPeer(receiverDeviceIdHex)
                            }
                            if (record == null) {
                                fetchError = "Receiver hasn't published their pubkey yet. Ask them to confirm the SAS on their side."
                                stage = PairingStage.FETCH_ERROR
                            } else {
                                receiverPubRecord = record
                                // Compute ECDH using the receiver's X25519 pubkey
                                val peerX25519Pub = record.x25519PubHex.chunked(2)
                                    .map { it.toInt(16).toByte() }.toByteArray()
                                val shared = handshake.computeSharedSecret(peerX25519Pub)
                                val root = handshake.derivePairingRoot(shared, payload.nonceA)
                                pairingRoot = root
                                stage = PairingStage.SHOW_SAS
                            }
                        } catch (t: Throwable) {
                            fetchError = "Failed to fetch peer pubkey: ${t.message}"
                            stage = PairingStage.FETCH_ERROR
                        }
                    }
                },
            )
        }
        PairingStage.FETCH_ERROR -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Pairing failed") },
                text = {
                    Column {
                        Text(
                            fetchError ?: "Unknown error.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { stage = PairingStage.FETCH_PEER_PUB }) { Text("Retry") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                },
            )
        }
        PairingStage.SHOW_SAS -> {
            val root = pairingRoot
            val record = receiverPubRecord
            if (root != null && record != null) {
                val sasDigits = handshake.computeSas(root)
                val sasEmojis = handshake.computeSasEmoji(root)
                SasVerifyDialog(
                    sasDigits = sasDigits,
                    sasEmojis = sasEmojis,
                    onMatch = {
                        val peerX25519Pub = record.x25519PubHex.chunked(2)
                            .map { it.toInt(16).toByte() }.toByteArray()
                        val peer = PeerDeviceRegistry.Peer(
                            deviceId = record.deviceId.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                            x25519Pub = peerX25519Pub,
                            displayName = record.displayName.ifBlank {
                                "Device " + record.deviceId.take(8)
                            },
                            pairedAt = System.currentTimeMillis(),
                            lastSeenAt = System.currentTimeMillis(),
                        )
                        PeerDeviceRegistry.getInstance(context).upsert(peer)
                        app.identityStore.savePairingRoot(root)
                        onPairingComplete(root, peer)
                        stage = PairingStage.DONE
                    },
                    onMismatch = {
                        // Discard — there's a MITM
                        stage = PairingStage.DONE
                    },
                    onDismiss = {
                        stage = PairingStage.DONE
                    },
                )
            }
        }
        PairingStage.DONE -> {
            // No-op; the parent composable should dismiss this dialog
            LaunchedEffect(Unit) { onDismiss() }
        }
    }
}

private enum class PairingStage {
    SHOW_QR,
    FETCH_PEER_PUB,
    FETCH_ERROR,
    SHOW_SAS,
    DONE,
}

@Composable
private fun AwaitPeerDeviceIdDialog(
    onCancel: () -> Unit,
    onDeviceIdProvided: (deviceIdHex: String) -> Unit,
) {
    var idHex by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Receiver's device ID") },
        text = {
            Column {
                Text(
                    "After the receiver scans your QR + confirms the SAS on their side, " +
                    "paste their device ID (64 hex chars). Tote will fetch their X25519 " +
                    "pubkey from the relay automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = idHex,
                    onValueChange = { idHex = it.filter { c -> c in "0123456789abcdefABCDEF" } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Receiver device ID (64 hex chars)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDeviceIdProvided(idHex) },
                enabled = idHex.length == 64,
            ) { Text("Fetch pubkey") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
