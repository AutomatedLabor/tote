package com.tote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tote.app.profile.ProfileRegistry

/**
 * Bottom-sheet profile switcher.
 *
 * Replaces the v0.4.0 AlertDialog-based ProfilePickerDialog with a proper
 * Material3 ModalBottomSheet that shows:
 *   - Each existing profile as a row: colored avatar + name + (active? check mark)
 *   - A "Create new profile" row at the bottom with the + icon
 *
 * Per ADR-018 (branding): each profile gets a deterministic color derived
 * from its profileId hash. The first 3 profiles use the brand palette
 * (Tote orange / deep teal / purple), subsequent ones cycle through a
 * palette of 8 colors.
 *
 * Per v1.1.0-alpha1: per ADR-019, the bottom-sheet has accessibility labels
 * on every row (role = Button, contentDescription = profile name + state).
 *
 * @param profiles List of all existing profiles
 * @param activeProfileId The currently-active profile ID (gets the check mark)
 * @param onProfileSelected Called when a profile row is tapped
 * @param onCreateNew Called when the "Create new profile" row is tapped
 * @param onDismiss Called when the sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherBottomSheet(
    profiles: List<ProfileRegistry.ToteProfile>,
    activeProfileId: String,
    onProfileSelected: (ProfileRegistry.ToteProfile) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            // Sheet drag handle (built-in)
            // Title
            Text(
                text = "Switch profile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                text = "Each profile has its own isolated cookies. Switching destroys the current profile's tabs and reloads in the new profile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Profile list
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        onClick = {
                            onProfileSelected(profile)
                            onDismiss()
                        },
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    CreateProfileRow(onClick = {
                        onCreateNew()
                        onDismiss()
                    })
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ProfileRegistry.ToteProfile,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val color = colorForProfile(profile.id)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                contentDescription = "Profile ${profile.displayName}" +
                    if (isActive) ", currently active" else ", tap to switch"
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Colored avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (isActive) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (isActive) "Active" else "Tap to switch",
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun CreateProfileRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                contentDescription = "Create new profile"
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "Create new profile",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Deterministic color for a profile based on its ID hash.
 *
 * First 3 profiles use the Tote brand palette. Subsequent ones cycle
 * through 8 extended Material colors.
 */
private fun colorForProfile(profileId: String): Color {
    val hash = profileId.hashCode()
    val palette = listOf(
        Color(0xFFFF5722),  // Tote orange
        Color(0xFF00695C),  // Tote teal
        Color(0xFF7B1FA2),  // Purple
        Color(0xFF1976D2),  // Blue
        Color(0xFF388E3C),  // Green
        Color(0xFFF57C00),  // Amber
        Color(0xFFE64A19),  // Deep orange
        Color(0xFF00838F),  // Cyan
    )
    return palette[Math.floorMod(hash, palette.size)]
}
