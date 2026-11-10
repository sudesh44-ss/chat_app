package com.nexa.app.presentation.backup

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.presentation.backup.components.BackupProgressCard
import com.nexa.app.presentation.backup.components.MnemonicInputGrid
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import java.util.Locale

/**
 * Production Restore Screen supporting Cloud Discovery, Selective Category Restore,
 * Restore Preview, 12-Word Mnemonic Validation, and In-RAM Key Envelope Decryption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreBackupScreen(
    viewModel: BackupViewModel,
    onRestoreSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val remoteFileState by viewModel.remoteBackupFile.collectAsState()
    val restoreState by viewModel.restoreResource.collectAsState()
    val prefsState by viewModel.preferencesState.collectAsState()
    val restoreCategories by viewModel.restoreCategoryConfig.collectAsState()

    val inputWords = remember { mutableStateListOf(*Array(12) { "" }) }
    var selectedFile by remember { mutableStateOf<DriveFile?>(null) }
    var isEnteringPassphrase by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.scanRemoteBackup()
    }

    LaunchedEffect(restoreState) {
        if (restoreState is DriveResource.Success) {
            onRestoreSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore Chat Backup", fontWeight = FontWeight.Bold) },
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

            // Header Section
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Cloud Recovery",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Restore conversations, media references, and settings onto this device from your zero-knowledge cloud vault.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // =========================================================================
            // 1. BACKUP DISCOVERY CARD
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (val state = remoteFileState) {
                        is DriveResource.Progress -> {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Scanning Google Drive appDataFolder...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        is DriveResource.Success -> {
                            val file = state.data
                            if (file != null) {
                                selectedFile = file
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.CloudDone,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Verified Cloud Vault Found",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = prefsState.googleAccountEmail ?: "Google Account",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Version 2", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Vault Size", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(formatFileSize(file.size), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Last Modified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text((file.modifiedTime ?: "Recent").take(19).replace("T", " "), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    }
                                }

                                if (!isEnteringPassphrase && restoreState !is DriveResource.Progress) {
                                    Button(
                                        onClick = { isEnteringPassphrase = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Select Data & Restore", fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                                    Text("No Backup Discovered", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(
                                        "No encrypted backup was discovered in your Google Drive AppData folder for this account.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedButton(
                                        onClick = { viewModel.scanRemoteBackup() },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Re-scan Google Drive")
                                    }
                                }
                            }
                        }

                        is DriveResource.Error -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }

            // =========================================================================
            // 2. SELECTIVE RESTORE CATEGORIES & PREVIEW
            // =========================================================================
            AnimatedVisibility(visible = isEnteringPassphrase && selectedFile != null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                Column {
                                    Text(
                                        text = "Selective Restore",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Choose which categories to restore",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { viewModel.setAllRestoreCategories(true) }) {
                                        Text("All", fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { viewModel.setAllRestoreCategories(false) }) {
                                        Text("None", fontSize = 12.sp)
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            RestoreCategoryRow("Chats & Messages", restoreCategories.includeChats) {
                                viewModel.updateRestoreCategory { it.copy(includeChats = !it.includeChats) }
                            }
                            RestoreCategoryRow("Group Conversations", restoreCategories.includeGroups) {
                                viewModel.updateRestoreCategory { it.copy(includeGroups = !it.includeGroups) }
                            }
                            RestoreCategoryRow("Contacts & Metadata", restoreCategories.includeContacts) {
                                viewModel.updateRestoreCategory { it.copy(includeContacts = !it.includeContacts) }
                            }
                            RestoreCategoryRow("Media Vault (Photos, Videos, Audio)", restoreCategories.includeAttachments) {
                                viewModel.updateRestoreCategory { it.copy(includeAttachments = !it.includeAttachments) }
                            }
                            RestoreCategoryRow("App Preferences & Settings", restoreCategories.includeAppSettings) {
                                viewModel.updateRestoreCategory { it.copy(includeAppSettings = !it.includeAppSettings) }
                            }
                        }
                    }

                    // Restore Mode & Safety Guarantee
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Atomic Swap & Rollback Safety", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                Text(
                                    "Your existing local messages are staged in a rollback journal before the restored database is activated. If decryption or database verification fails, the original database is automatically restored with zero data loss.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // =========================================================================
                    // 3. 12-WORD MNEMONIC INPUT & EXECUTION
                    // =========================================================================
                    NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Enter 12-Word Recovery Phrase",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }

                            Text(
                                text = "Enter the 12 secret words generated during your backup setup. They are required to decrypt the vault container and unseal the Key Envelope.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            MnemonicInputGrid(
                                words = inputWords,
                                onWordChange = { idx, word -> inputWords[idx] = word },
                                onPasteFullMnemonic = { phrase ->
                                    val words = Bip39PassphraseHelper.getWords(phrase)
                                    for (i in 0 until minOf(12, words.size)) {
                                        inputWords[i] = words[i]
                                    }
                                }
                            )

                            // Active Restore Progress Card
                            when (val state = restoreState) {
                                is DriveResource.Progress -> {
                                    BackupProgressCard(
                                        phase = state.phase,
                                        percent = state.percent,
                                        onCancel = { viewModel.cancelActiveRestore() }
                                    )
                                }
                                is DriveResource.Error -> {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                                else -> Unit
                            }

                            val isComplete = inputWords.all { it.isNotBlank() }
                            Button(
                                onClick = {
                                    val fullPhrase = inputWords.joinToString(" ")
                                    selectedFile?.id?.let { fileId ->
                                        viewModel.restoreBackup(fileId, fullPhrase)
                                    }
                                },
                                enabled = isComplete && restoreState !is DriveResource.Progress,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Decrypt & Restore Vault", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RestoreCategoryRow(
    title: String,
    checked: Boolean,
    onCheckedChange: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
    }
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
