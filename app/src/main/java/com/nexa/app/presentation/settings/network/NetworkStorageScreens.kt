package com.nexa.app.presentation.settings.network

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.data.local.preferences.MediaAutoReceiveMode
import com.nexa.app.data.local.preferences.MediaAutoReceivePreferences
import com.nexa.app.data.local.preferences.NearbyMeshPreferences
import com.nexa.app.data.remote.dtn.MeshState
import com.nexa.app.domain.repository.DtnRepository
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsInfoRow
import com.nexa.app.presentation.settings.components.SettingsRadioRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow
import java.io.File

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

@Composable
fun StorageDataScreen(
    context: Context,
    mediaAutoReceivePreferences: MediaAutoReceivePreferences,
    onNavigateBack: () -> Unit
) {
    var refreshKey by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val usage = remember(refreshKey) { calculateUsage(context) }
    val autoReceiveMode by mediaAutoReceivePreferences.state.collectAsState()

    SettingsDetailPage(title = "Storage & Data", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "All message content is stored in an encrypted SQLCipher database. Cache files are " +
                "disposable and never contain plaintext message content."
        )

        SettingsSectionLabel("Media Auto-Receive")
        SettingsCard {
            SettingsRadioRow(
                title = MediaAutoReceiveMode.NEVER.label,
                subtitle = "Require explicit user acceptance before any inbound transfer begins.",
                selected = autoReceiveMode == MediaAutoReceiveMode.NEVER,
                onClick = { mediaAutoReceivePreferences.setMode(MediaAutoReceiveMode.NEVER) }
            )
            SettingsDivider()
            SettingsRadioRow(
                title = MediaAutoReceiveMode.WIFI_ONLY.label,
                subtitle = "Auto-receive on Wi‑Fi for known and authorized contacts only.",
                selected = autoReceiveMode == MediaAutoReceiveMode.WIFI_ONLY,
                onClick = { mediaAutoReceivePreferences.setMode(MediaAutoReceiveMode.WIFI_ONLY) }
            )
            SettingsDivider()
            SettingsRadioRow(
                title = MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA.label,
                subtitle = "Allow auto-receive over Wi‑Fi and mobile data when policy permits.",
                selected = autoReceiveMode == MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA,
                onClick = { mediaAutoReceivePreferences.setMode(MediaAutoReceiveMode.WIFI_AND_MOBILE_DATA) }
            )
        }

        SettingsSectionLabel("On-Device Storage")
        SettingsCard {
            SettingsInfoRow("Encrypted database", formatBytes(usage.databaseBytes))
            SettingsDivider()
            SettingsInfoRow("Media & vault", formatBytes(usage.filesBytes))
            SettingsDivider()
            SettingsInfoRow("Cache", formatBytes(usage.cacheBytes))
            SettingsDivider()
            SettingsInfoRow("Total app data", formatBytes(usage.totalBytes))
        }

        SettingsSectionLabel("Maintenance")
        SettingsCard {
            com.nexa.app.presentation.settings.components.SettingsActionRow(
                title = "Clear Cache",
                subtitle = "Delete temporary files and thumbnails. Messages and media are kept.",
                enabled = usage.cacheBytes > 0L,
                onClick = {
                    val deleted = clearCache(context)
                    message = if (deleted > 0L) {
                        "Cleared ${formatBytes(deleted)} of cache"
                    } else {
                        "Cache is already empty"
                    }
                    refreshKey++
                }
            )
        }

        if (message != null) {
            Text(
                text = message!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        SettingsSectionLabel("Encrypted Backup")
        SettingsCard {
            Text(
                text = "Backups are created from the encrypted vault through Google Drive AppData. " +
                    "Use Settings → Backup to configure the schedule or run a manual backup.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

private data class StorageUsage(
    val databaseBytes: Long,
    val filesBytes: Long,
    val cacheBytes: Long
) {
    val totalBytes: Long get() = databaseBytes + filesBytes + cacheBytes
}

private fun calculateUsage(context: Context): StorageUsage {
    // The encrypted SQLCipher vault (plus any -wal / -shm journals) lives here.
    val databasesDir = File(context.applicationInfo.dataDir, "databases")
    val dbBytes = directorySize(databasesDir)
    val filesBytes = directorySize(context.filesDir)
    val cacheBytes = directorySize(context.cacheDir) + (context.externalCacheDir?.let { directorySize(it) } ?: 0L)
    return StorageUsage(dbBytes, filesBytes, cacheBytes)
}

private fun directorySize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    return try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
        0L
    }
}

private fun clearCache(context: Context): Long {
    val dirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
    var deleted = 0L
    for (dir in dirs) {
        if (!dir.exists()) continue
        dir.listFiles()?.forEach { child ->
            deleted += directorySize(child)
            runCatching { child.deleteRecursively() }
        }
    }
    return deleted
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}
