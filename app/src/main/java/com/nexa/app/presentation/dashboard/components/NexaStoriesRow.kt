package com.nexa.app.presentation.dashboard.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexa.app.R
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaCyan80
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.domain.model.UserStatusGroup
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.glass.NexaGlassSurface

data class StoryItem(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val hasUnseenStory: Boolean = true
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NexaStoriesRow(
    myStatusGroup: UserStatusGroup? = null,
    otherStatusGroups: List<UserStatusGroup> = emptyList(),
    mutedStatusGroups: List<UserStatusGroup> = emptyList(),
    onMyStatusClick: () -> Unit = {},
    onAddMyStatusClick: () -> Unit = {},
    onStoryClick: (UserStatusGroup) -> Unit = {},
    onToggleStatusMute: (UserStatusGroup, Boolean) -> Unit = { _, _ -> },
    onViewProfile: (UserStatusGroup) -> Unit = {},
    onGoToChat: (UserStatusGroup) -> Unit = {},
    onOpenStatusPage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var statusForContextMenu by remember { mutableStateOf<UserStatusGroup?>(null) }
    val hasMyActiveStatus = myStatusGroup != null && myStatusGroup.statuses.isNotEmpty()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(onClick = onOpenStatusPage) {
                Text(
                    text = "View all",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val myRingBrush = if (hasMyActiveStatus) {
                Brush.sweepGradient(listOf(MaterialTheme.colorScheme.primary, NexaCyan80, NexaBadgeMonospace, MaterialTheme.colorScheme.primary))
            } else {
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)))
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            if (hasMyActiveStatus) onMyStatusClick() else onAddMyStatusClick()
                        },
                        onLongClick = {}
                    )
                    .width(64.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(if (hasMyActiveStatus) 2.dp else 1.dp, myRingBrush, CircleShape)
                        .padding(if (hasMyActiveStatus) 3.dp else 0.dp),
                    contentAlignment = Alignment.Center
                ) {
                    NexaAvatar(
                        nexaAvatarUrl = myStatusGroup?.avatarUrl,
                        deviceContactPhotoUri = null,
                        displayName = "You",
                        size = if (hasMyActiveStatus) 48.dp else 52.dp
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                            .combinedClickable(onClick = onAddMyStatusClick, onLongClick = {}),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_nexa_plus),
                            contentDescription = "Add Status Story",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "My Status",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = if (hasMyActiveStatus) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (hasMyActiveStatus) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            otherStatusGroups.forEach { group ->
                StatusBubble(
                    group = group,
                    muted = false,
                    onClick = { onStoryClick(group) },
                    onLongClick = { statusForContextMenu = group }
                )
            }
        }

        if (mutedStatusGroups.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            )
            Text(
                text = "Muted Updates",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                mutedStatusGroups.forEach { group ->
                    StatusBubble(
                        group = group,
                        muted = true,
                        onClick = { onStoryClick(group) },
                        onLongClick = { statusForContextMenu = group }
                    )
                }
            }
        }
    }

    statusForContextMenu?.let { group ->
        val isMuted = mutedStatusGroups.any { it.userNxId == group.userNxId }
        Dialog(
            onDismissRequest = { statusForContextMenu = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            NexaGlassSurface(
                modifier = Modifier
                    .padding(24.dp)
                    .width(300.dp),
                shape = RoundedCornerShape(NexaGlassTokens.ContextMenuRadius),
                elevation = 16.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = group.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ContextMenuItemRow(if (isMuted) "Unmute" else "Mute", R.drawable.ic_nexa_mute) {
                        statusForContextMenu = null
                        onToggleStatusMute(group, !isMuted)
                    }
                    ContextMenuItemRow("View Profile", R.drawable.ic_nexa_view_contact) {
                        statusForContextMenu = null
                        onViewProfile(group)
                    }
                    ContextMenuItemRow("Go to Chat", R.drawable.ic_nexa_chats) {
                        statusForContextMenu = null
                        onGoToChat(group)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusBubble(
    group: UserStatusGroup,
    muted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val ring = if (!muted && group.hasUnviewedStatus) {
        Brush.sweepGradient(listOf(NexaBadgeMonospace, NexaCyan80, MaterialTheme.colorScheme.primary, NexaBadgeMonospace))
    } else {
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)))
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .width(64.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(width = 2.dp, brush = ring, shape = CircleShape)
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            NexaAvatar(
                nexaAvatarUrl = group.avatarUrl,
                deviceContactPhotoUri = null,
                displayName = group.displayName,
                size = 48.dp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = group.displayName,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (!muted && group.hasUnviewedStatus) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (!muted && group.hasUnviewedStatus) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
