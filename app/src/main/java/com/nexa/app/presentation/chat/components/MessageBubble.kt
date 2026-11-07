package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.core.util.SafeMessageParser
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    showTransportBadge: Boolean = true,
    isHighlighted: Boolean = false,
    onFailedClick: ((Message) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
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
                .then(
                    if (isHighlighted) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, bubbleShape)
                    } else Modifier
                )
                .background(bubbleColor)
                .combinedClickable(
                    onClick = {
                        if (isOutgoing && message.status == MessageStatus.FAILED && onFailedClick != null) {
                            onFailedClick(message)
                        }
                    },
                    onLongClick = onLongClick
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                if (!isOutgoing && message.conversationId.contains("group")) {
                    val senderDisplayName = when (message.senderNxId) {
                        "NX-DEMO-ALEX" -> "Alex"
                        "NX-DEMO-SAM" -> "Sam"
                        "NX-DEMO-PRIYA" -> "Priya"
                        else -> message.senderNxId
                    }
                    Text(
                        text = senderDisplayName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                val context = LocalContext.current
                val linkColor = MaterialTheme.colorScheme.primary
                val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                val annotatedContent = remember(message.plainTextContent, textColor, linkColor, codeBg) {
                    SafeMessageParser.parseMarkdownToAnnotatedString(
                        content = message.plainTextContent,
                        primaryColor = textColor,
                        linkColor = linkColor,
                        codeBgColor = codeBg
                    )
                }

                ClickableText(
                    text = annotatedContent,
                    style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                    onClick = { offset ->
                        annotatedContent.getStringAnnotations(tag = SafeMessageParser.TAG_URL, start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                SafeMessageParser.launchSafeWebUrl(context, annotation.item)
                            }
                    }
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    if (message.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (showTransportBadge) {
                        Spacer(modifier = Modifier.width(6.dp))
                        MessageTransportBadge(transportType = message.transportType)
                    }

                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        StatusIndicator(
                            status = message.status,
                            onClick = if (message.status == MessageStatus.FAILED && onFailedClick != null) {
                                { onFailedClick(message) }
                            } else null
                        )
                    }
                }

                if (isOutgoing && message.status == MessageStatus.FAILED) {
                    Text(
                        text = "⚠️ Delivery failed • Tap to retry",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    status: MessageStatus,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    when (status) {
        MessageStatus.PENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Pending in Outbox",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(13.dp)
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent to Carrier/Mesh",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(13.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered to Device",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(14.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Read by Recipient",
                tint = NexaBadgeMonospace, // Cyan colored tick marks for READ
                modifier = modifier.size(14.dp)
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = "Failed Delivery",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(13.dp)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
