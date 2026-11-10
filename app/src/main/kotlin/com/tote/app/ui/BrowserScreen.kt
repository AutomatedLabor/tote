package com.tote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tote.app.ToteApplication
import com.tote.app.webview.BrowserViewModel
import com.tote.app.webview.DeviceTierHelper

/**
 * The main browser screen: URL bar + WebView + profile pill + tab strip.
 *
 * Swipe gesture on URL bar cycles tabs (per Phase 10: ADR-012, Chrome-style).
 *
 * The WebView is owned by [BrowserViewModel.webViewPool] and survives across
 * recompositions. We attach the pool's active WebView into the [AndroidView]
 * factory (NOT a fresh WebView each recompose).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    var urlText by remember { mutableStateOf("") }
    var showProfilePicker by remember { mutableStateOf(false) }
    var showSendSessionDialog by remember { mutableStateOf(false) }
    val activeUrl by viewModel.activeUrl.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // One-time setup: tell the ViewModel about the device tier so it can size
    // the WebViewPool. We do this once on first composition.
    LaunchedEffect(Unit) {
        val tier = DeviceTierHelper.detect(context)
        viewModel.applyDeviceTier(tier.maxLiveTabs)
    }

    // Update URL bar when active tab changes
    LaunchedEffect(activeUrl) {
        urlText = activeUrl
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Top bar: URL bar + profile pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back/Forward buttons
            IconButton(onClick = { viewModel.goBack() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = { viewModel.goForward() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ArrowForward, contentDescription = "Forward")
            }

            // URL bar with swipe-to-switch-tab gesture
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        var accumulated = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { accumulated = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                accumulated += dragAmount
                            },
                            onDragEnd = {
                                // Swipe threshold: 80dp-ish left or right
                                val threshold = 200f
                                if (accumulated > threshold) {
                                    viewModel.previousTab()
                                } else if (accumulated < -threshold) {
                                    viewModel.nextTab()
                                }
                            },
                            onDragCancel = { }
                        )
                    }
                    .padding(horizontal = 4.dp),
                placeholder = { Text("Search or type URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        viewModel.loadUrl(urlText)
                    }
                ),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // Profile pill
            ProfilePill(
                profileId = activeProfileId,
                onClick = { showProfilePicker = true }
            )

            // Send session button (triggers SessionTransferService)
            IconButton(
                onClick = { showSendSessionDialog = true },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send session to paired device")
            }
        }

        // WebView area — uses the ViewModel's pool, NOT a fresh WebView each recompose.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AndroidView(
                factory = { ctx ->
                    // Get the active WebView from the pool — this is the
                    // critical wiring that makes tabs "truly live" (ADR-014).
                    val app = ToteApplication.get(ctx)
                    val activeTab = viewModel.tabManager.getActiveTab()
                    if (activeTab != null) {
                        viewModel.webViewPool.getOrCreateWebView(activeTab.id, activeTab.profileId)
                            ?: WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                loadUrl("about:blank")
                            }
                    } else {
                        // No active tab — show a blank WebView (will be replaced
                        // once a tab is created via the + button).
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl("about:blank")
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // Profile picker dialog
    if (showProfilePicker) {
        ProfilePickerDialog(
            onDismiss = { showProfilePicker = false },
            onProfileSelected = { profileId ->
                if (profileId == "new") {
                    // Create a new profile
                    val app = ToteApplication.get(context)
                    val newId = app.profileRegistry.createProfile(
                        displayName = "Profile ${app.profileRegistry.getAllProfiles().size + 1}",
                    )
                    viewModel.switchProfile(newId)
                } else {
                    viewModel.switchProfile(profileId)
                }
                showProfilePicker = false
            }
        )
    }

    // Send session dialog
    if (showSendSessionDialog) {
        SendSessionDialog(
            onDismiss = { showSendSessionDialog = false },
            onSend = { recipientHex ->
                viewModel.sendSessionToPairedDevice(recipientHex)
                showSendSessionDialog = false
            }
        )
    }
}

@Composable
fun SendSessionDialog(
    onDismiss: () -> Unit,
    onSend: (recipientDeviceIdHex: String) -> Unit
) {
    var recipientHex by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send session to paired device") },
        text = {
            Column {
                Text(
                    "Paste the recipient device's ID (64 hex chars, or scan their QR):",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = recipientHex,
                    onValueChange = { recipientHex = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. abc123... (64 chars)") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This will snapshot the current profile's cookies, encrypt them with the shared epoch key, sign with your device key, and upload to the relay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(recipientHex.trim()) },
                enabled = recipientHex.length == 64
            ) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ProfilePill(profileId: String, onClick: () -> Unit) {
    val color = when (profileId) {
        "default" -> Color(0xFF2196F3)  // Blue
        "work" -> Color(0xFFFF9800)     // Orange
        else -> Color(0xFFFF5722)       // Deep orange (user-created)
    }
    val label = when (profileId) {
        "default" -> "Personal"
        "work" -> "Work"
        else -> "Profile"
    }
    Row(
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
fun ProfilePickerDialog(
    onDismiss: () -> Unit,
    onProfileSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Profile") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Personal") },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onProfileSelected("default") })
                    }
                )
                ListItem(
                    headlineContent = { Text("Work") },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onProfileSelected("work") })
                    }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Add Profile") },
                    leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onProfileSelected("new") })
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
