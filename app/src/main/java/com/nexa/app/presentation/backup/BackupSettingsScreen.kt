package com.nexa.app.presentation.backup

import android.app.Activity
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.local.preferences.BackupFrequency
import com.nexa.app.presentation.backup.components.BackupProgressCard
import com.nexa.app.presentation.backup.components.MnemonicInputGrid
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Production Backup & Restore Settings Screen following the NEXA Liquid Glass Design System.
 * Supports Google Account selection, granular category controls, schedule automation,
 * E2EE Key Envelope recovery, and cloud backup management.
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
    val managementState by viewModel.managementResource.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showDeleteBackupDialog by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAuthState()
        viewModel.scanRemoteBackup()
    }

    LaunchedEffect(isAuthorized) {
        if (isAuthorized) {
            viewModel.scanRemoteBackup()
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
            // SECTION A: GOOGLE DRIVE ACCOUNT & STORAGE STATUS
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
                                text = "Google Drive Backup",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (isAuthorized) prefsState.googleAccountEmail ?: "Connected" else "Account Not Linked",
                                style = MaterialTheme.typography.bodySmall,
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Last Successful Backup", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatTimestamp(prefsState.lastBackupTimestamp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Estimated Size", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatFileSize(if (estimatedSize > 0) estimatedSize else prefsState.lastBackupSize),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
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
            // ACTIVE BACKUP PROGRESS OR ALERTS
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
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                is DriveResource.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cloud backup verified & sealed successfully!", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                else -> Unit
            }

            // Manual Trigger & Restore Navigation
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
            // SECTION B: INDIVIDUAL BACKUP CATEGORIES
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Group 1: Communication Data
                    Text(
                        text = "COMMUNICATION DATA",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    CategoryCheckboxRow("All Chats & Direct Messages", categories.includeChats) {
                        viewModel.updateCategory { it.copy(includeChats = !it.includeChats) }
                    }
                    CategoryCheckboxRow("Group Conversations", categories.includeGroups) {
                        viewModel.updateCategory { it.copy(includeGroups = !it.includeGroups) }
                    }
                    CategoryCheckboxRow("Contacts & Conversation Metadata", categories.includeContacts) {
                        viewModel.updateCategory { it.copy(includeContacts = !it.includeContacts) }
                    }
                    CategoryCheckboxRow("Message Attachments Vault", categories.includeAttachments) {
                        viewModel.updateCategory { it.copy(includeAttachments = !it.includeAttachments) }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Group 2: Media & Files
                    Text(
                        text = "MEDIA & FILES",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    CategoryCheckboxRow("Images & Photos", categories.includeImages) {
                        viewModel.updateCategory { it.copy(includeImages = !it.includeImages) }
                    }
                    CategoryCheckboxRow("Videos", categories.includeVideos) {
                        viewModel.updateCategory { it.copy(includeVideos = !it.includeVideos) }
                    }
                    CategoryCheckboxRow("Documents (PDF, Office, Text)", categories.includeDocuments) {
                        viewModel.updateCategory { it.copy(includeDocuments = !it.includeDocuments) }
                    }
                    CategoryCheckboxRow("Audio & Voice Notes", categories.includeAudio) {
                        viewModel.updateCategory { it.copy(includeAudio = !it.includeAudio) }
                    }
                    CategoryCheckboxRow("Other Files & Archives", categories.includeOtherFiles) {
                        viewModel.updateCategory { it.copy(includeOtherFiles = !it.includeOtherFiles) }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Group 3: Application Data
                    Text(
                        text = "APPLICATION DATA",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    CategoryCheckboxRow("Bookmarks & Saved Messages", categories.includeBookmarks) {
                        viewModel.updateCategory { it.copy(includeBookmarks = !it.includeBookmarks) }
                    }
                    CategoryCheckboxRow("User Preferences (Theme, Font, Auto-Download)", categories.includeUserPrefs) {
                        viewModel.updateCategory { it.copy(includeUserPrefs = !it.includeUserPrefs) }
                    }
                    CategoryCheckboxRow("Chat Configurations (Pinned, Mute, Wallpapers)", categories.includeAppSettings) {
                        viewModel.updateCategory { it.copy(includeAppSettings = !it.includeAppSettings) }
                    }
                    CategoryCheckboxRow("Portable Device Settings", categories.includeDeviceSettings) {
                        viewModel.updateCategory { it.copy(includeDeviceSettings = !it.includeDeviceSettings) }
                    }
                }
            }

            // =========================================================================
            // SECTION C: DEVICE SETTINGS SECURITY GUARANTEE
            // =========================================================================
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Hardware KeyStore Protection Invariant",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Android KeyStore private keys, authentication tokens, active sessions, and hardware secrets are NEVER included in backups. When restored on a new device, fresh hardware keys are generated to maintain total cryptographic isolation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // =========================================================================
            // SECTION D: BACKUP SCHEDULE & AUTOMATION
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Backup Schedule & Automation",
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
                            checked = prefsState.autoBackupEnabled,
                            onCheckedChange = { viewModel.setAutoBackupEnabled(it) }
                        )
                    }

                    if (prefsState.autoBackupEnabled) {
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
                            Text("Notify on completion or errors", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = prefsState.backupNotificationsEnabled,
                            onCheckedChange = { viewModel.setNotificationsEnabled(it) }
                        )
                    }
                }
            }

            // =========================================================================
            // SECTION E: END-TO-END ENCRYPTION & 12-WORD RECOVERY KEY
            // =========================================================================
            NexaGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "End-to-End Encryption (E2EE)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Text(
                        text = "Your backups are encrypted client-side using streaming AES-256-GCM. The key is derived exclusively from your 12-word recovery phrase via PBKDF2 (65,536 rounds). Neither Google nor NEXA can decrypt your chats without these words.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    recoveryPassphrase?.let { words ->
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
                                    Text(
                                        text = "12-Word Recovery Mnemonic",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row {
                                        IconButton(onClick = { viewModel.toggleRevealPassphrase() }) {
                                            Icon(
                                                imageVector = if (isPassphraseRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = "Toggle Passphrase"
                                            )
                                        }
                                        IconButton(onClick = {
                                            clipboardManager.setText(AnnotatedString(words))
                                            Toast.makeText(context, "Mnemonic copied to clipboard", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Mnemonic")
                                        }
                                    }
                                }

                                if (isPassphraseRevealed) {
                                    val wordList = words.split(" ").filter { it.isNotBlank() }
                                    MnemonicInputGrid(words = wordList)
                                } else {
                                    Text(
                                        text = "•••• •••• •••• •••• •••• •••• •••• •••• •••• •••• •••• ••••",
                                        style = MaterialTheme.typography.bodyMedium,
                                        letterSpacing = 2.sp,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // SECTION F: CLOUD BACKUP MANAGEMENT & ACTIONS
            // =========================================================================
            if (isAuthorized) {
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
                                text = "Cloud Backup Management",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            IconButton(onClick = { viewModel.scanRemoteBackup() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Cloud State")
                            }
                        }

                        when (val remote = remoteFileState) {
                            is DriveResource.Progress -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scanning Google Drive...", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            is DriveResource.Success -> {
                                val file = remote.data
                                if (file != null) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("File: ${file.name}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                        Text("Size: ${formatFileSize(file.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Modified: ${(file.modifiedTime ?: "Recent").take(19).replace("T", " ")} UTC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { viewModel.verifyRemoteBackupIntegrity(file.id) },
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Verify Integrity", fontSize = 12.sp)
                                            }

                                            OutlinedButton(
                                                onClick = { showDeleteBackupDialog = true },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Delete", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                } else {
                                    Text("No cloud backup found on this Google Drive account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            else -> Unit
                        }

                        // Management Feedback
                        when (val mgmt = managementState) {
                            is DriveResource.Success -> {
                                Text("Action completed successfully.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            is DriveResource.Error -> {
                                Text(mgmt.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            else -> Unit
                        }
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
            onDismissRequest = { showDeleteBackupDialog = false },
            title = { Text("Delete Cloud Backup?") },
            text = { Text("This will permanently delete the encrypted vault from your Google Drive appDataFolder. Your local chats on this phone WILL NOT be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val fileId = (remoteFileState as? DriveResource.Success)?.data?.id
                    if (fileId != null) {
                        viewModel.deleteRemoteBackup(fileId)
                    }
                    showDeleteBackupDialog = false
                }) {
                    Text("Delete Forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteBackupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CategoryCheckboxRow(
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
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange() }
        )
    }
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
