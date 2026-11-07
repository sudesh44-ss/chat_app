package com.nexa.app.presentation.media_explorer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.nexa.app.presentation.common.viewer.ProfileImageViewerScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Media Explorer Screen
 * Supports 4 categories: Images, Videos, Documents, Other Files.
 * Follows NEXA Liquid Glass Design System.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaExplorerScreen(
    viewModel: MediaExplorerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val screenBg = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg),
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Media, Links & Docs",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp
                            ),
                            color = primaryTextColor
                        )
                        Text(
                            text = uiState.participantName,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = secondaryTextColor
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = primaryTextColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Category Selector Tabs Row
            CategorySelectorTabs(
                selectedCategory = uiState.selectedCategory,
                imagesCount = uiState.images.size,
                videosCount = uiState.videos.size,
                documentsCount = uiState.documents.size,
                otherFilesCount = uiState.otherFiles.size,
                onCategorySelected = { viewModel.selectCategory(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Tab Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
            ) {
                when (uiState.selectedCategory) {
                    MediaCategory.IMAGES -> {
                        ImagesCategoryContent(
                            images = uiState.images,
                            onImageClick = { viewModel.setActiveViewerImage(it) }
                        )
                    }
                    MediaCategory.VIDEOS -> {
                        VideosCategoryContent(
                            videos = uiState.videos,
                            onVideoClick = { video ->
                                Toast.makeText(context, "Playing ${video.fileName}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    MediaCategory.DOCUMENTS -> {
                        DocumentsCategoryContent(
                            documents = uiState.documents,
                            onDocumentClick = { doc ->
                                Toast.makeText(context, "Opening ${doc.fileName}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    MediaCategory.OTHER_FILES -> {
                        OtherFilesCategoryContent(
                            otherFiles = uiState.otherFiles,
                            onFileClick = { file ->
                                Toast.makeText(context, "Selected ${file.fileName}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        // Full-Screen Image Viewer Overlay
        uiState.activeViewerImage?.let { imageModel ->
            ProfileImageViewerScreen(
                imageModel = imageModel,
                onCloseClick = { viewModel.setActiveViewerImage(null) }
            )
        }
    }
}

@Composable
private fun CategorySelectorTabs(
    selectedCategory: MediaCategory,
    imagesCount: Int,
    videosCount: Int,
    documentsCount: Int,
    otherFilesCount: Int,
    onCategorySelected: (MediaCategory) -> Unit
) {
    val isDark = isSystemInDarkTheme()

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MediaCategory.values()) { category ->
            val isSelected = category == selectedCategory
            val count = when (category) {
                MediaCategory.IMAGES -> imagesCount
                MediaCategory.VIDEOS -> videosCount
                MediaCategory.DOCUMENTS -> documentsCount
                MediaCategory.OTHER_FILES -> otherFilesCount
            }

            val bg = when {
                isSelected && isDark -> Color(0xFF2979FF)
                isSelected && !isDark -> Color(0xFF1976D2)
                isDark -> Color.White.copy(alpha = 0.06f)
                else -> Color.Black.copy(alpha = 0.05f)
            }

            val textColor = when {
                isSelected -> Color.White
                isDark -> Color(0xFFA0A7B5)
                else -> Color(0xFF555B66)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .clickable { onCategorySelected(category) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        color = textColor
                    )
                    if (count > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "($count)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            ),
                            color = textColor.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagesCategoryContent(
    images: List<TransferEntity>,
    onImageClick: (Any) -> Unit
) {
    val isDark = isSystemInDarkTheme()

    if (images.isEmpty()) {
        EmptyCategoryPlaceholder(
            iconRes = R.drawable.ic_nexa_image,
            title = "No Images Shared",
            subtitle = "Images shared in this conversation will appear here."
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(images, key = { it.transferId }) { img ->
                val model = img.localUri.ifBlank { R.drawable.ic_nexa_image }
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            1.dp,
                            if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { onImageClick(model) }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(model)
                            .crossfade(true)
                            .build(),
                        contentDescription = img.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun VideosCategoryContent(
    videos: List<TransferEntity>,
    onVideoClick: (TransferEntity) -> Unit
) {
    val isDark = isSystemInDarkTheme()

    if (videos.isEmpty()) {
        EmptyCategoryPlaceholder(
            iconRes = R.drawable.ic_nexa_video,
            title = "No Videos Shared",
            subtitle = "Video files shared in this chat will appear here."
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(videos, key = { it.transferId }) { vid ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            1.dp,
                            if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f),
                            RoundedCornerShape(14.dp)
                        )
                        .background(if (isDark) Color(0xFF1E1E1E) else Color(0xFFEEEEEE))
                        .clickable { onVideoClick(vid) }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(vid.localUri.ifBlank { R.drawable.ic_nexa_video })
                            .crossfade(true)
                            .build(),
                        contentDescription = vid.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Play Badge Overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Video size indicator at bottom
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formatFileSize(vid.fileSizeBytes),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentsCategoryContent(
    documents: List<TransferEntity>,
    onDocumentClick: (TransferEntity) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    if (documents.isEmpty()) {
        EmptyCategoryPlaceholder(
            iconRes = R.drawable.ic_nexa_file,
            title = "No Documents Shared",
            subtitle = "PDFs, spreadsheets and documents will be cataloged here."
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(documents, key = { it.transferId }) { doc ->
                NexaGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDocumentClick(doc) },
                    shape = RoundedCornerShape(18.dp),
                    surfaceColor = surfaceColor,
                    borderColor = borderColor,
                    elevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0xFF1E3A8A) else Color(0xFFDBEAFE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_file),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF93C5FD) else Color(0xFF1D4ED8),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = doc.fileName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                ),
                                color = primaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${formatFileSize(doc.fileSizeBytes)} • ${formatDate(doc.timestamp)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = secondaryTextColor
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_download),
                            contentDescription = "Open",
                            tint = secondaryTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OtherFilesCategoryContent(
    otherFiles: List<TransferEntity>,
    onFileClick: (TransferEntity) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    if (otherFiles.isEmpty()) {
        EmptyCategoryPlaceholder(
            iconRes = R.drawable.ic_nexa_storage,
            title = "No Other Files Shared",
            subtitle = "Archives, audio files and generic downloads will appear here."
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(otherFiles, key = { it.transferId }) { file ->
                NexaGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFileClick(file) },
                    shape = RoundedCornerShape(18.dp),
                    surfaceColor = surfaceColor,
                    borderColor = borderColor,
                    elevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color(0xFF064E3B) else Color(0xFFD1FAE5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_storage),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.fileName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                ),
                                color = primaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${formatFileSize(file.fileSizeBytes)} • ${formatDate(file.timestamp)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = secondaryTextColor
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = secondaryTextColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCategoryPlaceholder(
    iconRes: Int,
    title: String,
    subtitle: String
) {
    val isDark = isSystemInDarkTheme()
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = secondaryTextColor,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            ),
            color = primaryTextColor
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = secondaryTextColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
        else -> "$bytes B"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
