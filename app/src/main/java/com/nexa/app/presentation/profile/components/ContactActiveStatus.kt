package com.nexa.app.presentation.profile.components

import androidx.compose.foundation.background
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
    onlinePrivacyMode: OnlinePrivacyMode,
    typingPrivacyMode: TypingPrivacyMode,
    onTogglePresenceMode: (OnlinePrivacyMode) -> Unit,
    onToggleTypingMode: (TypingPrivacyMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)

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
            // Section Header
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

            // Current Peer Status Row
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
                        .background(if (isOnline) NexaVerifiedGreen else Color(0xFF737C8D))
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = if (isOnline) "Active Now" else "Offline",
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

            // Setting 1: Show Active Status Switch
            val isPresenceEnabled = onlinePrivacyMode != OnlinePrivacyMode.NOBODY
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Share My Active Status",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        color = primaryTextColor
                    )
                    Text(
                        text = if (isPresenceEnabled) "Visible to mutual contacts" else "Hidden from all users",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = secondaryTextColor
                    )
                }

                Switch(
                    checked = isPresenceEnabled,
                    onCheckedChange = { checked ->
                        onTogglePresenceMode(if (checked) OnlinePrivacyMode.MY_CONTACTS else OnlinePrivacyMode.NOBODY)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2),
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                    )
                )
            }

            // Setting 2: Typing Indicators Switch
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
