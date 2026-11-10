package com.nexa.app.presentation.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.TransportType

@Composable
fun TransportToggleBar(
    selectedTransport: TransportType,
    destinationPhone: String?,
    onTransportSelected: (TransportType) -> Unit,
    onPhoneEditClicked: () -> Unit,
    modifier: Modifier = Modifier,
    isSmsSupported: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Multi-Transport Pill Selector (Internet, Data SMS, DTN Mesh)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportOptionPill(
                icon = Icons.Default.Cloud,
                label = "Net",
                isSelected = selectedTransport == TransportType.INTERNET,
                activeColor = MaterialTheme.colorScheme.primary,
                onClick = { onTransportSelected(TransportType.INTERNET) }
            )

            TransportOptionPill(
                icon = Icons.Default.SignalCellularAlt,
                label = if (isSmsSupported) "SMS (8901)" else "SMS (Disabled)",
                isSelected = selectedTransport == TransportType.DATA_SMS,
                activeColor = NexaWarningOrange,
                enabled = isSmsSupported,
                onClick = { if (isSmsSupported) onTransportSelected(TransportType.DATA_SMS) }
            )

            TransportOptionPill(
                icon = Icons.Default.Hub,
                label = "DTN Mesh",
                isSelected = selectedTransport == TransportType.DTN_MESH,
                activeColor = NexaBadgeMonospace,
                onClick = { onTransportSelected(TransportType.DTN_MESH) }
            )
        }

        // Contextual Destination Phone badge when in Data SMS mode
        if (selectedTransport == TransportType.DATA_SMS) {
            val phoneText = if (destinationPhone.isNullOrBlank()) "Set Phone" else destinationPhone
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(NexaWarningOrange.copy(alpha = 0.15f))
                    .border(1.dp, NexaWarningOrange.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable { onPhoneEditClicked() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "Target Phone",
                        tint = NexaWarningOrange,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = phoneText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = NexaWarningOrange
                    )
                }
            }
        } else if (selectedTransport == TransportType.DTN_MESH) {
            // Contextual DTN Mesh indicator
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(NexaBadgeMonospace.copy(alpha = 0.15f))
                    .border(1.dp, NexaBadgeMonospace.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "7 Hops / 48h TTL",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = NexaBadgeMonospace
                )
            }
        }
    }
}

@Composable
private fun TransportOptionPill(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.22f) else Color.Transparent,
        animationSpec = tween(200),
        label = "bgColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            isSelected -> activeColor
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(200),
        label = "contentColor"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            ),
            color = contentColor
        )
    }
}
