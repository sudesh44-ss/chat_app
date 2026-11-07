package com.nexa.app.presentation.chat.components

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
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.common.glass.NexaGlassSurface

enum class NexaTransportOption {
    INTERNET,
    SMS,
    MESH_BLUETOOTH,
    AUTO
}

/**
 * Message Transport Selector with exact 4 options:
 * 1. Internet
 * 2. SMS
 * 3. Mesh / Nearby Bluetooth
 * 4. Auto
 */
@Composable
fun NexaTransportSelector(
    currentOption: NexaTransportOption,
    onOptionSelected: (NexaTransportOption) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) {
        Color(0xEE1E1E1E)
    } else {
        Color(0xEEFFFFFF)
    }

    val contentColor = if (isDark) Color.White else Color(0xFF111111)
    val subtitleColor = if (isDark) Color(0xFF9E9E9E) else Color(0xFF666666)

    NexaGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        surfaceColor = surfaceColor,
        elevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Message Transport",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Text(
                text = "All transports strictly maintain end-to-end encryption (E2EE).",
                fontSize = 12.sp,
                color = subtitleColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 1. Internet
            TransportOptionItem(
                title = "Internet",
                description = "Zero-disk RAM relay. Lowest latency & rich media.",
                iconRes = R.drawable.ic_nexa_wifi,
                tint = MaterialTheme.colorScheme.primary,
                isSelected = currentOption == NexaTransportOption.INTERNET,
                onClick = { onOptionSelected(NexaTransportOption.INTERNET) }
            )

            // 2. SMS
            TransportOptionItem(
                title = "SMS",
                description = "Port 8901 binary SMS airwaves. No data plan required.",
                iconRes = R.drawable.ic_nexa_messages_square,
                tint = NexaWarningOrange,
                isSelected = currentOption == NexaTransportOption.SMS,
                onClick = { onOptionSelected(NexaTransportOption.SMS) }
            )

            // 3. Mesh / Nearby Bluetooth
            TransportOptionItem(
                title = "Mesh / Nearby Bluetooth",
                description = "Peer-to-peer store-carry-forward. Zero infrastructure.",
                iconRes = R.drawable.ic_nexa_mesh,
                tint = NexaBadgeMonospace,
                isSelected = currentOption == NexaTransportOption.MESH_BLUETOOTH,
                onClick = { onOptionSelected(NexaTransportOption.MESH_BLUETOOTH) }
            )

            // 4. Auto
            TransportOptionItem(
                title = "Auto",
                description = "Intelligently routes via fastest available channel.",
                iconRes = R.drawable.ic_nexa_badge_check,
                tint = contentColor,
                isSelected = currentOption == NexaTransportOption.AUTO,
                onClick = { onOptionSelected(NexaTransportOption.AUTO) }
            )
        }
    }
}

@Composable
private fun TransportOptionItem(
    title: String,
    description: String,
    iconRes: Int,
    tint: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isSelected) {
        tint.copy(alpha = if (isDark) 0.18f else 0.12f)
    } else {
        Color.Transparent
    }

    NexaGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        surfaceColor = bgColor,
        isSelected = isSelected,
        elevation = if (isSelected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isDark) Color.White else Color.Black
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666)
                )
            }
            if (isSelected) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_check),
                    contentDescription = "Selected",
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
