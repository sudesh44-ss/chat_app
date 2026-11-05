package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.data.local.preferences.TransportRouteMode
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.common.avatar.NexaAvatar

/**
 * Top bar for Nexa Chat featuring dynamic multi-transport routing dropdown,
 * real-time typing indicators, bilateral presence, and privacy settings controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexaChatTopBar(
    participantName: String,
    participantNxId: String,
    isPeerTyping: Boolean,
    isPeerOnline: Boolean,
    connectionState: ConnectionState,
    selectedRouteMode: TransportRouteMode,
    selectedTransport: TransportType,
    showTransportBadges: Boolean,
    originalProfileName: String? = null,
    deviceContactName: String? = null,
    avatarUrl: String? = null,
    deviceContactPhotoUri: String? = null,
    onSelectRouteMode: (TransportRouteMode) -> Unit,
    onToggleTransportBadges: (Boolean) -> Unit,
    onEditNickname: () -> Unit = {},
    onViewProfile: () -> Unit = onEditNickname,
    onAvatarPhotoClick: ((Any) -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var routeDropdownExpanded by remember { mutableStateOf(false) }
    var optionsMenuExpanded by remember { mutableStateOf(false) }

    val activeColor = when (selectedTransport) {
        TransportType.DATA_SMS -> NexaWarningOrange
        TransportType.DTN_MESH -> NexaBadgeMonospace
        TransportType.INTERNET -> MaterialTheme.colorScheme.primary
    }

    TopAppBar(
        modifier = modifier,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Universal 3-Tier Priority Avatar (Photo viewer opens on tap ONLY if photo exists)
                NexaAvatar(
                    nexaAvatarUrl = avatarUrl,
                    deviceContactPhotoUri = deviceContactPhotoUri,
                    displayName = participantName,
                    size = 38.dp,
                    onPhotoClick = onAvatarPhotoClick
                )

                Spacer(modifier = Modifier.width(10.dp))

                // 3-Tier Identity contextual subtitle
                val identitySubtitle = remember(participantName, originalProfileName, deviceContactName) {
                    when {
                        !deviceContactName.isNullOrBlank() && !originalProfileName.isNullOrBlank() &&
                            (participantName != originalProfileName || participantName != deviceContactName) -> {
                            "$deviceContactName • ~ $originalProfileName"
                        }
                        !originalProfileName.isNullOrBlank() && originalProfileName != participantName -> {
                            "~ $originalProfileName"
                        }
                        !deviceContactName.isNullOrBlank() && deviceContactName != participantName -> {
                            "~ $deviceContactName"
                        }
                        else -> null
                    }
                }

                // Name & Presence / Typing indicator (clickable to view profile / edit nickname)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onViewProfile() }
                ) {
                    Text(
                        text = participantName.ifBlank { participantNxId },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )

                    if (identitySubtitle != null) {
                        Text(
                            text = identitySubtitle,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isPeerTyping) {
                        Text(
                            text = "typing...",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontStyle = FontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = participantNxId,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = NexaBadgeMonospace
                            )

                            if (isPeerOnline) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(NexaVerifiedGreen, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Online",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = NexaVerifiedGreen
                                )
                            }
                        }
                    }
                }

                // Dynamic Route Selector Pill Button
                Box {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = activeColor.copy(alpha = 0.15f),
                        modifier = Modifier.clickable { routeDropdownExpanded = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${selectedRouteMode.iconEmoji} ${selectedRouteMode.name.take(4)}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = activeColor
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Route",
                                tint = activeColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Dropdown for Route Selection
                    DropdownMenu(
                        expanded = routeDropdownExpanded,
                        onDismissRequest = { routeDropdownExpanded = false }
                    ) {
                        TransportRouteMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "${mode.iconEmoji}  ${mode.label}")
                                    }
                                },
                                trailingIcon = if (mode == selectedRouteMode) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onSelectRouteMode(mode)
                                    routeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Options Menu (3 dots)
                Box {
                    IconButton(onClick = { optionsMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options"
                        )
                    }

                    DropdownMenu(
                        expanded = optionsMenuExpanded,
                        onDismissRequest = { optionsMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("View Identity Profile") },
                            onClick = {
                                onViewProfile()
                                optionsMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Contact Nickname") },
                            onClick = {
                                onEditNickname()
                                optionsMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (showTransportBadges) {
                                        "Hide Transport Badges"
                                    } else {
                                        "Show Transport Badges"
                                    }
                                )
                            },
                            onClick = {
                                onToggleTransportBadges(!showTransportBadges)
                                optionsMenuExpanded = false
                            }
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}
