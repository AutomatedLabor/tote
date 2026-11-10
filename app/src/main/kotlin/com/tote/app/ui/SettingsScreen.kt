package com.tote.app.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tote.app.ToteApplication
import com.tote.app.recovery.KillSwitch
import com.tote.app.session.PeerDeviceRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen — exposes all Tote configuration surfaces:
 * - Paired devices (list + remove)
 * - Recovery code (view + rotate)
 * - Kill switch (nuke + duress)
 * - About (version, source, license)
 *
 * Accessed from the BrowserScreen top bar via the Settings IconButton.
 *
 * Per ADR-015: every destructive action has a confirmation dialog with the
 * full consequence described in plain language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var peers by remember { mutableStateOf(PeerDeviceRegistry.getInstance(context).list()) }
    var showRecovery by remember { mutableStateOf(false) }
    var showKillSwitch by remember { mutableStateOf(false) }
    var showRemovePeer by remember { mutableStateOf<PeerDeviceRegistry.Peer?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Refresh peers list when refreshTrigger changes
    LaunchedEffect(refreshTrigger) {
        peers = PeerDeviceRegistry.getInstance(context).list()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Paired devices section
            item {
                SettingsSection(title = "Paired devices") {
                    if (peers.isEmpty()) {
                        Text(
                            "No paired devices yet. Tap the + button on the browser screen to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        peers.forEach { peer ->
                            PeerRow(
                                peer = peer,
                                onRemove = { showRemovePeer = peer },
                            )
                            if (peer != peers.last()) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // Recovery section
            item {
                SettingsSection(title = "Recovery code") {
                    ListItem(
                        headlineContent = { Text("View recovery code") },
                        supportingContent = { Text("12-word BIP-39 mnemonic. Required to recover sessions if you lose this device.") },
                        leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable { showRecovery = true },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Rotate recovery code") },
                        supportingContent = { Text("Generates a new code. Triggers an epoch advance on all paired devices. Recommended every 90 days.") },
                        leadingContent = { Icon(Icons.Default.Autorenew, contentDescription = null) },
                        modifier = Modifier.clickable {
                            // TODO: confirmation dialog before rotate
                        },
                    )
                }
            }

            // Kill switch section
            item {
                SettingsSection(title = "Kill switch") {
                    ListItem(
                        headlineContent = { Text("Nuke all data") },
                        supportingContent = { Text("Destroys all keys, profiles, cookies after a 24h cooldown. Cancellable within the window.") },
                        leadingContent = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { showKillSwitch = true },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Set duress code") },
                        supportingContent = { Text("Set a code (≥8 chars) that, if entered at the lock, destroys sensitive profiles. NOT cancellable.") },
                        leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.clickable { showKillSwitch = true },
                    )
                }
            }

            // About section
            item {
                SettingsSection(title = "About") {
                    ListItem(
                        headlineContent = { Text("Tote") },
                        supportingContent = { Text("v0.4.0-alpha • MIT License") },
                        leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Source code") },
                        supportingContent = { Text("github.com/AutomatedLabor/tote") },
                        leadingContent = { Icon(Icons.Default.Code, contentDescription = null) },
                    )
                }
            }
        }
    }

    if (showRecovery) {
        RecoveryCodeDialog(onDismiss = { showRecovery = false })
    }
    if (showKillSwitch) {
        KillSwitchDialog(onDismiss = { showKillSwitch = false })
    }
    if (showRemovePeer != null) {
        AlertDialog(
            onDismissRequest = { showRemovePeer = null },
            title = { Text("Remove paired device?") },
            text = {
                Text("This device will no longer be able to receive session transfers from you. They'll need to re-pair.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemovePeer?.let { peer ->
                        PeerDeviceRegistry.getInstance(context).remove(peer.deviceId)
                        refreshTrigger++
                    }
                    showRemovePeer = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePeer = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, start = 16.dp),
            fontWeight = FontWeight.SemiBold,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun PeerRow(
    peer: PeerDeviceRegistry.Peer,
    onRemove: () -> Unit,
) {
    val displayName = peer.displayName.ifBlank { "Unnamed device" }
    val fingerprint = peer.deviceId.joinToString("") { "%02x".format(it) }.take(12)
    val pairedAtStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(peer.pairedAt))

    ListItem(
        headlineContent = { Text(displayName) },
        supportingContent = { Text("Fingerprint: $fingerprint… • Paired: $pairedAtStr") },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove paired device")
            }
        },
    )
}

@Composable
private fun RecoveryCodeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ToteApplication

    // Load or generate the recovery code (memoized so it doesn't change on recompose).
    val recovery = remember {
        val identity = app.identityStore.getOrCreateIdentity()
        // The recovery code is derived from the device seed (so it's deterministic
        // and survives app restarts). Per CRYPTO-SPEC-FINAL §5 the recovery code
        // is shown ONCE at onboarding — for the alpha we re-derive on demand.
        val entropy = identity.seed.copyOf(16)  // 128 bits for 12-word mnemonic
        val mnemonic = com.tote.app.crypto.BIP39.entropyToMnemonic(entropy)
        mnemonic to entropy
    }
    val mnemonic = recovery.first

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your recovery code") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Write down these 12 words on paper. Store offline. They are the ONLY way to recover your paired sessions if you lose all your devices.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                mnemonic.forEachIndexed { idx, word ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = "${idx + 1}.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = Modifier.width(36.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "⚠ Never share these words. Anyone with them can claim your paired sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun KillSwitchDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ToteApplication
    val killSwitch = remember { KillSwitch() }
    var mode by remember { mutableStateOf(killSwitch.currentMode) }
    var timeRemaining by remember { mutableStateOf(0L) }
    var duressCodeInput by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    // Tick the countdown every second while in NUKE_PENDING
    LaunchedEffect(mode) {
        while (mode == KillSwitch.Mode.NUKE_PENDING) {
            timeRemaining = killSwitch.nukeTimeRemaining()
            if (killSwitch.checkNukeExpired()) {
                mode = killSwitch.currentMode
                statusMsg = "NUKE EXECUTED — all keys destroyed"
                break
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kill switch") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // NUKE section
                Text("Nuke mode (24h cooldown, cancellable):",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                when (mode) {
                    KillSwitch.Mode.IDLE -> {
                        Text(
                            "Destroys all keys, profiles, cookies after a 24-hour countdown. You can cancel within the window.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    KillSwitch.Mode.NUKE_PENDING -> {
                        val hrs = timeRemaining / 3_600_000
                        val mins = (timeRemaining % 3_600_000) / 60_000
                        val secs = (timeRemaining % 60_000) / 1000
                        Text(
                            "Countdown: %02d:%02d:%02d".format(hrs, mins, secs),
                            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    KillSwitch.Mode.NUKE_EXECUTED -> {
                        Text(
                            "Already executed. All data destroyed. Restart the app to re-onboard.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    KillSwitch.Mode.DURESS_ACTIVATED -> {
                        Text(
                            "Duress mode active. Sensitive profiles destroyed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Duress section
                Text("Duress code (NOT cancellable):",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "If entered at the lock screen, immediately destroys sensitive profiles and switches to decoy. There is no way to undo this.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = duressCodeInput,
                    onValueChange = { duressCodeInput = it },
                    label = { Text("New duress code (≥8 chars)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = duressCodeInput.isNotEmpty() && duressCodeInput.length < 8,
                    supportingText = {
                        if (duressCodeInput.isNotEmpty() && duressCodeInput.length < 8) {
                            Text("Must be at least 8 characters")
                        }
                    },
                )

                if (statusMsg != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        statusMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                // Nuke trigger / cancel button
                when (mode) {
                    KillSwitch.Mode.IDLE -> {
                        TextButton(onClick = {
                            killSwitch.triggerNuke()
                            mode = killSwitch.currentMode
                            statusMsg = "Nuke countdown started — 24h remaining"
                        }) { Text("Trigger nuke") }
                    }
                    KillSwitch.Mode.NUKE_PENDING -> {
                        TextButton(onClick = {
                            killSwitch.cancelNuke()
                            mode = killSwitch.currentMode
                            statusMsg = "Nuke cancelled — no data destroyed"
                        }) { Text("Cancel nuke") }
                    }
                    else -> {}
                }
                // Save duress code button
                TextButton(
                    onClick = {
                        if (duressCodeInput.length >= 8) {
                            // Persist the duress code (TODO: AndroidKeyStore-wrap)
                            context.getSharedPreferences("tote_kill_switch", 0)
                                .edit()
                                .putString("duress_code", duressCodeInput)
                                .apply()
                            statusMsg = "Duress code saved (length ${duressCodeInput.length})"
                            duressCodeInput = ""
                        }
                    },
                    enabled = duressCodeInput.length >= 8,
                ) { Text("Save duress code") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
