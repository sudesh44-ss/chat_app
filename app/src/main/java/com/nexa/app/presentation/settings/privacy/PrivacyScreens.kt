package com.nexa.app.presentation.settings.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.data.local.preferences.ChatPrivacyPrefs
import com.nexa.app.data.local.preferences.OfflinePresenceMode
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TransportRouteMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode
import com.nexa.app.data.remote.dto.BlockedUserItemDto
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.presentation.settings.components.SettingsActionRow
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsRadioRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow
import kotlinx.coroutines.launch

/**
 * Personal Privacy: visibility controls enforced entirely on-device before packets
 * are formed (read receipts, typing pulses, presence, transport badges).
 */
@Composable
fun PersonalPrivacyScreen(
    chatPrivacyPrefs: ChatPrivacyPrefs,
    onNavigateBack: () -> Unit
) {
    var readReceipts by remember { mutableStateOf(chatPrivacyPrefs.readReceiptsEnabled) }
    var typingMode by remember { mutableStateOf(chatPrivacyPrefs.typingIndicatorMode) }
    var showOnlineStatus by remember { mutableStateOf(chatPrivacyPrefs.showOnlineStatus) }
    var presenceMode by remember { mutableStateOf(chatPrivacyPrefs.onlinePresenceMode) }
    var offlinePresenceMode by remember { mutableStateOf(chatPrivacyPrefs.offlinePresenceMode) }
    val selectedOnlineContacts = remember { mutableStateListOf<String>().apply { addAll(chatPrivacyPrefs.selectedOnlineStatusContacts) } }
    var showBadges by remember { mutableStateOf(chatPrivacyPrefs.showTransportBadges) }
    var route by remember { mutableStateOf(chatPrivacyPrefs.preferredTransportRoute) }

    SettingsDetailPage(title = "Personal Privacy", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "These controls are applied locally before any packet leaves your device. " +
                "NEXA never uploads read receipts or presence to the relay."
        )

        SettingsSectionLabel("Visibility")
        SettingsCard {
            SettingsSwitchRow(
                title = "Read Receipts",
                subtitle = "Let contacts know when you have read their messages",
                checked = readReceipts,
                onCheckedChange = {
                    readReceipts = it
                    chatPrivacyPrefs.readReceiptsEnabled = it
                }
            )
            SettingsDivider()
            Text(
                text = "Typing Indicator",
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)
            )
            TypingPrivacyMode.values().forEach { mode ->
                SettingsRadioRow(
                    title = if (mode == TypingPrivacyMode.EVERYONE) "Everyone" else "Nobody",
                    subtitle = if (mode == TypingPrivacyMode.EVERYONE)
                        "Show a typing pulse while you compose a message"
                    else
                        "Never transmit typing state",
                    selected = typingMode == mode,
                    onClick = {
                        typingMode = mode
                        chatPrivacyPrefs.typingIndicatorMode = mode
                    }
                )
            }
        }

        SettingsSectionLabel("Online Status")
        SettingsCard {
            SettingsSwitchRow(
                title = "Show my online status",
                subtitle = "Hide your online and offline status indicators from everyone when turned off",
                checked = showOnlineStatus,
                onCheckedChange = {
                    showOnlineStatus = it
                    chatPrivacyPrefs.showOnlineStatus = it
                }
            )
            SettingsDivider()
            Text(
                text = "Who can see my online status",
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)
            )
            OnlinePrivacyMode.values().forEach { mode ->
                SettingsRadioRow(
                    title = when (mode) {
                        OnlinePrivacyMode.EVERYONE -> "Everyone"
                        OnlinePrivacyMode.MY_CONTACTS -> "My contacts"
                        OnlinePrivacyMode.NOBODY -> "Nobody"
                    },
                    subtitle = when (mode) {
                        OnlinePrivacyMode.EVERYONE -> "All eligible NEXA users can see your online state"
                        OnlinePrivacyMode.MY_CONTACTS -> "Only people in your contacts can see your online state"
                        OnlinePrivacyMode.NOBODY -> "No one can see your online state"
                    },
                    selected = presenceMode == mode,
                    onClick = {
                        presenceMode = mode
                        chatPrivacyPrefs.onlinePresenceMode = mode
                    }
                )
            }
        }

        SettingsSectionLabel("When I'm Offline")
        SettingsCard {
            OfflinePresenceMode.values().forEach { mode ->
                SettingsRadioRow(
                    title = mode.label,
                    subtitle = when (mode) {
                        OfflinePresenceMode.SHOW_OFFLINE -> "Display Offline"
                        OfflinePresenceMode.LAST_SEEN_RECENTLY -> "Display Last seen recently"
                        OfflinePresenceMode.TAKING_A_BREAK -> "Display a break message"
                    },
                    selected = offlinePresenceMode == mode,
                    onClick = {
                        offlinePresenceMode = mode
                        chatPrivacyPrefs.offlinePresenceMode = mode
                    }
                )
            }
        }

        SettingsSectionLabel("Transport")
        SettingsCard {
            SettingsSwitchRow(
                title = "Show Transport Badges",
                subtitle = "Display how each message travelled (Internet, Mesh, or Data SMS)",
                checked = showBadges,
                onCheckedChange = {
                    showBadges = it
                    chatPrivacyPrefs.showTransportBadges = it
                }
            )
            SettingsDivider()
            Text(
                text = "Preferred Route",
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)
            )
            TransportRouteMode.values().forEach { mode ->
                SettingsRadioRow(
                    title = mode.label,
                    subtitle = when (mode) {
                        TransportRouteMode.AUTO -> "Choose the best available transport per message"
                        TransportRouteMode.INTERNET -> "Use the encrypted WebSocket relay only"
                        TransportRouteMode.MESH -> "Use Nearby Mesh / DTN store-and-forward only"
                        TransportRouteMode.SMS -> "Use binary Data SMS on port 8901"
                    },
                    selected = route == mode,
                    onClick = {
                        route = mode
                        chatPrivacyPrefs.preferredTransportRoute = mode
                    }
                )
            }
        }
    }
}

