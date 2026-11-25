package com.nexa.app.presentation.backup

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.backup.BackupPasswordValidator
import com.nexa.app.core.backup.PasswordStrength
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.local.preferences.BackupFrequency
import com.nexa.app.domain.model.BackupCategory
import com.nexa.app.domain.model.BackupMode
import com.nexa.app.domain.model.BackupRestorePoint
import com.nexa.app.presentation.backup.components.BackupProgressCard
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Production Backup & Restore Settings Screen following the NEXA Liquid Glass Design System.
 * Enforces the exact 12-section approved hierarchy:
 * 1. Google Drive Account
 * 2. Backup Overview
 * 3. Back Up Now / Restore
 * 4. Backup Progress (Conditional — active operations only)
 * 5. Backup Categories (Exact 7 categories)
 * 6. Backup Mode (Scheduled, Chat Priority, Manual Only)
 * 7. Automatic Backup Schedule
 * 8. Backup Conditions
 * 9. File Backup Preferences
 * 10. Chat Backup Preferences
 * 11. Backup History
 * 12. Backup Password
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    viewModel: BackupViewModel,
    onNavigateBack: () -> Unit,
    onNavigateRestore: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val backupState by viewModel.backupResource.collectAsState()
    val prefsState by viewModel.preferencesState.collectAsState()
    val isAuthorized by viewModel.isAuthorized.collectAsState()
    val recoveryPassphrase by viewModel.recoveryPassphrase.collectAsState()
    val isPassphraseRevealed by viewModel.isPassphraseRevealed.collectAsState()
    val categories by viewModel.categoryConfig.collectAsState()
    val estimatedSize by viewModel.estimatedSize.collectAsState()
    val remoteFileState by viewModel.remoteBackupFile.collectAsState()
    val restorePointsState by viewModel.restorePoints.collectAsState()
    val managementState by viewModel.managementResource.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    val hasCustomPassword by viewModel.hasCustomPassword.collectAsState()

    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showDeleteBackupDialog by remember { mutableStateOf(false) }
    var targetDeleteFileId by remember { mutableStateOf<String?>(null) }
    var showPrioritySelection by remember { mutableStateOf(false) }
    var showSetPasswordDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showRemovePasswordDialog by remember { mutableStateOf(false) }

    if (showPrioritySelection) {
        PriorityChatsSelectionScreen(
            viewModel = viewModel,
            onNavigateBack = { showPrioritySelection = false }
        )
        return
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAuthState()
        viewModel.scanRemoteBackup()
        viewModel.fetchRestorePoints()
    }

    LaunchedEffect(isAuthorized) {
        if (isAuthorized) {
            viewModel.scanRemoteBackup()
            viewModel.fetchRestorePoints()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // =========================================================================
            // 1. GOOGLE DRIVE ACCOUNT
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isAuthorized) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (isAuthorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Google Drive Account",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            val maskedEmail = prefsState.googleAccountEmail?.let { email ->
                                val atIdx = email.indexOf('@')
                                if (atIdx > 2) {
                                    val name = email.substring(0, atIdx)
                                    val domain = email.substring(atIdx)
                                    "${name.take(2)}***$domain"
                                } else email
                            }
                            Text(
                                text = if (isAuthorized) maskedEmail ?: "Connected" else "Account Not Linked",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isAuthorized) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                            )
                        }
                        if (isAuthorized) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "E2EE Sealed",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Account Actions
                    if (!isAuthorized) {
                        Button(
                            onClick = {
                                val app = context.applicationContext as com.nexa.app.NexaApplication
                                googleSignInLauncher.launch(app.googleDriveAuthManager.getSignInIntent())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect Google Account", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val app = context.applicationContext as com.nexa.app.NexaApplication
                                    googleSignInLauncher.launch(app.googleDriveAuthManager.getSignInIntent())
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Switch", fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = { showDisconnectDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Disconnect", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 2. BACKUP OVERVIEW
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Backup Overview",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        val statusLabel = when {
                            backupState is DriveResource.Progress -> "Backing Up..."
                            prefsState.lastBackupTimestamp > 0L -> "Verified & Sealed"
                            else -> "No Backups Yet"
                        }
                        val statusColor = when {
                            backupState is DriveResource.Progress -> MaterialTheme.colorScheme.tertiary
                            prefsState.lastBackupTimestamp > 0L -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(statusColor.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = statusColor
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Last Successful Backup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatTimestamp(prefsState.lastBackupTimestamp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text("Estimated Vault Size", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatFileSize(if (estimatedSize > 0) estimatedSize else prefsState.lastBackupSize),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Next Scheduled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = viewModel.getNextScheduledBackupTimeFormatted(),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text("Active Mode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val modeStr = when (prefsState.backupMode) {
                                BackupMode.SCHEDULED -> "Scheduled"
                                BackupMode.CHAT_PRIORITY -> "Chat Priority"
                                BackupMode.MANUAL_ONLY -> "Manual Only"
                            }
                            Text(
                                text = modeStr,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 3. BACK UP NOW / RESTORE
            // =========================================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.triggerBackup() },
                    enabled = isAuthorized && backupState !is DriveResource.Progress,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Back Up Now", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onNavigateRestore,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore", fontWeight = FontWeight.SemiBold)
                }
            }

            // =========================================================================
            // 4. BACKUP PROGRESS — ONLY WHEN AN OPERATION IS ACTIVE
            // =========================================================================
            when (val state = backupState) {
                is DriveResource.Progress -> {
                    BackupProgressCard(
                        phase = state.phase,
                        percent = state.percent,
                        onCancel = { viewModel.cancelActiveBackup() }
                    )
                }
                is DriveResource.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Backup Issue", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            TextButton(onClick = { viewModel.resetBackupResource() }) {
                                Text("Dismiss", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                is DriveResource.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Cloud backup verified & sealed successfully!",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                else -> Unit
            }

            // =========================================================================
            // 5. BACKUP CATEGORIES — EXACTLY 7
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Backup Categories",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Select data to include in your vault",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.selectAllCategories() }) {
                                Text("All", fontSize = 12.sp)
                            }
                            TextButton(onClick = { viewModel.deselectAllCategories() }) {
                                Text("None", fontSize = 12.sp)
                            }
                        }
                    }

                    if (!categories.hasAnySelected()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "All 7 categories disabled. Backups are paused until at least one category is selected.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // 1. Chats & Conversations
                    CategoryRow(
                        title = "Chats & Conversations",
                        description = "Personal & group conversations, messages, contacts, and metadata",
                        iconRes = R.drawable.ic_nexa_chats,
                        checked = categories.includeChats,
                        onCheckedChange = {
                            viewModel.updateCategory { it.withCategory(BackupCategory.CHATS, !categories.includeChats) }
                        }
                    )

                    // 2. Images & Photos
                    CategoryRow(
                        title = "Images & Photos",
                        description = "Photos, camera images, graphics, and screenshots",
                        iconRes = R.drawable.ic_nexa_image,
                        checked = categories.includeImages,
                        onCheckedChange = {
                            viewModel.updateCategory { it.withCategory(BackupCategory.IMAGES, !categories.includeImages) }
                        }
                    )

                    // 3. Videos
                    CategoryRow(
                        title = "Videos",
                        description = "Video recordings, clips, and video attachments",
                        iconRes = R.drawable.ic_nexa_video,
                        checked = categories.includeVideos,
                        onCheckedChange = {
                            viewModel.updateCategory { it.withCategory(BackupCategory.VIDEOS, !categories.includeVideos) }
                        }
                    )

                    // 4. Documents
                    CategoryRow(
                        title = "Documents",
                        description = "PDF, Office documents, text files, and spreadsheets",
                        iconRes = R.drawable.ic_nexa_file,
                        checked = categories.includeDocuments,
                        onCheckedChange = {
                            viewModel.updateCategory { it.withCategory(BackupCategory.DOCUMENTS, !categories.includeDocuments) }
                        }
                    )

                    // 5. Audio & Voice Notes
                    CategoryRow(
                        title = "Audio & Voice Notes",
                        description = "Voice messages, audio notes, and sound recordings",
                        iconRes = R.drawable.ic_nexa_audio_lines,
                        checked = categories.includeAudio,
                        onCheckedChange = {
                            viewModel.updateCategory { it.withCategory(BackupCategory.AUDIO, !categories.includeAudio) }
                        }
                    )

                    // 6. Other Files
                    CategoryRow(
                        title = "Other Files",
                        description = "Archives, downloads, APKs, and miscellaneous attachments",
                        iconRes = R.drawable.ic_nexa_storage,
                        checked = categories.includeOtherFiles,
                        onCheckedChange = {
                            viewModel.updateCategory { it.withCategory(BackupCategory.OTHER_FILES, !categories.includeOtherFiles) }
                        }
                    )

                    // 7. Settings & App Data
                    CategoryRow(
                        title = "Settings & App Data",
                        description = "Bookmarks, saved messages, preferences, themes, and chat layouts",
                        iconRes = R.drawable.ic_nexa_settings,
                        checked = categories.includeSettings,
                        onCheckedChange = {
                            viewModel.updateCategory { it.withCategory(BackupCategory.SETTINGS, !categories.includeSettings) }
                        }
                    )
                }
            }

            // =========================================================================
            // 6. BACKUP MODE (EXACTLY 3 MODES)
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Backup Mode",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackupMode.values().forEach { mode ->
                            FilterChip(
                                selected = prefsState.backupMode == mode,
                                onClick = { viewModel.setBackupMode(mode) },
                                label = {
                                    val label = when (mode) {
                                        BackupMode.SCHEDULED -> "Scheduled"
                                        BackupMode.CHAT_PRIORITY -> "Chat Priority"
                                        BackupMode.MANUAL_ONLY -> "Manual Only"
                                    }
                                    Text(label)
                                }
                            )
                        }
                    }

                    when (prefsState.backupMode) {
                        BackupMode.CHAT_PRIORITY -> {
                            Text(
                                text = "Priority conversations and their messages are archived first before standard conversations. All other conversations remain eligible according to global category selections.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val priorityCount by viewModel.priorityChatCount.collectAsState(initial = 0)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$priorityCount conversations prioritized",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedButton(onClick = { showPrioritySelection = true }) {
                                    Text("Select Priority Chats")
                                }
                            }
                        }
                        BackupMode.SCHEDULED -> {
                            Text(
                                text = "Standard automated backup processing based on the schedule configured below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BackupMode.MANUAL_ONLY -> {
                            Text(
                                text = "Backups run only when you explicitly tap 'Back Up Now'. WorkManager automatic scheduling is disabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 7. AUTOMATIC BACKUP SCHEDULE
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Automatic Backup Schedule",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic Cloud Backup", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                            Text("Executed automatically via Android WorkManager", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefsState.autoBackupEnabled && prefsState.backupMode != BackupMode.MANUAL_ONLY,
                            onCheckedChange = { viewModel.setAutoBackupEnabled(it) }
                        )
                    }

                    if (prefsState.autoBackupEnabled && prefsState.backupMode != BackupMode.MANUAL_ONLY) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Next Scheduled Backup",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = viewModel.getNextScheduledBackupTimeFormatted(),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Frequency", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BackupFrequency.values().forEach { freq ->
                                    FilterChip(
                                        selected = prefsState.backupFrequency == freq,
                                        onClick = { viewModel.setFrequency(freq) },
                                        label = { Text(freq.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                    )
                                }
                            }
                        }

                        if (prefsState.backupFrequency == BackupFrequency.WEEKLY) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Day of Week", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val days = listOf("Mon" to 1, "Tue" to 2, "Wed" to 3, "Thu" to 4, "Fri" to 5, "Sat" to 6, "Sun" to 7)
                                    days.forEach { (name, dayNum) ->
                                        FilterChip(
                                            selected = prefsState.scheduledWeeklyDayOfWeek == dayNum,
                                            onClick = { viewModel.setWeeklyScheduleDay(dayNum) },
                                            label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }

                        if (prefsState.backupFrequency == BackupFrequency.MONTHLY) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Day of Month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val dates = listOf("1st" to 1, "15th" to 15, "28th" to 28)
                                    dates.forEach { (label, dateNum) ->
                                        FilterChip(
                                            selected = prefsState.scheduledMonthlyDayOfMonth == dateNum,
                                            onClick = { viewModel.setMonthlyScheduleDate(dateNum) },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Preferred Time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val timePresets = listOf(
                                    "02:00 AM (Default)" to Pair(2, 0),
                                    "04:00 AM" to Pair(4, 0),
                                    "12:00 AM" to Pair(0, 0)
                                )
                                timePresets.forEach { (label, time) ->
                                    FilterChip(
                                        selected = prefsState.scheduledDailyHour == time.first && prefsState.scheduledDailyMinute == time.second,
                                        onClick = { viewModel.setDailyScheduleTime(time.first, time.second) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 8. BACKUP CONDITIONS
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column {
                        Text(
                            text = "Backup Conditions",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Network and power restrictions for automated backups",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Back up over Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                            Text("Prevent mobile data consumption", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefsState.backupOverWifiOnly,
                            onCheckedChange = { viewModel.setWifiOnly(it) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Back up only while charging", style = MaterialTheme.typography.bodyMedium)
                            Text("Preserve device battery life", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefsState.backupWhenCharging,
                            onCheckedChange = { viewModel.setChargingOnly(it) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Backup Notifications", style = MaterialTheme.typography.bodyMedium)
                            Text("Notify on completion or errors (does not disable backups)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefsState.backupNotificationsEnabled,
                            onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                        )
                    }
                }
            }

            // =========================================================================
            // 9. FILE BACKUP PREFERENCES
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text(
                            text = "File Backup Preferences",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Priority queue ordering and media handling",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Priority Queue Tiers
                    QueueTierRow("Tier 1 — Highest", "Messages & Conversations", "Direct packaging; never blocked by media")
                    QueueTierRow("Tier 2 — High", "Documents & Voice Notes", "Small files (< 5 MB), PDFs, and audio clips")
                    QueueTierRow("Tier 3 — Medium", "Images & Photos", "Photos, screenshots, and visual media")
                    QueueTierRow("Tier 4 — Low", "Videos & Large Files", "Videos (> 20 MB) staged second to prevent starvation")

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "Starvation Prevention: Text messages and database snapshots are prioritized in separate queues so large media uploads never delay conversation history restoration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // =========================================================================
            // 10. CHAT BACKUP PREFERENCES
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val priorityCountAll by viewModel.priorityChatCount.collectAsState(initial = 0)
                    val excludedCountAll by viewModel.excludedChatCount.collectAsState(initial = 0)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Chat Backup Preferences",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "$priorityCountAll prioritized • $excludedCountAll excluded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedButton(
                            onClick = { showPrioritySelection = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Manage Policies", fontSize = 12.sp)
                        }
                    }

                    Text(
                        text = "Configure custom backup policies per conversation. Chat Priority ensures designated VIP chats are archived first without excluding non-priority chats from the backup package.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // =========================================================================
            // 11. BACKUP HISTORY
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Backup History",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Cloud restore points (up to 7 retained)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            viewModel.scanRemoteBackup()
                            viewModel.fetchRestorePoints()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Restore Points")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    when (val state = restorePointsState) {
                        is DriveResource.Progress -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scanning Google Drive restore points...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        is DriveResource.Success -> {
                            val points = state.data ?: emptyList()
                            if (points.isEmpty()) {
                                Text(
                                    text = "No cloud backups found on this Google Drive account.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    points.forEach { point ->
                                        RestorePointHistoryItem(
                                            point = point,
                                            onRestore = {
                                                viewModel.selectRestorePointForRestore(point)
                                                onNavigateRestore()
                                            },
                                            onDelete = {
                                                targetDeleteFileId = point.driveFileId
                                                showDeleteBackupDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        is DriveResource.Error -> {
                            Text(
                                text = "Failed to load history: ${state.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> Unit
                    }

                    // Management Feedback
                    when (val mgmt = managementState) {
                        is DriveResource.Success -> {
                            Text("Operation completed successfully.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        is DriveResource.Error -> {
                            Text(mgmt.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        else -> Unit
                    }
                }
            }

            // =========================================================================
            // 12. BACKUP PASSWORD
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Backup Password & Recovery",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Text(
                        text = "Your backups are protected with zero-knowledge end-to-end encryption. Only your backup password or 12-word recovery phrase can unlock your messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. Custom Backup Password Control
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (hasCustomPassword) Icons.Default.Lock else Icons.Default.Key,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Custom Backup Password",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (hasCustomPassword) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (hasCustomPassword) "Active" else "Optional",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (hasCustomPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = if (hasCustomPassword) {
                                    "Future automated and manual backups will be encrypted with your custom password."
                                } else {
                                    "Set a memorable password to restore your backups on any device."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (hasCustomPassword) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { showChangePasswordDialog = true },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.LockReset, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Change Password", fontSize = 12.sp)
                                    }

                                    TextButton(
                                        onClick = { showRemovePasswordDialog = true }
                                    ) {
                                        Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { showSetPasswordDialog = true },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Set Backup Password", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Zero-Crypto Security Guarantee
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Backups are encrypted on-device with zero-knowledge security before uploading to Google Drive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // --- Disconnect Confirmation Dialog ---
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect Google Drive?") },
            text = { Text("Your local messages will remain safe on this phone. Automatic cloud backups will stop until you reconnect an account.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.disconnectGoogleAccount()
                    showDisconnectDialog = false
                }) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Delete Cloud Backup Confirmation Dialog ---
    if (showDeleteBackupDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteBackupDialog = false
                targetDeleteFileId = null
            },
            title = { Text("Delete Cloud Restore Point?") },
            text = { Text("This will permanently delete this encrypted vault from your Google Drive AppData folder. Your local chats on this phone WILL NOT be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val fileIdToDelete = targetDeleteFileId ?: (remoteFileState as? DriveResource.Success)?.data?.id
                    if (fileIdToDelete != null) {
                        viewModel.deleteRemoteBackup(fileIdToDelete)
                    }
                    showDeleteBackupDialog = false
                    targetDeleteFileId = null
                }) {
                    Text("Delete Forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteBackupDialog = false
                    targetDeleteFileId = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Set Backup Password Dialog ---
    if (showSetPasswordDialog) {
        SetBackupPasswordDialog(
            onDismiss = { showSetPasswordDialog = false },
            onSave = { pwd, confirm ->
                val result = viewModel.setBackupPassword(pwd, confirm)
                if (result.isValid) {
                    Toast.makeText(context, "Backup password set successfully", Toast.LENGTH_SHORT).show()
                }
                result
            }
        )
    }

    // --- Change Backup Password Dialog ---
    if (showChangePasswordDialog) {
        ChangeBackupPasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onChange = { current, newP, confirm ->
                val result = viewModel.changeBackupPassword(current, newP, confirm)
                if (result.isValid) {
                    Toast.makeText(context, "Backup password updated successfully", Toast.LENGTH_SHORT).show()
                }
                result
            }
        )
    }

    // --- Remove Backup Password Dialog ---
    if (showRemovePasswordDialog) {
        AlertDialog(
            onDismissRequest = { showRemovePasswordDialog = false },
            title = { Text("Remove Backup Password?") },
            text = { Text("Future backups will be protected exclusively by your 12-word account recovery phrase. Existing backups will retain their original encryption.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBackupPassword()
                    showRemovePasswordDialog = false
                    Toast.makeText(context, "Backup password removed", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CategoryRow(
    title: String,
    description: String,
    iconRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color(0xFFA0A7B5),
                uncheckedTrackColor = Color(0xFF2C2C2E),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun QueueTierRow(tier: String, target: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = tier,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = target, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            Text(text = detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RestorePointHistoryItem(
    point: BackupRestorePoint,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.getDefault()).format(Date(point.createdAt))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (point.isLegacy) Icons.Default.FolderZip else Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (point.isLegacy) "Format v1" else "Format v2",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatFileSize(point.sizeBytes)} • ${point.totalConversations} chats • ${point.totalMessages} msgs • ${point.mediaFilesCount} files",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onRestore,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restore", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SetBackupPasswordDialog(
    onDismiss: () -> Unit,
    onSave: (password: String, confirm: String) -> com.nexa.app.core.backup.PasswordValidationResult
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val strength = remember(password) { BackupPasswordValidator.evaluateStrength(password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Backup Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Set a password to protect future cloud backups. Minimum 8 characters, including uppercase, lowercase, numbers, and symbols.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("New Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (password.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Strength: ", style = MaterialTheme.typography.labelSmall)
                        val (label, color) = when (strength) {
                            PasswordStrength.WEAK -> "Weak" to MaterialTheme.colorScheme.error
                            PasswordStrength.FAIR -> "Fair" to MaterialTheme.colorScheme.tertiary
                            PasswordStrength.STRONG -> "Strong" to MaterialTheme.colorScheme.primary
                            PasswordStrength.VERY_STRONG -> "Very Strong" to MaterialTheme.colorScheme.primary
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = color)
                    }
                }

                OutlinedTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        errorMessage = null
                    },
                    label = { Text("Confirm Password") },
                    singleLine = true,
                    visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { confirmVisible = !confirmVisible }) {
                            Icon(if (confirmVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = onSave(password, confirm)
                if (result.isValid) {
                    onDismiss()
                } else {
                    errorMessage = result.primaryErrorMessage ?: "Invalid password"
                }
            }) {
                Text("Save Password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ChangeBackupPasswordDialog(
    onDismiss: () -> Unit,
    onChange: (current: String, newP: String, confirm: String) -> com.nexa.app.core.backup.PasswordValidationResult
) {
    var current by remember { mutableStateOf("") }
    var newP by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var currentVisible by remember { mutableStateOf(false) }
    var newVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val strength = remember(newP) { BackupPasswordValidator.evaluateStrength(newP) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Backup Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Enter your current password or 12-word recovery phrase, followed by the new password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = current,
                    onValueChange = {
                        current = it
                        errorMessage = null
                    },
                    label = { Text("Current Password or 12 Words") },
                    singleLine = true,
                    visualTransformation = if (currentVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { currentVisible = !currentVisible }) {
                            Icon(if (currentVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newP,
                    onValueChange = {
                        newP = it
                        errorMessage = null
                    },
                    label = { Text("New Password") },
                    singleLine = true,
                    visualTransformation = if (newVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { newVisible = !newVisible }) {
                            Icon(if (newVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (newP.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Strength: ", style = MaterialTheme.typography.labelSmall)
                        val (label, color) = when (strength) {
                            PasswordStrength.WEAK -> "Weak" to MaterialTheme.colorScheme.error
                            PasswordStrength.FAIR -> "Fair" to MaterialTheme.colorScheme.tertiary
                            PasswordStrength.STRONG -> "Strong" to MaterialTheme.colorScheme.primary
                            PasswordStrength.VERY_STRONG -> "Very Strong" to MaterialTheme.colorScheme.primary
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = color)
                    }
                }

                OutlinedTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        errorMessage = null
                    },
                    label = { Text("Confirm New Password") },
                    singleLine = true,
                    visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { confirmVisible = !confirmVisible }) {
                            Icon(if (confirmVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = onChange(current, newP, confirm)
                if (result.isValid) {
                    onDismiss()
                } else {
                    errorMessage = result.primaryErrorMessage ?: "Failed to change password"
                }
            }) {
                Text("Update Password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "Never"
    val sdf = SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
