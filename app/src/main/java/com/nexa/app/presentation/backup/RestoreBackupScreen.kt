package com.nexa.app.presentation.backup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.presentation.backup.components.BackupProgressCard
import com.nexa.app.presentation.backup.components.MnemonicInputGrid
import java.util.Locale

/**
 * Onboarding and recovery screen allowing users to discover cloud backups and restore
 * via their 12-word BIP-39 recovery mnemonic phrase.
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
                title = { Text("Restore Chat History") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Remote Discovery Header
            Text(
                text = "Recover from Google Drive",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Restores your chats, media references, and cryptographic keys onto this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Discovery State
            when (val state = remoteFileState) {
                is DriveResource.Progress -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(state.phase, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                is DriveResource.Success -> {
                    val file = state.data
                    if (file != null) {
                        selectedFile = file
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Cloud Backup Found",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }

                                Text(
                                    text = "Size: ${formatFileSize(file.size)} • Modified: ${file.modifiedTime ?: "Recent"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (!isEnteringPassphrase && restoreState !is DriveResource.Progress) {
                                    Button(
                                        onClick = { isEnteringPassphrase = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Restore This Backup")
                                    }
                                }
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("No Backup Found", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                Text(
                                    "No encrypted backup was discovered in your Google Drive AppData folder.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = { viewModel.scanRemoteBackup() }) {
                                    Text("Re-scan")
                                }
                            }
                        }
                    }
                }

                is DriveResource.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                else -> Unit
            }

            // Passphrase Entry Section
            AnimatedVisibility(visible = isEnteringPassphrase && selectedFile != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Enter 12-Word Passphrase",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Text(
                            text = "Type or paste your recovery words in order to decrypt the vault.",
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
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
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
                            Text("Decrypt & Restore Database", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.ROOT, "%.1f MB", mb)
}
