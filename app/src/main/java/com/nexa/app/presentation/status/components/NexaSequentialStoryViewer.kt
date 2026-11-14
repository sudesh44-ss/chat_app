package com.nexa.app.presentation.status.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaCyan80
import com.nexa.app.core.theme.NexaDarkBackground
import com.nexa.app.domain.model.StatusItem
import com.nexa.app.domain.model.UserStatusGroup
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val STORY_DURATION_MS = 5000L

/**
 * WhatsApp-style sequential story viewer with segmented progress bars,
 * auto-advance, tap navigation, press-to-pause, and per-status view tracking.
 */
@Composable
fun NexaSequentialStoryViewer(
    group: UserStatusGroup,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    onMarkViewed: (String) -> Unit,
    onDeleteStatus: (String) -> Unit = {},
    onShowViewers: (StatusItem) -> Unit = {}
) {
    if (group.statuses.isEmpty()) {
        onDismiss()
        return
    }

    var currentIndex by remember {
        mutableStateOf(initialIndex.coerceIn(0, group.statuses.lastIndex))
    }
    var isPaused by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    val currentStatus = group.statuses.getOrNull(currentIndex) ?: run {
        onDismiss()
        return
    }

    // Mark current status as viewed immediately upon presentation
    LaunchedEffect(currentStatus.statusId) {
        onMarkViewed(currentStatus.statusId)
    }

    // Step timer for the active status: 50ms increments
    LaunchedEffect(currentIndex, isPaused) {
        progress = 0f
        val stepMs = 50L
        val totalSteps = STORY_DURATION_MS / stepMs
        var step = 0

        while (step < totalSteps) {
            if (!isPaused) {
                step++
                progress = step.toFloat() / totalSteps.toFloat()
            }
            delay(stepMs)
        }

        // Auto-advance upon timer completion
        if (currentIndex < group.statuses.lastIndex) {
            currentIndex++
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(currentIndex) {
                    detectTapGestures(
                        onPress = {
                            isPaused = true
                            tryAwaitRelease()
                            isPaused = false
                        },
                        onTap = { offset ->
                            val width = size.width
                            if (offset.x < width * 0.3f) {
                                // Tap Left: Previous status
                                if (currentIndex > 0) {
                                    currentIndex--
                                } else {
                                    progress = 0f
                                }
                            } else {
                                // Tap Right: Next status
                                if (currentIndex < group.statuses.lastIndex) {
                                    currentIndex++
                                } else {
                                    onDismiss()
                                }
                            }
                        }
                    )
                }
        ) {
            // 1. Status Content Body (Text vs Media)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (currentStatus.statusType) {
                    "TEXT" -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            NexaDarkBackground,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                            NexaDarkBackground
                                        )
                                    )
                                )
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentStatus.caption,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 36.sp
                                ),
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    "IMAGE" -> {
                        AsyncImage(
                            model = currentStatus.mediaReference,
                            contentDescription = currentStatus.caption,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Bottom caption pill
                        if (currentStatus.caption.isNotBlank()) {
                            NexaGlassSurface(
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(start = 20.dp, end = 20.dp, bottom = 48.dp)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    text = currentStatus.caption,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        // VIDEO / Fallback
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = currentStatus.mediaReference,
                                contentDescription = currentStatus.caption,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (currentStatus.caption.isNotBlank()) {
                                NexaGlassSurface(
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(start = 20.dp, end = 20.dp, bottom = 48.dp)
                                        .fillMaxWidth()
                                ) {
                                    Text(
                                        text = currentStatus.caption,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Top Overlay: Segmented Progress Bars & Author Details
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Segmented Progress Bar (1 segment per status in group)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    group.statuses.forEachIndexed { index, _ ->
                        val segmentProgress = when {
                            index < currentIndex -> 1f
                            index == currentIndex -> progress
                            else -> 0f
                        }

                        LinearProgressIndicator(
                            progress = { segmentProgress },
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Header Row: Avatar, Name, Timestamp, Actions & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NexaAvatar(
                        nexaAvatarUrl = group.avatarUrl,
                        deviceContactPhotoUri = null,
                        displayName = group.displayName,
                        size = 38.dp
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatStatusTimestamp(currentStatus.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }

                    // For current user: Show View Count & Delete actions
                    if (group.isCurrentUser) {
                        IconButton(onClick = { onShowViewers(currentStatus) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = "Viewers",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                if (currentStatus.viewCount > 0) {
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "${currentStatus.viewCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { onDeleteStatus(currentStatus.statusId) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Status",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Close Button
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Story",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun formatStatusTimestamp(timestamp: Long): String {
    val diffMs = System.currentTimeMillis() - timestamp
    val minutes = diffMs / 60000L
    val hours = minutes / 60L

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> {
            val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
