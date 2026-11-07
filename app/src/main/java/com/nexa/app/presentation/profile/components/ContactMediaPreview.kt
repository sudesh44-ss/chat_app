package com.nexa.app.presentation.profile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nexa.app.R
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Horizontal Media, Links & Docs Preview Card
 *
 * Implements:
 * - Header with section title, item count badge, and Chevron
 * - Horizontally scrollable row of thumbnails (14dp rounded corners)
 * - Image thumbnail, Video thumbnail with play indicator, Document badge preview
 * - Empty state when no media exists in conversation
 * - Click navigates to Media Explorer
 */
@Composable
fun ContactMediaPreview(
    mediaItems: List<TransferEntity>,
    onOpenMediaExplorer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        surfaceColor = surfaceColor,
        borderColor = borderColor,
        elevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
        ) {
            // Header Row (Clickable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMediaExplorer() }
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Media, Links & Docs",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = primaryTextColor
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (mediaItems.isNotEmpty()) {
                    Text(
                        text = "${mediaItems.size}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = secondaryTextColor
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = "View All",
                    tint = secondaryTextColor,
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (mediaItems.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color.White.copy(alpha = 0.03f) else Color.Black.copy(alpha = 0.02f))
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No shared media, links or documents yet",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = secondaryTextColor
                    )
                }
            } else {
                // Horizontal Thumbnail List
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(mediaItems.take(12), key = { it.transferId }) { item ->
                        MediaThumbnailItem(
                            item = item,
                            onClick = onOpenMediaExplorer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnailItem(
    item: TransferEntity,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val thumbnailBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val ext = item.fileName.substringAfterLast('.', "").lowercase()

    val isVideo = ext in listOf("mp4", "mkv", "webm", "avi", "mov", "3gp")
    val isDocument = ext in listOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx")
    val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "heic")

    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, thumbnailBorder, RoundedCornerShape(14.dp))
            .background(if (isDark) Color(0xFF1E1E1E) else Color(0xFFEBEBEB))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when {
            isImage -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.localUri.ifBlank { R.drawable.ic_nexa_image })
                        .crossfade(true)
                        .build(),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            isVideo -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.localUri.ifBlank { R.drawable.ic_nexa_video })
                            .crossfade(true)
                            .build(),
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Play icon overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            isDocument -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_file),
                        contentDescription = "Document",
                        tint = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2),
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ext.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (isDark) Color.White else Color.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_storage),
                        contentDescription = "File",
                        tint = if (isDark) Color(0xFF81C784) else Color(0xFF388E3C),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ext.uppercase().take(4),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (isDark) Color.White else Color.Black
                    )
                }
            }
        }
    }
}
