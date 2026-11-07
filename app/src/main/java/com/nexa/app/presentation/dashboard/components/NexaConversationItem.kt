package com.nexa.app.presentation.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NexaConversationItem(
    chat: ChatConversation,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPhotoClick: ((Any) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isGroup = chat.conversationId.contains("group", ignoreCase = true) ||
        chat.participantNxId.contains("GROUP", ignoreCase = true)
    val isDemo = chat.participantNxId.startsWith("NX-DEMO") || chat.conversationId.startsWith("demo_")

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(NexaGlassTokens.LargeCardRadius),
        elevation = if (isSelected) 8.dp else 3.dp,
        isSelected = isSelected
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NexaGlassTokens.CardHorizontalPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Multi-Selection Checkbox Indicator
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_check),
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Avatar (Group Icon or Contact Avatar)
            Box {
                if (isGroup) {
                    Box(
                        modifier = Modifier
                            .size(NexaGlassTokens.AvatarSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_groups),
                            contentDescription = "Group",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    NexaAvatar(
                        nexaAvatarUrl = null,
                        deviceContactPhotoUri = null,
                        displayName = chat.participantName,
                        size = NexaGlassTokens.AvatarSize,
                        onPhotoClick = onPhotoClick
                    )
                }

                // Online green dot indicator for active direct contacts
                if (!isGroup) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(NexaVerifiedGreen)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Main Info Column
            Column(modifier = Modifier.weight(1f)) {
                // Title Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = chat.participantName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (isPinned) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_pin),
                                contentDescription = "Pinned",
                                tint = NexaBadgeMonospace,
                                modifier = Modifier.size(13.dp)
                            )
                        }

                        if (isMuted) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_mute),
                                contentDescription = "Muted",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (isDemo && !isGroup) {
                        // Green "Online" badge exactly as in the reference design
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(androidx.compose.ui.graphics.Color(0xFF00C853).copy(alpha = 0.12f))
                                .border(1.dp, androidx.compose.ui.graphics.Color(0xFF00C853).copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 9.dp, vertical = 2.5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Online",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    color = androidx.compose.ui.graphics.Color(0xFF00E676)
                                )
                            )
                        }
                    } else {
                        Text(
                            text = formatConversationTimestamp(chat.lastMessageTimestamp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isDemo) {
                    Spacer(modifier = Modifier.height(2.dp))

                    // Testing Subtitle Row: "Personal conversation · Testing" or "Group conversation · Testing"
                    Text(
                        text = if (isGroup) "Group conversation · Testing" else "Personal conversation · Testing",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Snippet Row: Last message snippet + Unread count badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chat.lastMessageText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = if (isDemo && isGroup) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isDemo && isGroup) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (chat.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = chat.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatConversationTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
