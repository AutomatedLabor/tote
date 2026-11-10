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
 * Full pairing flow (sender side) dialog with QR display + SAS verify.
 *
 * State machine:
 * 1. SHOW_QR — display the pairing QR
 * 2. AWAIT_PEER_PUB — wait for the receiver's X25519 pubkey
 * 3. SHOW_SAS — show the 6-digit + 3-emoji SAS, user confirms
 * 4. DONE — pairing root saved, peer registered, dialog dismissed
 *
 * For the alpha, step 2 is simplified: the user manually pastes the receiver's
 * X25519 pubkey. The full relay-mediated pub exchange is a v1.0.0 TODO.
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
    var peerX25519Pub by remember { mutableStateOf<ByteArray?>(null) }
    var pairingRoot by remember { mutableStateOf<ByteArray?>(null) }

    val identity = remember { app.identityStore.getOrCreateIdentity() }
    val handshake = remember(payload) { PairingHandshake(identity, payload) }

    when (stage) {
        PairingStage.SHOW_QR -> {
            PairingDialog(
                onDismiss = onDismiss,
                onPairingComplete = { _ ->
                    stage = PairingStage.AWAIT_PEER_PUB
                },
            )
        }
        PairingStage.AWAIT_PEER_PUB -> {
            AwaitPeerPubDialog(
                onCancel = onDismiss,
                onPubProvided = { pubHex ->
                    val pub = pubHex.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    if (pub.size == 32) {
                        peerX25519Pub = pub
                        val shared = handshake.computeSharedSecret(pub)
                        val root = handshake.derivePairingRoot(shared, payload.nonceA)
                        pairingRoot = root
                        stage = PairingStage.SHOW_SAS
                    }
                },
            )
        }
        PairingStage.SHOW_SAS -> {
            val root = pairingRoot
            if (root != null) {
                val sasDigits = handshake.computeSas(root)
                val sasEmojis = handshake.computeSasEmoji(root)
                SasVerifyDialog(
                    sasDigits = sasDigits,
                    sasEmojis = sasEmojis,
                    onMatch = {
                        val peer = peerX25519Pub?.let { pub ->
                            PeerDeviceRegistry.Peer(
                                deviceId = payload.senderDeviceId,
                                x25519Pub = pub,
                                displayName = "Device " + payload.senderDeviceId.joinToString("") { "%02x".format(it) }.take(8),
                                pairedAt = System.currentTimeMillis(),
                                lastSeenAt = System.currentTimeMillis(),
                            )
                        }
                        if (peer != null) {
                            PeerDeviceRegistry.getInstance(context).upsert(peer)
                            app.identityStore.savePairingRoot(root)
                            onPairingComplete(root, peer)
                        }
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
    AWAIT_PEER_PUB,
    SHOW_SAS,
    DONE,
}

@Composable
private fun AwaitPeerPubDialog(
    onCancel: () -> Unit,
    onPubProvided: (pubHex: String) -> Unit,
) {
    var pubHex by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Receiver's pubkey") },
        text = {
            Column {
                Text(
                    "Paste the receiver device's X25519 public key (64 hex chars). On the other device, this is shown after it scans your QR.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pubHex,
                    onValueChange = { pubHex = it.filter { c -> c in "0123456789abcdefABCDEF" } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("X25519 pubkey (64 hex chars)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPubProvided(pubHex) },
                enabled = pubHex.length == 64,
            ) { Text("Compute SAS") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
