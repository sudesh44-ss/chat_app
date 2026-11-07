package com.nexa.app.presentation.chat.components

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Capsule Glass Header for NEXA 1-to-1 Chat.
 *
 * Implements:
 * - 30-32dp rounded capsule glass surface
 * - Back button
 * - Avatar with presence dot
 * - Contact display name & presence / status text
 * - Voice Call, Video Call, and Overflow Menu actions
 */
@Composable
fun NexaChatHeader(
    participantName: String,
    participantNxId: String,
    isPeerTyping: Boolean,
    isPeerOnline: Boolean,
    connectionState: ConnectionState,
    selectedTransport: TransportType,
    avatarUrl: String? = null,
    deviceContactPhotoUri: String? = null,
    onBack: () -> Unit,
    onAvatarClick: (() -> Unit)? = null,
    onVoiceCallClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onOverflowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) {
        Color(0xCC121212)
    } else {
        Color(0xEEF8F9FA)
    }

    val contentColor = if (isDark) Color.White else Color(0xFF111111)
    val subtitleColor = if (isDark) Color(0xFF9E9E9E) else Color(0xFF666666)

    val (statusColor, statusText) = when {
        isPeerTyping -> Pair(MaterialTheme.colorScheme.primary, "Typing...")
        selectedTransport == TransportType.DATA_SMS -> Pair(NexaWarningOrange, "SMS Airwaves")
        selectedTransport == TransportType.DTN_MESH -> Pair(NexaBadgeMonospace, "Nearby Mesh")
        isPeerOnline || connectionState is ConnectionState.Connected -> Pair(NexaVerifiedGreen, "Online")
        connectionState is ConnectionState.Connecting -> Pair(NexaWarningOrange, "Connecting...")
        else -> Pair(subtitleColor, "Offline")
    }

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(60.dp),
        shape = RoundedCornerShape(30.dp),
        surfaceColor = surfaceColor,
        elevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back Button
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_arrow_back),
                    contentDescription = "Back",
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            // Avatar with Presence Indicator
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                NexaAvatar(
                    nexaAvatarUrl = avatarUrl,
                    deviceContactPhotoUri = deviceContactPhotoUri,
                    displayName = participantName,
                    size = 40.dp,
                    onPhotoClick = { onAvatarClick?.invoke() }
                )
                if (isPeerOnline || connectionState is ConnectionState.Connected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .align(Alignment.BottomEnd)
                            .background(statusColor, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Contact Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = participantName,
                    color = contentColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        color = if (isPeerTyping) MaterialTheme.colorScheme.primary else subtitleColor,
                        fontSize = 11.sp,
                        fontWeight = if (isPeerTyping) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Icons (Voice Call, Video Call, Overflow Menu)
            IconButton(
                onClick = onVoiceCallClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_phone_call),
                    contentDescription = "Voice Call",
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onVideoCallClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_video),
                    contentDescription = "Video Call",
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onOverflowClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_more_vert),
                    contentDescription = "Options",
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
