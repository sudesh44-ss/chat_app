package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.TransportType

@Composable
fun TransportSelectorMenu(
    selectedTransport: TransportType,
    onSelectTransport: (TransportType) -> Unit,
    modifier: Modifier = Modifier,
    isSmsSupported: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportOptionItem(
                label = "Internet",
                icon = Icons.Default.Cloud,
                isSelected = selectedTransport == TransportType.INTERNET,
                activeColor = NexaVerifiedGreen,
                onClick = { onSelectTransport(TransportType.INTERNET) },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(4.dp))

            TransportOptionItem(
                label = if (isSmsSupported) "Data SMS" else "Data SMS (Disabled)",
                icon = Icons.Default.SignalCellularAlt,
                isSelected = selectedTransport == TransportType.DATA_SMS,
                activeColor = NexaWarningOrange,
                enabled = isSmsSupported,
                onClick = { if (isSmsSupported) onSelectTransport(TransportType.DATA_SMS) },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(4.dp))

            TransportOptionItem(
                label = "BLE Mesh",
                icon = Icons.Default.Hub,
                isSelected = selectedTransport == TransportType.DTN_MESH,
                activeColor = NexaBadgeMonospace,
                onClick = { onSelectTransport(TransportType.DTN_MESH) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TransportOptionItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (isSelected) activeColor.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        isSelected -> activeColor
        else -> MaterialTheme.colorScheme.outline
    }
    val fw = if (isSelected) FontWeight.Bold else FontWeight.Normal

    Box(
        modifier = modifier
            .background(color = bg, shape = RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = fw,
                    color = tint
                )
            )
        }
    }
}
