package com.nexa.app.presentation.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/**
 * Canonical Settings destinations, in on-screen order. Personal Privacy and
 * Privacy & Safety stay separate; Notifications and Sound stay separate; Nearby
 * Discovery and Mesh Relay live under the independent Nearby & Mesh screen.
 */
enum class SettingsDestination {
    ACCOUNT,
    PERSONAL_PRIVACY,
    PRIVACY_AND_SAFETY,
    SECURITY,
    APP_LOCK,
    APPEARANCE,
    NOTIFICATIONS,
    SOUND,
    CHATS,
    ARCHIVE,
    NEARBY_AND_MESH,
    STORAGE_AND_DATA,
    LANGUAGE,
    ACCESSIBILITY,
    CONNECTED_DEVICES,
    BACKUP,
    HELP,
    ABOUT
}

/**
 * Main Settings Screen. Uses the existing [NexaSettingsItem] row design and the
 * fixed bottom navigation capsule (Home + Profile) without the Plus FAB.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateProfile: () -> Unit,
    onNavigateDestination: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val snackbarHostState = remember { SnackbarHostState() }

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
                item {
                    NexaSettingsItem(
                        title = "Account",
                        subtitle = "Display name, phone number, NEXA ID",
                        iconRes = R.drawable.ic_nexa_profile,
                        onClick = { onNavigateDestination(SettingsDestination.ACCOUNT) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Personal Privacy",
                        subtitle = "Read receipts, typing indicators, presence",
                        iconRes = R.drawable.ic_nexa_shield,
                        onClick = { onNavigateDestination(SettingsDestination.PERSONAL_PRIVACY) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Privacy & Safety",
                        subtitle = "Blocked contacts and safety controls",
                        iconRes = R.drawable.ic_nexa_block,
                        onClick = { onNavigateDestination(SettingsDestination.PRIVACY_AND_SAFETY) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Security",
                        subtitle = "Identity keys, certificates, session state",
                        iconRes = R.drawable.ic_nexa_security_lock,
                        onClick = { onNavigateDestination(SettingsDestination.SECURITY) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "App Lock",
                        subtitle = "PIN and biometric protection",
                        iconRes = R.drawable.ic_nexa_pin,
                        onClick = { onNavigateDestination(SettingsDestination.APP_LOCK) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Appearance",
                        subtitle = "AMOLED Dark, Light, System Default",
                        iconRes = R.drawable.ic_nexa_theme,
                        onClick = { onNavigateDestination(SettingsDestination.APPEARANCE) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Notifications",
                        subtitle = "Push privacy, vibration, message previews",
                        iconRes = R.drawable.ic_nexa_bell,
                        onClick = { onNavigateDestination(SettingsDestination.NOTIFICATIONS) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Sound",
                        subtitle = "Ringtones, message sounds, and event audio",
                        iconRes = R.drawable.ic_nexa_music,
                        onClick = { onNavigateDestination(SettingsDestination.SOUND) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Chats",
                        subtitle = "Message text size, Enter to Send, media",
                        iconRes = R.drawable.ic_nexa_chats,
                        onClick = { onNavigateDestination(SettingsDestination.CHATS) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Archive",
                        subtitle = "Archived conversations and retained history",
                        iconRes = R.drawable.ic_nexa_archive,
                        onClick = { onNavigateDestination(SettingsDestination.ARCHIVE) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Nearby & Mesh",
                        subtitle = "Nearby Discovery and DTN mesh relay",
                        iconRes = R.drawable.ic_nexa_mesh,
                        onClick = { onNavigateDestination(SettingsDestination.NEARBY_AND_MESH) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Storage & Data",
                        subtitle = "Encrypted storage usage and cache",
                        iconRes = R.drawable.ic_nexa_storage,
                        onClick = { onNavigateDestination(SettingsDestination.STORAGE_AND_DATA) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Language",
                        subtitle = "App language (BETA)",
                        iconRes = R.drawable.ic_nexa_language,
                        onClick = { onNavigateDestination(SettingsDestination.LANGUAGE) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Accessibility",
                        subtitle = "Text size and message contrast",
                        iconRes = R.drawable.ic_nexa_info,
                        onClick = { onNavigateDestination(SettingsDestination.ACCESSIBILITY) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Connected Devices",
                        subtitle = "Linked hardware and active sessions",
                        iconRes = R.drawable.ic_nexa_devices,
                        onClick = { onNavigateDestination(SettingsDestination.CONNECTED_DEVICES) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Backup",
                        subtitle = "Zero-knowledge Google Drive backup",
                        iconRes = R.drawable.ic_nexa_cloud_backup,
                        onClick = { onNavigateDestination(SettingsDestination.BACKUP) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "Help & Support",
                        subtitle = "FAQ, report a bug, contact support",
                        iconRes = R.drawable.ic_nexa_help,
                        onClick = { onNavigateDestination(SettingsDestination.HELP) }
                    )
                }

                item {
                    NexaSettingsItem(
                        title = "About",
                        subtitle = "Version, security stack, build information",
                        iconRes = R.drawable.ic_nexa_about,
                        onClick = { onNavigateDestination(SettingsDestination.ABOUT) }
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
                    currentDestination = BottomNavDestination.SETTINGS,
                    showPlusButton = false,
                    onNavigateHome = onNavigateHome,
                    onNavigateProfile = onNavigateProfile
                )
            }
        }
    }
}
