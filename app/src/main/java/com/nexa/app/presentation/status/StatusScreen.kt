package com.nexa.app.presentation.status

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.domain.repository.StatusRepository
import com.nexa.app.domain.model.StatusItem
import com.nexa.app.domain.model.UserStatusGroup
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.dashboard.components.BottomNavDestination
import com.nexa.app.presentation.dashboard.components.NexaFloatingBottomNavigation
import com.nexa.app.presentation.status.components.CreateStatusDialog
import com.nexa.app.presentation.status.components.NexaSequentialStoryViewer
import com.nexa.app.presentation.status.components.StatusViewersSheet

@Composable
fun StatusScreen(
    authRepository: AuthRepository,
    statusRepository: StatusRepository,
    contactRepository: ContactRepository? = null,
    onNavigateHome: () -> Unit,
    onNavigateProfile: () -> Unit,
    onBack: () -> Unit = {}
) {
    val viewModel: StatusViewModel = viewModel(factory = StatusViewModel.Factory(statusRepository))
    val myStatusGroup by viewModel.myStatusGroup.collectAsState(initial = null)
    val otherStatusGroups by viewModel.otherStatusGroups.collectAsState(initial = emptyList())
    val mutedStatusGroups by viewModel.mutedStatusGroups.collectAsState(initial = emptyList())
    val selectedStatusGroup by viewModel.selectedStatusGroup.collectAsState(initial = null)
    val currentStatusIndex by viewModel.currentStatusIndex.collectAsState(initial = 0)
    val showCreateDialog by viewModel.showCreateStatusDialog.collectAsState(initial = false)
    val activeViewersStatus by viewModel.activeViewersStatus.collectAsState(initial = null)
    val statusViewersList by viewModel.statusViewersList.collectAsState(initial = emptyList())

    val recentUpdates = otherStatusGroups
        .filter { it.hasUnviewedStatus }
        .sortedByDescending { it.latestTimestamp }
    val viewedUpdates = otherStatusGroups
        .filter { !it.hasUnviewedStatus }
        .sortedByDescending { it.latestTimestamp }

    var showAddOptions by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var textBackground by remember { mutableStateOf("Classic") }
    var textColor by remember { mutableStateOf("White") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var showTextComposer by remember { mutableStateOf(false) }
    var showMusicUnavailable by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        selectedImageUri = uri
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        selectedVideoUri = uri
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val uri = bitmapToUri(bitmap, context)
            if (uri != null) {
                selectedImageUri = uri
            }
        }
    }

    Scaffold(
        topBar = {
            StatusTopBar(
                onBack = onBack,
                onAddStatus = { showAddOptions = true },
                onHome = onNavigateHome,
                onProfile = onNavigateProfile
            )
        },
        bottomBar = {
            NexaFloatingBottomNavigation(
                currentDestination = BottomNavDestination.HOME,
                isPlusMenuOpen = false,
                showPlusButton = true,
                onNavigateHome = onNavigateHome,
                onNavigateProfile = onNavigateProfile,
                onTogglePlusMenu = { showAddOptions = true }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    MyStatusCard(
                        myStatusGroup = myStatusGroup,
                        onAddStatus = { showAddOptions = true },
                        onOpenMyStatus = {
                            myStatusGroup?.let { viewModel.openStatusGroup(it) }
                        }
                    )
                }

                item {
                    SectionHeader(title = "Recent Updates")
                }
                if (recentUpdates.isEmpty()) {
                    item {
                        EmptySectionCard("No new status updates")
                    }
                } else {
                    items(recentUpdates, key = { it.userNxId }) { group ->
                        StatusRowItem(
                            group = group,
                            label = "New",
                            onClick = { viewModel.openStatusGroup(group) }
                        )
                    }
                }

                item {
                    SectionHeader(title = "Viewed Updates")
                }
                if (viewedUpdates.isEmpty()) {
                    item {
                        EmptySectionCard("No viewed status updates")
                    }
                } else {
                    items(viewedUpdates, key = { it.userNxId }) { group ->
                        StatusRowItem(
                            group = group,
                            label = "Seen",
                            onClick = { viewModel.openStatusGroup(group) }
                        )
                    }
                }

                if (mutedStatusGroups.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Muted Updates")
                    }
                    items(mutedStatusGroups, key = { it.userNxId }) { group ->
                        StatusRowItem(
                            group = group,
                            label = "Muted",
                            onClick = { viewModel.openStatusGroup(group) }
                        )
                    }
                }
            }

            if (showAddOptions) {
                StatusCreateSheet(
                    onDismiss = { showAddOptions = false },
                    onCamera = {
                        showAddOptions = false
                        cameraLauncher.launch(null)
                    },
                    onGallery = {
                        showAddOptions = false
                        imagePicker.launch("image/*")
                    },
                    onText = {
                        showAddOptions = false
                        showTextComposer = true
                    },
                    onMusic = {
                        showAddOptions = false
                        showMusicUnavailable = true
                    },
                    onVideo = {
                        showAddOptions = false
                        videoPicker.launch("video/*")
                    }
                )
            }

            if (showTextComposer) {
                TextStatusComposerSheet(
                    textInput = textInput,
                    onTextChange = { textInput = it },
                    textBackground = textBackground,
                    textColor = textColor,
                    onBackgroundChange = { textBackground = it },
                    onColorChange = { textColor = it },
                    onDismiss = { showTextComposer = false },
                    onPost = {
                        if (textInput.isNotBlank()) {
                            viewModel.createTextStatus(textInput.trim(), "CONTACTS")
                            textInput = ""
                            showTextComposer = false
                        }
                    }
                )
            }

            if (selectedImageUri != null) {
                StatusMediaPreviewSheet(
                    uri = selectedImageUri,
                    onDismiss = { selectedImageUri = null },
                    onPost = {
                        viewModel.createMediaStatus(selectedImageUri.toString(), "", "IMAGE", "CONTACTS")
                        selectedImageUri = null
                    }
                )
            }

            if (selectedVideoUri != null) {
                StatusMediaPreviewSheet(
                    uri = selectedVideoUri,
                    onDismiss = { selectedVideoUri = null },
                    onPost = {
                        viewModel.createMediaStatus(selectedVideoUri.toString(), "", "VIDEO", "CONTACTS")
                        selectedVideoUri = null
                    },
                    isVideo = true
                )
            }

            selectedStatusGroup?.let { group ->
                val status = group.statuses.getOrNull(currentStatusIndex)
                if (status != null) {
                    NexaSequentialStoryViewer(
                        group = group,
                        initialIndex = currentStatusIndex,
                        onDismiss = { viewModel.closeStatusGroup() },
                        onMarkViewed = { id -> viewModel.markStatusViewed(id) },
                        onDeleteStatus = { id -> viewModel.deleteStatus(id) },
                        onShowViewers = { item -> viewModel.showStatusViewers(item) }
                    )
                }
            }

            activeViewersStatus?.let { status ->
                StatusViewersSheet(
                    status = status,
                    viewers = statusViewersList,
                    onDismiss = { viewModel.dismissStatusViewers() }
                )
            }

            if (showMusicUnavailable) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showMusicUnavailable = false },
                    title = { Text("Music status unavailable") },
                    text = {
                        Text("This build does not support posting music as a status item yet. The current status media architecture only supports text, image, and video status updates.")
                    },
                    confirmButton = {
                        TextButton(onClick = { showMusicUnavailable = false }) {
                            Text("OK")
                        }
                    }
                )
            }

            if (showCreateDialog) {
                CreateStatusDialog(
                    onDismiss = { viewModel.dismissCreateDialog() },
                    onCreateTextStatus = { caption -> viewModel.createTextStatus(caption) },
                    onCreateMediaStatus = { mediaUri, caption, statusType ->
                        viewModel.createMediaStatus(mediaUri, caption, statusType)
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusTopBar(
    onBack: () -> Unit,
    onAddStatus: () -> Unit,
    onHome: () -> Unit,
    onProfile: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Back")
            }
            Text(
                text = "Status",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAddStatus) {
                Text("Add")
            }
        }
    }
}

