package com.nexa.app.presentation.settings.network

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.storage.NexaStorageCategory
import com.nexa.app.core.storage.NexaStorageManager
import com.nexa.app.core.storage.StorageCategoryBreakdown
import com.nexa.app.core.theme.isNexaDarkTheme
import com.nexa.app.data.local.preferences.MediaAutoReceiveMode
import com.nexa.app.data.local.preferences.MediaAutoReceivePreferences
import com.nexa.app.data.local.preferences.NearbyMeshPreferences
import com.nexa.app.data.remote.dtn.MeshState
import com.nexa.app.domain.repository.DtnRepository
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsInfoRow
import com.nexa.app.presentation.settings.components.SettingsRadioRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow
import kotlinx.coroutines.launch

/**
 * Nearby & Mesh is deliberately independent from Personal Privacy.
 * Discovery is OFF by default and only starts Google Nearby Connections when the user opts in.
 */
@Composable
fun NearbyMeshScreen(
    nearbyMeshPreferences: NearbyMeshPreferences,
    dtnRepository: DtnRepository,
    onNavigateBack: () -> Unit
) {
    val settings by nearbyMeshPreferences.state.collectAsState()
    val meshState by dtnRepository.meshState.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    SettingsDetailPage(title = "Nearby & Mesh", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "Nearby Discovery uses Bluetooth and Wi-Fi Direct to find other NEXA devices " +
                "without the internet. It is OFF by default and never makes you publicly " +
                "discoverable unless you enable it here."
        )

        SettingsSectionLabel("Discovery")
        SettingsCard {
            SettingsSwitchRow(
                title = "Nearby Discovery",
                subtitle = "Advertise and scan for nearby NEXA peers using Google Nearby Connections",
                checked = settings.nearbyDiscoveryEnabled,
                onCheckedChange = { enabled ->
                    nearbyMeshPreferences.setNearbyDiscoveryEnabled(enabled)
                    if (enabled) {
                        dtnRepository.startMesh()
                        message = "Starting Nearby Mesh…"
                    } else {
                        dtnRepository.stopMesh()
                        message = "Nearby Mesh stopped"
                    }
                }
            )
        }

        SettingsSectionLabel("Mesh Status")
        SettingsCard {
            SettingsInfoRow(
                label = "State",
                value = when (val state = meshState) {
                    is MeshState.Idle -> "Idle"
                    is MeshState.AdvertisingAndDiscovering -> "Advertising & discovering"
                    is MeshState.Connected -> "Connected to ${state.peerCount} peer(s)"
                    is MeshState.Error -> "Error"
                }
            )
            SettingsDivider()
            SettingsInfoRow("Preference", if (settings.nearbyDiscoveryEnabled) "Enabled" else "Disabled")
            val state = meshState
            if (state is MeshState.Error) {
                SettingsDivider()
                Text(
                    text = state.message,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }

        if (message != null) {
            Text(
                text = message!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        SettingsSectionLabel("Permissions")
        SettingsCard {
            Text(
                text = "Nearby Mesh requires Bluetooth (Scan / Advertise / Connect) and, on " +
                    "Android 12 and below, location permission. If you denied them, grant them " +
                    "in Android Settings and toggle Nearby Discovery again.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/**
 * Storage & Data Screen.
 *
 * Provides complete visibility and control over NEXA's local storage:
 * 1. Standard Android User-Accessible Media Folder (/Android/media/<packageName>/NEXA)
 * 2. Real-time asynchronous storage calculation and visual progress breakdown
 * 3. Strict security isolation (SQLCipher and Keystore keys strictly in private internal storage)
 * 4. "Open NEXA Folder" intent integration with graceful fallback
 * 5. Safe "Clear Cache" with pre-clearing confirmation dialog
 * 6. Media Auto-Receive configuration
 */
@Composable
fun StorageDataScreen(
    context: Context,
    mediaAutoReceivePreferences: MediaAutoReceivePreferences,
    storageManager: NexaStorageManager = remember { NexaStorageManager(context) },
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val isDark = isNexaDarkTheme()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    var breakdown by remember { mutableStateOf(StorageCategoryBreakdown()) }
    var isCalculating by remember { mutableStateOf(true) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val autoReceiveMode by mediaAutoReceivePreferences.state.collectAsState()

    fun refreshUsage() {
        coroutineScope.launch {
            isCalculating = true
            breakdown = storageManager.calculateStorageUsage()
            isCalculating = false
        }
    }

    LaunchedEffect(Unit) {
        refreshUsage()
    }

    SettingsDetailPage(title = "Storage & Data", onBack = onNavigateBack) {
        // Privacy Banner
        SettingsInfoBanner(
            "NEXA maintains strict zero-knowledge security: All messages and cryptographic keys are encrypted with SQLCipher in private internal app storage. User media and downloads are saved in your accessible NEXA directory."
        )

        // -------------------------------------------------------------
        // 1. NEXA USER-ACCESSIBLE DIRECTORY CARD
        // -------------------------------------------------------------
        SettingsSectionLabel("NEXA Media Directory")
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Folder",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Standard Android Storage",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (breakdown.isFolderAccessible) "Active & User-Accessible" else "Initializing…",
                            fontSize = 12.sp,
                            color = if (breakdown.isFolderAccessible) Color(0xFF10B981) else Color(0xFFF59E0B),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    IconButton(
                        onClick = {
                            val path = storageManager.copyPathToClipboard(context)
                            Toast.makeText(context, "Path copied to clipboard:\n$path", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Path",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Folder Path Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isDark) Color(0x33000000) else Color(0x0F000000))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = breakdown.nexaRootPath.ifBlank { "/storage/emulated/0/Android/media/${context.packageName}/NEXA" },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = secondaryText,
                        lineHeight = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons: Open Folder & Refresh
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ElevatedButton(
                        onClick = {
                            try {
                                val intent = storageManager.createOpenFolderIntent()
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(
                                    context,
                                    "No file viewer found. Path copied to clipboard.",
                                    Toast.LENGTH_LONG
                                ).show()
                                storageManager.copyPathToClipboard(context)
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Could not open directory: ${e.localizedMessage}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.elevatedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = if (isDark) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Open Folder", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { refreshUsage() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.width(100.dp)
                    ) {
                        if (isCalculating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Scan", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -------------------------------------------------------------
        // 2. ON-DEVICE STORAGE BREAKDOWN
        // -------------------------------------------------------------
        SettingsSectionLabel("Storage Breakdown")
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Total App Storage",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText
                    )
                    Text(
                        text = NexaStorageManager.formatBytes(breakdown.totalAppBytes),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Visual Storage Bar
                StorageDistributionBar(breakdown = breakdown)

                Spacer(modifier = Modifier.height(16.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Category Rows
                StorageDetailItem(
                    icon = Icons.Default.Image,
                    iconTint = Color(0xFF38BDF8),
                    title = "Photos & Images",
                    sub = "NEXA/Media/Images • ${breakdown.imagesCount} files",
                    bytes = breakdown.imagesBytes
                )

                StorageDetailItem(
                    icon = Icons.Default.Videocam,
                    iconTint = Color(0xFFA855F7),
                    title = "Videos",
                    sub = "NEXA/Media/Videos • ${breakdown.videosCount} files",
                    bytes = breakdown.videosBytes
                )

                StorageDetailItem(
                    icon = Icons.Default.Audiotrack,
                    iconTint = Color(0xFFF97316),
                    title = "Audio & Voice Notes",
                    sub = "NEXA/Media/Audio • ${breakdown.audioCount} files",
                    bytes = breakdown.audioBytes
                )

                StorageDetailItem(
                    icon = Icons.Default.Description,
                    iconTint = Color(0xFF10B981),
                    title = "Documents & Files",
                    sub = "NEXA/Media/Documents • ${breakdown.documentsCount} files",
                    bytes = breakdown.documentsBytes
                )

                StorageDetailItem(
                    icon = Icons.Default.Download,
                    iconTint = Color(0xFF06B6D4),
                    title = "Downloads & Exports",
                    sub = "NEXA/Downloads • ${breakdown.downloadsCount + breakdown.exportsCount} files",
                    bytes = breakdown.downloadsBytes + breakdown.exportsBytes
                )

                StorageDetailItem(
                    icon = Icons.Default.Lock,
                    iconTint = Color(0xFFEC4899),
                    title = "Encrypted Backups",
                    sub = "NEXA/Backups/Encrypted • ${breakdown.encryptedBackupsCount} vaults",
                    bytes = breakdown.encryptedBackupsBytes
                )

                StorageDetailItem(
                    icon = Icons.Default.Security,
                    iconTint = Color(0xFF6366F1),
                    title = "Encrypted Database (SQLCipher)",
                    sub = "Internal vault • zero cloud exposure",
                    bytes = breakdown.encryptedDatabaseBytes
                )

                StorageDetailItem(
                    icon = Icons.Default.CleaningServices,
                    iconTint = Color(0xFFEAB308),
                    title = "App Cache & Temporary Files",
                    sub = "Thumbnails and network cache",
                    bytes = breakdown.cacheBytes
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -------------------------------------------------------------
        // 3. STORAGE MAINTENANCE & ACTIONS
        // -------------------------------------------------------------
        SettingsSectionLabel("Maintenance & Cleanup")
        SettingsCard {
            com.nexa.app.presentation.settings.components.SettingsActionRow(
                title = "Clear Cache",
                subtitle = "Deletes temporary thumbnails and network buffers (${NexaStorageManager.formatBytes(breakdown.cacheBytes)}). Messages, media, and keys are safe.",
                enabled = breakdown.cacheBytes > 0L && !isCalculating,
                onClick = { showClearCacheDialog = true }
            )
        }

        if (statusMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusMessage!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -------------------------------------------------------------
        // 4. MEDIA AUTO-RECEIVE SETTINGS
        // -------------------------------------------------------------
        SettingsSectionLabel("Media Auto-Receive")
        SettingsCard {
            SettingsRadioRow(
                title = MediaAutoReceiveMode.NEVER.label,
                subtitle = "Require explicit user acceptance before any inbound media transfer begins.",
                selected = autoReceiveMode == MediaAutoReceiveMode.NEVER,
                onClick = { mediaAutoReceivePreferences.setMode(MediaAutoReceiveMode.NEVER) }
            )
            SettingsDivider()
            SettingsRadioRow(
                title = MediaAutoReceiveMode.WIFI_ONLY.label,
                subtitle = "Auto-receive on Wi‑Fi for known and verified contacts only.",
                selected = autoReceiveMode == MediaAutoReceiveMode.WIFI_ONLY,
                onClick = { mediaAutoReceivePreferences.setMode(MediaAutoReceiveMode.WIFI_ONLY) }
            )
            SettingsDivider()
            SettingsRadioRow(
                title = MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA.label,
                subtitle = "Allow auto-receive over Wi‑Fi and mobile data when bandwidth permits.",
                selected = autoReceiveMode == MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA,
                onClick = { mediaAutoReceivePreferences.setMode(MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -------------------------------------------------------------
        // 5. ENCRYPTED BACKUP INFORMATION
        // -------------------------------------------------------------
        SettingsSectionLabel("Backup Architecture")
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Encrypted Local & Cloud Backups",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryText
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Backups are created directly from the encrypted SQLCipher vault using your BIP-39 recovery passphrase. Encrypted backup archives (.enc) are stored in NEXA/Backups/Encrypted and optionally mirrored to Google Drive AppData.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = secondaryText
                )
            }
        }
    }

    // Clear Cache Confirmation Dialog
    if (showClearCacheDialog) {
        val dialogBg = if (isDark) Color(0xFF1E222B) else Color(0xFFFFFFFF)
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            containerColor = dialogBg,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Clear Cache?",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "This will free up ${NexaStorageManager.formatBytes(breakdown.cacheBytes)} of temporary files and image cache.",
                        fontSize = 13.sp,
                        color = primaryText,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your messages, media files, downloaded documents, cryptographic keys, and encrypted backups will NOT be deleted.",
                        fontSize = 12.sp,
                        color = secondaryText,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                ElevatedButton(
                    onClick = {
                        showClearCacheDialog = false
                        coroutineScope.launch {
                            val result = storageManager.clearCache()
                            statusMessage = "Cleared ${NexaStorageManager.formatBytes(result.bytesCleared)} (${result.filesDeletedCount} cache files removed)"
                            refreshUsage()
                        }
                    },
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = if (isDark) Color.Black else Color.White
                    )
                ) {
                    Text("Clear Cache", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel", color = secondaryText)
                }
            }
        )
    }
}

/**
 * Visual multi-segment progress bar showing storage distribution.
 */
@Composable
private fun StorageDistributionBar(breakdown: StorageCategoryBreakdown) {
    val total = breakdown.totalAppBytes.coerceAtLeast(1L).toFloat()

    val pImages = (breakdown.imagesBytes / total).coerceIn(0f, 1f)
    val pVideos = (breakdown.videosBytes / total).coerceIn(0f, 1f)
    val pAudio = (breakdown.audioBytes / total).coerceIn(0f, 1f)
    val pDocs = (breakdown.documentsBytes / total).coerceIn(0f, 1f)
    val pDownloads = ((breakdown.downloadsBytes + breakdown.exportsBytes) / total).coerceIn(0f, 1f)
    val pBackups = (breakdown.encryptedBackupsBytes / total).coerceIn(0f, 1f)
    val pDb = (breakdown.encryptedDatabaseBytes / total).coerceIn(0f, 1f)
    val pCache = (breakdown.cacheBytes / total).coerceIn(0f, 1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x22888888))
    ) {
        if (pImages > 0.005f) Box(modifier = Modifier.weight(pImages).height(10.dp).background(Color(0xFF38BDF8)))
        if (pVideos > 0.005f) Box(modifier = Modifier.weight(pVideos).height(10.dp).background(Color(0xFFA855F7)))
        if (pAudio > 0.005f) Box(modifier = Modifier.weight(pAudio).height(10.dp).background(Color(0xFFF97316)))
        if (pDocs > 0.005f) Box(modifier = Modifier.weight(pDocs).height(10.dp).background(Color(0xFF10B981)))
        if (pDownloads > 0.005f) Box(modifier = Modifier.weight(pDownloads).height(10.dp).background(Color(0xFF06B6D4)))
        if (pBackups > 0.005f) Box(modifier = Modifier.weight(pBackups).height(10.dp).background(Color(0xFFEC4899)))
        if (pDb > 0.005f) Box(modifier = Modifier.weight(pDb).height(10.dp).background(Color(0xFF6366F1)))
        if (pCache > 0.005f) Box(modifier = Modifier.weight(pCache).height(10.dp).background(Color(0xFFEAB308)))
    }
}

/**
 * Storage category item row with icon, title, path/file count, and formatted byte size.
 */
@Composable
private fun StorageDetailItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    sub: String,
    bytes: Long
) {
    val isDark = isNexaDarkTheme()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(iconTint.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = primaryText
            )
            Text(
                text = sub,
                fontSize = 11.sp,
                color = secondaryText
            )
        }

        Text(
            text = NexaStorageManager.formatBytes(bytes),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = primaryText
        )
    }
}
