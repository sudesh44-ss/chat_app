package com.nexa.app.presentation.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.presentation.common.avatar.NexaAvatar

@Composable
fun NexaTopAppBar(
    connectionState: ConnectionState,
    profilePhotoUri: String? = null,
    avatarUrl: String? = null,
    displayName: String = "NEXA",
    onAudioHeadphonesClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onQrClick: () -> Unit = {},
    onLogoutClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Audio/Headphone Action
            IconButton(
                onClick = onAudioHeadphonesClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_headphones),
                    contentDescription = "Audio / Radio Mode",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Center: NEXA Branding
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onProfileClick)
            ) {
                Text(
                    text = "NEXA",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // Subtle Relay Connection Status Indicator
                RelayStatusBullet(connectionState = connectionState)
            }

            // Right Actions: Profile Avatar + Three-dot Overflow Menu
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onProfileClick)
                ) {
                    if (avatarUrl != null || profilePhotoUri != null) {
                        NexaAvatar(
                            nexaAvatarUrl = avatarUrl,
                            deviceContactPhotoUri = profilePhotoUri,
                            displayName = displayName,
                            size = 38.dp
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_profile),
                            contentDescription = "Profile Account",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_more_vert),
                            contentDescription = "More Options",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nexa_settings),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onProfileClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("My QR Code") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nexa_qr_code),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onQrClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Notification Settings") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nexa_bell),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onNotificationSettingsClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Lock / Reset Session", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nexa_shield),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onLogoutClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayStatusBullet(connectionState: ConnectionState) {
    val (dotColor, label) = when (connectionState) {
        is ConnectionState.Connected -> Pair(NexaVerifiedGreen, "Relay Online")
        is ConnectionState.Connecting -> Pair(NexaWarningOrange, "Connecting...")
        else -> Pair(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), "Offline Mesh")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 1.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(color = dotColor, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            ),
            color = dotColor
        )
    }
}