@Composable
private fun MyStatusCard(
    myStatusGroup: UserStatusGroup?,
    onAddStatus: () -> Unit,
    onOpenMyStatus: () -> Unit
) {
    val hasStatus = myStatusGroup != null && myStatusGroup.statuses.isNotEmpty()
    val statusCount = myStatusGroup?.statuses?.size ?: 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onOpenMyStatus() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (hasStatus) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            NexaAvatar(
                nexaAvatarUrl = myStatusGroup?.avatarUrl,
                deviceContactPhotoUri = null,
                displayName = "My Status",
                size = 54.dp
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "My Status",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = if (hasStatus) "$statusCount active update${if (statusCount == 1) "" else "s"}" else "No status yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TextButton(onClick = onAddStatus) {
            Text("Add Status")
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun EmptySectionCard(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusRowItem(
    group: UserStatusGroup,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            NexaAvatar(
                nexaAvatarUrl = group.avatarUrl,
                deviceContactPhotoUri = null,
                displayName = group.displayName,
                size = 46.dp
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = "${group.statuses.size} update${if (group.statuses.size == 1) "" else "s"} • ${label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (label == "New") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusCreateSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onText: () -> Unit,
    onMusic: () -> Unit,
    onVideo: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Create a status",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            StatusOptionRow("Camera", Icons.Default.CameraAlt, onCamera)
            StatusOptionRow("Gallery", Icons.Default.Image, onGallery)
            StatusOptionRow("Text", Icons.Default.TextFields, onText)
            StatusOptionRow("Music", Icons.Default.MusicNote, onMusic)
            StatusOptionRow("Video", Icons.Default.VideoLibrary, onVideo)
        }
    }
}

@Composable
private fun StatusOptionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextStatusComposerSheet(
    textInput: String,
    onTextChange: (String) -> Unit,
    textBackground: String,
    textColor: String,
    onBackgroundChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPost: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Text Status",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            OutlinedTextField(
                value = textInput,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 6
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Classic", "Soft", "Dark").forEach { option ->
                    TextButton(
                        onClick = { onBackgroundChange(option) },
                        modifier = Modifier
                            .background(
                                if (textBackground == option) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        Text(option)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("White", "Black", "Cyan").forEach { option ->
                    TextButton(
                        onClick = { onColorChange(option) },
                        modifier = Modifier
                            .background(
                                if (textColor == option) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        Text(option)
                    }
                }
            }
            Button(
                onClick = onPost,
                enabled = textInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Post status")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusMediaPreviewSheet(
    uri: Uri?,
    onDismiss: () -> Unit,
    onPost: () -> Unit,
    isVideo: Boolean = false
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (isVideo) "Video preview" else "Image preview",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Button(onClick = onPost, modifier = Modifier.fillMaxWidth()) {
                Text("Post status")
            }
        }
    }
}

private fun bitmapToUri(bitmap: android.graphics.Bitmap, context: android.content.Context): Uri? {
    return try {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        val file = java.io.File.createTempFile("nexa_status_", ".png", context.cacheDir)
        java.io.FileOutputStream(file).use { output ->
            output.write(stream.toByteArray())
        }
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    } catch (_: Exception) {
        null
    }
}
