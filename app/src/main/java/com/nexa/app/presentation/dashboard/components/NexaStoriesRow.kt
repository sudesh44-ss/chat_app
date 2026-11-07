package com.nexa.app.presentation.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaCyan80
import com.nexa.app.presentation.common.avatar.NexaAvatar

data class StoryItem(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val hasUnseenStory: Boolean = true
)

@Composable
fun NexaStoriesRow(
    onMyStatusClick: () -> Unit = {},
    onStoryClick: (StoryItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Stories: My Status + Active peer status feeds
    val demoStories = listOf(
        StoryItem(id = "1", name = "Alex", hasUnseenStory = true),
        StoryItem(id = "2", name = "Sam", hasUnseenStory = true),
        StoryItem(id = "3", name = "Priya", hasUnseenStory = false),
        StoryItem(id = "4", name = "Elena", hasUnseenStory = true),
        StoryItem(id = "5", name = "Marcus", hasUnseenStory = false)
    )

    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // My Status Item (+ button)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clickable(onClick = onMyStatusClick)
                .width(64.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                NexaAvatar(
                    nexaAvatarUrl = null,
                    deviceContactPhotoUri = null,
                    displayName = "You",
                    size = 52.dp
                )

                // Plus badge overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_plus),
                        contentDescription = "Add Story",
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
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Contact Stories
        demoStories.forEach { story ->
            val ringBrush = if (story.hasUnseenStory) {
                Brush.sweepGradient(
                    listOf(
                        NexaBadgeMonospace,
                        NexaCyan80,
                        MaterialTheme.colorScheme.primary,
                        NexaBadgeMonospace
                    )
                )
            } else {
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onStoryClick(story) }
                    .width(64.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(width = 2.dp, brush = ringBrush, shape = CircleShape)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    NexaAvatar(
                        nexaAvatarUrl = story.avatarUrl,
                        deviceContactPhotoUri = null,
                        displayName = story.name,
                        size = 48.dp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = story.name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = if (story.hasUnseenStory) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (story.hasUnseenStory) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
