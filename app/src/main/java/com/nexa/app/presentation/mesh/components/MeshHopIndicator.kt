package com.nexa.app.presentation.mesh.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual chip displayed inside message bubbles indicating hop radius and transport medium:
 * - Hop count (e.g. "Hop 2/7")
 * - Medium (BLE L2CAP vs Wi-Fi Aware)
 * - DTN Mule delivery status
 */
@Composable
fun MeshHopIndicator(
    hopCount: Int,
    maxHops: Int = 7,
    medium: String = "BLE", // "BLE" or "WIFI_AWARE"
    isMuleCustody: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (bgColor, tintColor) = if (isMuleCustody) {
        Color(0xFFEDE7F6) to Color(0xFF5E35B1) // Purple for DTN Mule
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) to MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    isMuleCustody -> Icons.Default.LocalShipping
                    medium == "WIFI_AWARE" -> Icons.Default.Wifi
                    else -> Icons.Default.Bluetooth
                },
                contentDescription = null,
                tint = tintColor,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isMuleCustody) "Data Mule" else "Hop $hopCount/$maxHops",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = tintColor
            )
        }
    }
}
