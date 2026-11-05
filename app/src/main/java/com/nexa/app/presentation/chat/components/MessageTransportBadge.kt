package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.TransportType

/**
 * Renders a visual transport route badge on message bubbles:
 * 🌐 Net (Internet Relay), 📡 Mesh (Local BLE/Wi-Fi Direct DTN), ✉️ SMS (Port 8901 Binary PDU).
 * Visibility controlled by user settings toggle (ChatPrivacyPrefs.showTransportBadges).
 */
@Composable
fun MessageTransportBadge(
    transportType: TransportType,
    modifier: Modifier = Modifier
) {
    val (label, emoji, badgeColor) = when (transportType) {
        TransportType.DATA_SMS -> Triple("SMS", "✉️", NexaWarningOrange)
        TransportType.DTN_MESH -> Triple("Mesh", "📡", NexaBadgeMonospace)
        TransportType.INTERNET -> Triple("Net", "🌐", MaterialTheme.colorScheme.primary)
    }

    Surface(
        color = badgeColor.copy(alpha = 0.18f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = emoji,
                fontSize = 8.sp
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = badgeColor
            )
        }
    }
}
