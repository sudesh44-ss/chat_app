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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Four Communication Actions in a 2-Column Capsule Layout:
 * [ Chat ]       [ Video Call ]
 * [ Voice Call ] [ Share ]
 */
@Composable
fun ContactActionButtons(
    onChatClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onVoiceCallClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val contentColor = if (isDark) Color.White else Color(0xFF111111)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Row 1: Chat and Video Call
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionCapsuleButton(
                title = "Chat",
                iconRes = R.drawable.ic_nexa_chats,
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                contentColor = contentColor,
                onClick = onChatClick,
                modifier = Modifier.weight(1f)
            )

            ActionCapsuleButton(
                title = "Video Call",
                iconRes = R.drawable.ic_nexa_video,
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                contentColor = contentColor,
                onClick = onVideoCallClick,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Voice Call and Share
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionCapsuleButton(
                title = "Call",
                iconRes = R.drawable.ic_nexa_phone_call,
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                contentColor = contentColor,
                onClick = onVoiceCallClick,
                modifier = Modifier.weight(1f)
            )

            ActionCapsuleButton(
                title = "Share",
                iconRes = R.drawable.ic_nexa_share,
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                contentColor = contentColor,
                onClick = onShareClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ActionCapsuleButton(
    title: String,
    iconRes: Int,
    surfaceColor: Color,
    borderColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NexaGlassSurface(
        modifier = modifier
            .height(52.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        surfaceColor = surfaceColor,
        borderColor = borderColor,
        elevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = contentColor
            )
        }
    }
}
