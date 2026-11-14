package com.nexa.app.presentation.profile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.data.local.preferences.OfflinePresenceMode
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Active Status Section:
 * - Current presence state & last seen
 * - Privacy toggle controls for active status, last-seen, and typing indicator
 */
@Composable
fun ContactActiveStatus(
    isOnline: Boolean,
    lastSeenText: String,
    showOnlineStatus: Boolean,
    onlinePrivacyMode: OnlinePrivacyMode,
    offlinePresenceMode: OfflinePresenceMode,
    selectedOnlineStatusContacts: List<String>,
    typingPrivacyMode: TypingPrivacyMode,
    onToggleShowOnlineStatus: (Boolean) -> Unit,
    onTogglePresenceMode: (OnlinePrivacyMode) -> Unit,
    onOfflinePresenceModeChanged: (OfflinePresenceMode) -> Unit,
    onToggleTypingMode: (TypingPrivacyMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)
    val effectivePresenceEnabled = showOnlineStatus && onlinePrivacyMode != OnlinePrivacyMode.NOBODY

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        surfaceColor = surfaceColor,
        borderColor = borderColor,
        elevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_clock_fading),
                    contentDescription = "Active Status",
                    tint = secondaryTextColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Active Status & Privacy",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = primaryTextColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (effectivePresenceEnabled && isOnline) NexaVerifiedGreen else Color(0xFF737C8D))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (effectivePresenceEnabled && isOnline) "Active Now" else "Offline",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        color = primaryTextColor
                    )
                    Text(
                        text = lastSeenText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = secondaryTextColor
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = dividerColor,
                thickness = 1.dp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show my online status",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        color = primaryTextColor
                    )
                    Text(
                        text = if (showOnlineStatus) "Visible to selected viewers" else "Hidden from all viewers",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = secondaryTextColor
                    )
                }
                Switch(
                    checked = showOnlineStatus,
                    onCheckedChange = onToggleShowOnlineStatus,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2),
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                    )
                )
            }

            Text(
                text = "Who can see my online status",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                ),
                color = primaryTextColor,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            OnlinePrivacyMode.values().forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (mode) {
                            OnlinePrivacyMode.EVERYONE -> "Everyone"
                            OnlinePrivacyMode.MY_CONTACTS -> "My contacts"
                            OnlinePrivacyMode.NOBODY -> "Nobody"
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        color = primaryTextColor
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (onlinePrivacyMode == mode) NexaVerifiedGreen else Color(0xFF737C8D))
                            .clickable { onTogglePresenceMode(mode) },
                        contentAlignment = Alignment.Center
                    ) { }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = dividerColor,
                thickness = 1.dp
            )

            Text(
                text = "When I'm Offline",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                ),
                color = primaryTextColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            OfflinePresenceMode.values().forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = mode.label,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        color = primaryTextColor
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (offlinePresenceMode == mode) NexaVerifiedGreen else Color(0xFF737C8D))
                            .clickable { onOfflinePresenceModeChanged(mode) },
                        contentAlignment = Alignment.Center
                    ) { }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = dividerColor,
                thickness = 1.dp
            )

            val isTypingEnabled = typingPrivacyMode == TypingPrivacyMode.EVERYONE
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Typing Indicator",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        color = primaryTextColor
                    )
                    Text(
                        text = if (isTypingEnabled) "Shows when you are typing" else "Client-side suppression active",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = secondaryTextColor
                    )
                }

                Switch(
                    checked = isTypingEnabled,
                    onCheckedChange = { checked ->
                        onToggleTypingMode(if (checked) TypingPrivacyMode.EVERYONE else TypingPrivacyMode.NOBODY)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2),
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}
