package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier
) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    val bubbleColor = if (isOutgoing) {
        when (message.transportType) {
            TransportType.DATA_SMS -> NexaWarningOrange.copy(alpha = 0.18f)
            TransportType.DTN_MESH -> NexaBadgeMonospace.copy(alpha = 0.18f)
            TransportType.INTERNET -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        }
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }

    val textColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                Text(
                    text = message.plainTextContent,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Transport Badge: "Net" vs "SMS" vs "Mesh"
                    val (badgeText, badgeColor) = when (message.transportType) {
                        TransportType.DATA_SMS -> Pair("SMS", NexaWarningOrange)
                        TransportType.DTN_MESH -> Pair("Mesh", NexaBadgeMonospace)
                        TransportType.INTERNET -> Pair("Net", MaterialTheme.colorScheme.primary)
                    }

                    Surface(
                        color = badgeColor.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        StatusIndicator(status = message.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: MessageStatus) {
    when (status) {
        MessageStatus.PENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Pending in Outbox",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent to Carrier/Mesh",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered to Device",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read by Recipient",
                tint = NexaBadgeMonospace, // Cyan colored tick marks for READ
                modifier = Modifier.size(14.dp)
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Failed Delivery",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