/**
 * Privacy & Safety: blocked contacts backed by the real server-side block list,
 * kept separate from Personal Privacy visibility controls.
 */
@Composable
fun PrivacySafetyScreen(
    contactRepository: ContactRepository,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val blockedUsers = remember { mutableStateListOf<BlockedUserItemDto>() }
    var isLoading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        contactRepository.getBlockedUsers()
            .onSuccess { blockedUsers.addAll(it) }
            .onFailure { message = "Could not load blocked contacts: ${it.localizedMessage ?: "network error"}" }
        isLoading = false
    }

    SettingsDetailPage(title = "Privacy & Safety", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "Blocking is enforced locally and on the relay. Blocked peers are never notified " +
                "and cannot send you connection requests or messages."
        )

        SettingsSectionLabel("Blocked Contacts")
        SettingsCard {
            when {
                isLoading -> Text(
                    text = "Loading blocked contacts…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                blockedUsers.isEmpty() -> Text(
                    text = "No blocked contacts.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    blockedUsers.forEachIndexed { index, user ->
                        if (index > 0) SettingsDivider()
                        SettingsActionRow(
                            title = user.displayName.ifBlank { user.nxId },
                            subtitle = "${user.nxId} • Tap to unblock",
                            onClick = {
                                scope.launch {
                                    contactRepository.unblockUser(user.nxId)
                                        .onSuccess {
                                            blockedUsers.remove(user)
                                            message = "${user.displayName.ifBlank { user.nxId }} unblocked"
                                        }
                                        .onFailure {
                                            message = "Unblock failed: ${it.localizedMessage ?: "unknown error"}"
                                        }
                                }
                            }
                        )
                    }
                }
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
    }
}
