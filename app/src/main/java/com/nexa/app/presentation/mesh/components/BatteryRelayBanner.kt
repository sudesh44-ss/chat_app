package com.nexa.app.presentation.mesh.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
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
 * Warning banner displayed when device battery drops below 15% (Scenario 15),
 * signaling that multi-hop packet relaying is throttled to prevent shutdown.
 */
@Composable
fun BatteryRelayBanner(
    batteryPct: Int,
    modifier: Modifier = Modifier
) {
    if (batteryPct >= 15) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF3E0), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.BatteryAlert,
            contentDescription = null,
            tint = Color(0xFFE65100),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (batteryPct < 5) {
                "Battery critical ($batteryPct%): Mesh relay disabled. Direct messages only."
            } else {
                "Low battery ($batteryPct%): Mesh relaying throttled to conserve power."
            },
            color = Color(0xFFE65100),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
