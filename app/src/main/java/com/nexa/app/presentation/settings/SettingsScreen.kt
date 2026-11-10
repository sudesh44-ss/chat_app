package com.nexa.app.presentation.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.presentation.dashboard.components.BottomNavDestination
import com.nexa.app.presentation.dashboard.components.NexaFloatingBottomNavigation
import com.nexa.app.presentation.settings.components.NexaSettingsItem
import com.nexa.app.presentation.settings.components.SettingsHeader
import kotlinx.coroutines.launch

enum class SettingsDestination {
    ACCOUNT,
    PRIVACY_AND_SAFETY,
    SECURITY,
    APPEARANCE,
    NOTIFICATIONS,
    LANGUAGE,
    ARCHIVED_CHATS,
    NETWORK,
    STORAGE_AND_DATA,
    DEVICES,
    BACKUP,
    HELP,
    ABOUT
}

/**
 * Main Settings Screen with exact 13 items in specified order:
 * 1. Account
 * 2. Privacy and Safety
 * 3. Security
 * 4. Appearance
 * 5. Notifications
 * 6. Language
 * 7. Archived Chats
 * 8. Network
 * 9. Storage and Data
 * 10. Devices
 * 11. Backup
 * 12. Help
 * 13. About
 *
 * Includes the fixed bottom navigation capsule (Home + Profile) without Plus FAB.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateDestination: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var activeDialogTitle by remember { mutableStateOf<String?>(null) }
    var activeDialogMessage by remember { mutableStateOf<String?>(null) }

    if (activeDialogTitle != null && activeDialogMessage != null) {
        AlertDialog(
            onDismissRequest = {
                activeDialogTitle = null
                activeDialogMessage = null
            },
            title = { Text(activeDialogTitle!!) },
            text = { Text(activeDialogMessage!!) },
            confirmButton = {
                TextButton(onClick = {
                    activeDialogTitle = null
                    activeDialogMessage = null
                }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = backgroundColor,
        topBar = {
            SettingsHeader(
                title = "Settings",
                onBack = onNavigateBack
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = NexaGlassTokens.ConversationListBottomPadding + 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Account
                item {
                    NexaSettingsItem(
                        title = "Account",
                        subtitle = "Display name, phone number, NEXA ID",
                        iconRes = R.drawable.ic_nexa_profile,
                        onClick = { onNavigateDestination(SettingsDestination.ACCOUNT) }
                    )
                }

                // 2. Privacy and Safety
                item {
                    NexaSettingsItem(
                        title = "Privacy and Safety",
                        subtitle = "Read receipts, typing indicators, discovery",
                        iconRes = R.drawable.ic_nexa_shield,
                        onClick = { onNavigateDestination(SettingsDestination.PRIVACY_AND_SAFETY) }
                    )
                }

                // 3. Security
                item {
                    NexaSettingsItem(
                        title = "Security",
                        subtitle = "Identity keys, certificates, session state",
                        iconRes = R.drawable.ic_nexa_security_lock,
                        onClick = { onNavigateDestination(SettingsDestination.SECURITY) }
                    )
                }

                // 4. Appearance
                item {
                    NexaSettingsItem(
                        title = "Appearance",
                        subtitle = "AMOLED Dark, Light, System Default",
                        iconRes = R.drawable.ic_nexa_theme,
                        onClick = { onNavigateDestination(SettingsDestination.APPEARANCE) }
                    )
                }

                // 5. Notifications
                item {
                    NexaSettingsItem(
                        title = "Notifications",
                        subtitle = "Push privacy, sound, vibration, previews",
                        iconRes = R.drawable.ic_nexa_bell,
                        onClick = { onNavigateDestination(SettingsDestination.NOTIFICATIONS) }
                    )
                }

                // 6. Language
                item {
                    NexaSettingsItem(
                        title = "Language",
                        subtitle = "English (System Default)",
                        iconRes = R.drawable.ic_nexa_language,
                        onClick = {
                            activeDialogTitle = "Language"
                            activeDialogMessage = "NEXA currently operates in English (System Default). Additional localization packages will be enabled in upcoming releases."
                        }
                    )
                }

                // 7. Archived Chats
                item {
                    NexaSettingsItem(
                        title = "Archived Chats",
                        subtitle = "Hidden & muted conversations",
                        iconRes = R.drawable.ic_nexa_archive,
                        onClick = {
                            activeDialogTitle = "Archived Chats"
                            activeDialogMessage = "No archived conversations. You can archive any conversation by long-pressing it on the home screen."
                        }
                    )
                }

                // 8. Network
                item {
                    NexaSettingsItem(
                        title = "Network",
                        subtitle = "RAM relay, Nearby Mesh, Port 8901 SMS",
                        iconRes = R.drawable.ic_nexa_wifi,
                        onClick = {
                            activeDialogTitle = "Network & Transports"
                            activeDialogMessage = "Active Relay: WebSocket over TLS.\nNearby Mesh: Google Nearby Connections.\nZero-disk DTN store-and-forward: Active."
                        }
                    )
                }

                // 9. Storage and Data
                item {
                    NexaSettingsItem(
                        title = "Storage and Data",
                        subtitle = "Encrypted cache, media auto-download",
                        iconRes = R.drawable.ic_nexa_storage,
                        onClick = {
                            activeDialogTitle = "Storage & Data"
                            activeDialogMessage = "Local database is encrypted at rest using SQLCipher 4.\nTemporary files in quarantine sandbox are purged automatically."
                        }
                    )
                }

                // 10. Devices
                item {
                    NexaSettingsItem(
                        title = "Devices",
                        subtitle = "Linked hardware & active sessions",
                        iconRes = R.drawable.ic_nexa_devices,
                        onClick = { onNavigateDestination(SettingsDestination.DEVICES) }
                    )
                }

                // 11. Backup
                item {
                    NexaSettingsItem(
                        title = "Backup",
                        subtitle = "Zero-knowledge Google Drive backup",
                        iconRes = R.drawable.ic_nexa_cloud_backup,
                        onClick = { onNavigateDestination(SettingsDestination.BACKUP) }
                    )
                }

                // 12. Help
                item {
                    NexaSettingsItem(
                        title = "Help",
                        subtitle = "FAQ, security guide, contact support",
                        iconRes = R.drawable.ic_nexa_help,
                        onClick = {
                            activeDialogTitle = "Help & Support"
                            activeDialogMessage = "NEXA is a privacy-first, zero-knowledge decentralized messaging application with end-to-end Double Ratchet encryption. For documentation visit https://nexa.network."
                        }
                    )
                }

                // 13. About
                item {
                    NexaSettingsItem(
                        title = "About",
                        subtitle = "NEXA v2.0 • Zero-Knowledge Architecture",
                        iconRes = R.drawable.ic_nexa_about,
                        onClick = {
                            activeDialogTitle = "About NEXA"
                            activeDialogMessage = "NEXA Mobile v2.0\nBuilt on Double Ratchet, X25519, Ed25519, Android Keystore StrongBox, and SQLCipher.\nYour keys and data remain exclusively on your device."
                        }
                    )
                }
            }

            // Fixed Bottom Navigation Capsule (Home + Profile) without Plus FAB
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = NexaGlassTokens.FloatingControlsBottomMargin)
            ) {
                NexaFloatingBottomNavigation(
                    currentDestination = BottomNavDestination.PROFILE,
                    showPlusButton = false,
                    onNavigateHome = onNavigateHome,
                    onNavigateProfile = { /* Already in Settings/Profile */ }
                )
            }
        }
    }
}
