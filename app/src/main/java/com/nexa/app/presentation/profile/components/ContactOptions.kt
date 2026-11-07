package com.nexa.app.presentation.profile.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Settings & Operations Rows for Contact Profile:
 * - Contact Details
 * - Pinned Messages
 * - Connectivity
 * - Mute Notifications
 * - Custom Notifications
 * - Chat Wallpaper
 * - Clear Chat (Dialog prompt)
 * - Block Contact (Red Destructive action)
 */
@Composable
fun ContactOptions(
    pinnedCount: Int,
    latestPinnedPreview: String?,
    isMuted: Boolean,
    muteSubtitle: String,
    connectivitySummary: String,
    isBlocked: Boolean,
    onContactDetailsClick: () -> Unit,
    onPinnedMessagesClick: () -> Unit,
    onConnectivityClick: () -> Unit,
    onMuteClick: () -> Unit,
    onCustomNotificationsClick: () -> Unit,
    onWallpaperClick: () -> Unit,
    onClearChatClick: () -> Unit,
    onBlockContactClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)
    val dangerColor = MaterialTheme.colorScheme.error

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Group 1: Navigation & Features Card
        NexaGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            surfaceColor = surfaceColor,
            borderColor = borderColor,
            elevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                // Contact Details
                OptionItem(
                    title = "Contact Details",
                    subtitle = "Phone sync, identity and details",
                    iconRes = R.drawable.ic_nexa_contact_card,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    onClick = onContactDetailsClick
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor, thickness = 1.dp)

                // Pinned Messages
                OptionItem(
                    title = "Pinned Messages",
                    subtitle = if (pinnedCount > 0) "$pinnedCount pinned • ${latestPinnedPreview ?: "Tap to view"}" else "None",
                    iconRes = R.drawable.ic_nexa_pin,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    onClick = onPinnedMessagesClick
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor, thickness = 1.dp)

                // Connectivity
                OptionItem(
                    title = "Connectivity",
                    subtitle = connectivitySummary,
                    iconRes = R.drawable.ic_nexa_wifi,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    onClick = onConnectivityClick
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor, thickness = 1.dp)

                // Mute Notifications
                OptionItem(
                    title = "Mute Notifications",
                    subtitle = if (isMuted) muteSubtitle else "Unmuted",
                    iconRes = R.drawable.ic_nexa_mute,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    onClick = onMuteClick
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor, thickness = 1.dp)

                // Custom Notifications
                OptionItem(
                    title = "Custom Notifications",
                    subtitle = "Tone and vibration style",
                    iconRes = R.drawable.ic_nexa_bell,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    onClick = onCustomNotificationsClick
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor, thickness = 1.dp)

                // Chat Wallpaper
                OptionItem(
                    title = "Chat Wallpaper",
                    subtitle = "Customize appearance for this chat",
                    iconRes = R.drawable.ic_nexa_wallpaper,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    onClick = onWallpaperClick
                )
            }
        }

        // Group 2: Actions & Privacy Card (Clear Chat & Block Contact)
        NexaGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            surfaceColor = surfaceColor,
            borderColor = borderColor,
            elevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                // Clear Chat
                OptionItem(
                    title = "Clear Chat",
                    subtitle = "Delete local messages in this conversation",
                    iconRes = R.drawable.ic_nexa_clear_chat,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    onClick = onClearChatClick
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = dividerColor, thickness = 1.dp)

                // Block Contact (Red accent)
                OptionItem(
                    title = if (isBlocked) "Unblock Contact" else "Block Contact",
                    subtitle = if (isBlocked) "Tap to restore communication" else "Restrict calls, presence and messaging",
                    iconRes = R.drawable.ic_nexa_block_ban,
                    primaryTextColor = dangerColor,
                    secondaryTextColor = dangerColor.copy(alpha = 0.7f),
                    iconTint = dangerColor,
                    onClick = onBlockContactClick
                )
            }
        }
    }
}

@Composable
private fun OptionItem(
    title: String,
    subtitle: String,
    iconRes: Int,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    iconTint: Color? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = iconTint ?: secondaryTextColor,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = primaryTextColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = secondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = secondaryTextColor.copy(alpha = 0.6f),
            modifier = Modifier.size(12.dp)
        )
    }
}
