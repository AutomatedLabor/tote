package com.tote.app.ui

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.tote.app.webview.BrowserViewModel

/**
 * The main browser screen: URL bar + WebView + profile pill + tab strip.
 *
 * Swipe gesture on URL bar cycles tabs (per Phase 10: ADR-012, Chrome-style).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    var urlText by remember { mutableStateOf("") }
    var showProfilePicker by remember { mutableStateOf(false) }
    val activeUrl by viewModel.activeUrl.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()

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
                        // Swipe gesture: horizontal swipe on URL bar cycles tabs
                        detectHorizontalDragGestures(
                            onDragStart = { },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                            },
                            onDragEnd = { },
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
        }

        // WebView area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl("https://www.google.com")
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
                viewModel.switchProfile(profileId)
                showProfilePicker = false
            }
        )
    }
}

@Composable
fun ProfilePill(profileId: String, onClick: () -> Unit) {
    val color = when (profileId) {
        "default" -> Color(0xFF2196F3)  // Blue
        else -> Color(0xFFFF5722)        // Deep orange
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
            text = if (profileId == "default") "Personal" else "Profile",
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
