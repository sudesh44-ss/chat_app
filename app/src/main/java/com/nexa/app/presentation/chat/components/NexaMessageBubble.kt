package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import coil.compose.AsyncImage
import com.nexa.app.domain.model.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Liquid Glass Message Bubble with grouping, delivery indicators, reactions, and pin status.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NexaMessageBubble(
    message: Message,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    reactions: List<String> = emptyList(),
    isHighlighted: Boolean = false,
    originalQuotedMessage: Message? = null,
    onQuotedMessageClick: ((String) -> Unit)? = null,
    onFailedClick: ((Message) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onReactionClick: ((String) -> Unit)? = null,
    textScale: Float = 1f,
    highContrast: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    val scale = textScale.coerceIn(0.8f, 1.5f)

    // Tailored corner radii for message grouping
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = if (isFirstInGroup) 18.dp else 6.dp,
            bottomStart = 18.dp,
            bottomEnd = if (isLastInGroup) 4.dp else 6.dp
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInGroup) 18.dp else 6.dp,
            topEnd = 18.dp,
            bottomStart = if (isLastInGroup) 4.dp else 6.dp,
            bottomEnd = 18.dp
        )
    }

    val bubbleSurfaceColor = if (isOutgoing) {
        when (message.transportType) {
            TransportType.DATA_SMS -> NexaWarningOrange.copy(alpha = if (isDark) 0.22f else 0.16f)
            TransportType.DTN_MESH -> NexaBadgeMonospace.copy(alpha = if (isDark) 0.22f else 0.16f)
            TransportType.INTERNET -> MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.25f else 0.18f)
        }
    } else {
        if (highContrast) {
            if (isDark) Color(0xFF101010) else Color.White
        } else {
            if (isDark) Color(0xCC202020) else Color(0xEEF1F3F4)
        }
    }

    val textColor = when {
        highContrast && isDark -> Color.White
        highContrast -> Color.Black
        isDark -> Color.White
        else -> Color(0xFF111111)
    }
    val timeColor = if (isDark) Color(0xFFAAAAAA) else Color(0xFF777777)

    val formattedTime = remember(message.timestamp) {
        try {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp))
        } catch (e: Exception) {
            ""
        }
    }

    val verticalPadding = if (isLastInGroup) 4.dp else 1.5.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        horizontalAlignment = alignment
    ) {
        Box(contentAlignment = if (isOutgoing) Alignment.BottomEnd else Alignment.BottomStart) {
            NexaGlassSurface(
                modifier = Modifier
                    .widthIn(min = 64.dp, max = 290.dp)
                    .combinedClickable(
                        onClick = {
                            if (isOutgoing && message.status == MessageStatus.FAILED && onFailedClick != null) {
                                onFailedClick(message)
                            }
                        },
                        onLongClick = onLongClick
                    ),
                shape = bubbleShape,
                surfaceColor = bubbleSurfaceColor,
                isSelected = isHighlighted,
                elevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // Pinned Indicator Badge
                    if (message.isPinned) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Pinned Message",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Quoted Message Preview (if this message is a reply)
                    if (message.replyToMessageId != null) {
                        val quoteAccentColor = when (message.transportType) {
                            TransportType.DATA_SMS -> NexaWarningOrange
                            TransportType.DTN_MESH -> NexaBadgeMonospace
                            TransportType.INTERNET -> MaterialTheme.colorScheme.primary
                        }

                        val displayInfo = remember(originalQuotedMessage, message.replyToSenderNxId, message.replyToSnippet) {
                            QuotedMessageHelper.resolveDisplayInfo(
                                originalMessage = originalQuotedMessage,
                                fallbackSenderName = message.replyToSenderNxId,
                                fallbackSnippet = message.replyToSnippet
                            )
                        }

                        QuotedMessageBubbleCard(
                            displayInfo = displayInfo,
                            accentColor = quoteAccentColor,
                            onQuoteClick = {
                                onQuotedMessageClick?.invoke(message.replyToMessageId)
                            },
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    // Message Text or Media Content
                    if (message.isDeleted) {
                        Text(
                            text = "🚫 This message was deleted",
                            color = Color.Gray,
                            fontStyle = FontStyle.Italic,
                            fontSize = (14f * scale).sp
                        )
                    } else if (message.messageType == MessageType.GIF || message.messageType == MessageType.MEME) {
                        val mediaUri = message.plainTextContent.takeIf { it.startsWith("/") || it.startsWith("content://") || it.startsWith("file://") }
                        if (mediaUri != null) {
                            AsyncImage(
                                model = mediaUri,
                                contentDescription = message.messageType.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = if (message.messageType == MessageType.GIF) "🎬 GIF" else "🎭 MEME",
                                fontWeight = FontWeight.Bold,
                                fontSize = (15f * scale).sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (message.plainTextContent.isNotBlank() && !message.plainTextContent.startsWith("[")) {
                                Spacer(modifier = Modifier.size(2.dp))
                                Text(
                                    text = message.plainTextContent,
                                    color = textColor,
                                    fontSize = (14f * scale).sp
                                )
                            }
                        }
                    } else {
                        Text(
                            text = message.plainTextContent,
                            color = textColor,
                            fontSize = (15f * scale).sp,
                            lineHeight = (20f * scale).sp
                        )
                    }

                    Spacer(modifier = Modifier.size(4.dp))

                    // Timestamp & Delivery Indicators
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (message.editedAt != null) {
                            Text(
                                text = "(edited)",
                                fontSize = 9.sp,
                                fontStyle = FontStyle.Italic,
                                color = timeColor
                            )
                        }
                        if (message.isStarred) {
                            Text(
                                text = "★",
                                fontSize = 10.sp,
                                color = Color(0xFFFFD700)
                            )
                        }
                        Text(
                            text = formattedTime,
                            fontSize = 10.sp,
                            color = timeColor
                        )

                        if (isOutgoing) {
                            DeliveryStatusIndicator(status = message.status)
                        }
                    }
                }
            }
        }

        // Reactions Display Badge (if any)
        if (reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(
                    start = if (isOutgoing) 0.dp else 8.dp,
                    end = if (isOutgoing) 8.dp else 0.dp,
                    top = 2.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                reactions.distinct().forEach { reaction ->
                    val count = reactions.count { it == reaction }
                    NexaReactionBadge(
                        reaction = reaction,
                        count = count,
                        onClick = { onReactionClick?.invoke(reaction) }
                    )
                }
            }
        }
    }
}

/**
 * Message Delivery Tick Indicator.
 * Supports: Pending/Clock, Sent (Single Tick), Delivered (Double Tick), Read (Colored Double Tick), Failed.
 */
@Composable
private fun DeliveryStatusIndicator(status: MessageStatus) {
    when (status) {
        MessageStatus.CREATED,
        MessageStatus.PENDING -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_help),
                contentDescription = "Pending",
                tint = Color.Gray,
                modifier = Modifier.size(12.dp)
            )
        }
        MessageStatus.QUEUED,
        MessageStatus.SENT -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_check),
                contentDescription = "Accepted by Cloud Queue",
                tint = Color.Gray,
                modifier = Modifier.size(12.dp)
            )
        }
        MessageStatus.RELAYED -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_check),
                contentDescription = "Relayed via Custody Node",
                tint = Color.Gray,
                modifier = Modifier.size(12.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_mark_read),
                contentDescription = "Delivered",
                tint = Color.Gray,
                modifier = Modifier.size(13.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_mark_read),
                contentDescription = "Read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
        }
        MessageStatus.FAILED,
        MessageStatus.EXPIRED -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_delete),
                contentDescription = if (status == MessageStatus.EXPIRED) "Expired" else "Failed",
                tint = NexaWarningOrange,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
