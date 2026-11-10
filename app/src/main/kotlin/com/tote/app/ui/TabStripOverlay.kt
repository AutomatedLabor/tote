package com.tote.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tote.app.webview.TabManager
import com.tote.app.webview.WebViewPool

/**
 * Bottom-of-screen horizontal strip showing one thumbnail per open tab.
 *
 * Per ADR-014 (truly-live tabs): the thumbnail is the screenshot captured
 * by [WebViewPool.WebViewEntry.checkpoint] when the tab was last evicted.
 * If the tab is currently live (not evicted), no thumbnail is shown — we
 * show the title only (since pulling a screenshot of a live WebView would
 * require PixelCopy which we haven't yet implemented per-tab).
 *
 * Tapping a thumbnail switches to that tab.
 * Tapping the "x" closes the tab.
 *
 * Accessibility: each thumbnail has a contentDescription of the form
 * "Tab: <title>, double-tap to switch" via the [semantics] modifier.
 *
 * Per ADR-019: the strip is 72dp tall — tall enough to read the title but
 * short enough to not steal screen real estate from the WebView.
 */
@Composable
fun TabStripOverlay(
    tabs: List<TabManager.Tab>,
    activeTabId: String?,
    webViewPool: WebViewPool,
    onTabSelected: (tabId: String) -> Unit,
    onTabClosed: (tabId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        modifier = modifier,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tabs, key = { it.id }) { tab ->
                TabThumbnail(
                    tab = tab,
                    isActive = tab.id == activeTabId,
                    thumbnail = webViewPool.getThumbnail(tab.id),
                    onClick = { onTabSelected(tab.id) },
                    onClose = { onTabClosed(tab.id) },
                )
            }
            item {
                // "New tab" button at the end of the strip
                IconButton(onClick = { /* TODO: wire to TabManager.createTab */ }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New tab",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabThumbnail(
    tab: TabManager.Tab,
    isActive: Boolean,
    thumbnail: Bitmap?,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    Box(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .semantics {
                contentDescription = "Tab: ${tab.title}. Double-tap to switch."
                role = Role.Tab
            }
            .clickable(onClick = onClick),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail (or placeholder)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(borderColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    androidx.compose.foundation.Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Placeholder — small URL favicon-ish letter
                    Text(
                        text = tab.url.takeIf { it.startsWith("http") }
                            ?.let { url ->
                                val host = android.net.Uri.parse(url).host ?: ""
                                host.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                            } ?: "?",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Title
            Text(
                text = tab.title.takeIf { it.isNotBlank() } ?: tab.url,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            )

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close tab ${tab.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
