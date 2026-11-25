package com.nexa.app.presentation.settings.privacy

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.nexa.app.R
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nexa.app.core.proto.PresenceStatus
import com.nexa.app.data.local.preferences.ChatPrivacyPrefs
import com.nexa.app.data.local.preferences.OfflinePresenceMode
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TransportRouteMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode
import com.nexa.app.data.remote.dto.BlockedUserItemDto
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.settings.components.SettingsActionRow
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsNavRow
import com.nexa.app.presentation.settings.components.SettingsRadioRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow
import kotlinx.coroutines.launch

/**
 * Model representing a selectable option in the Personal Privacy selection dialog.
 */
data class PersonalPrivacyOption<T>(
    val value: T,
    val title: String,
    val subtitle: String? = null
)

/**
 * Modal selection dialog styled with NexaGlassSurface, checkmark indicators,
 * and immediate dismissal upon selection.
 */
@Composable
private fun <T> PersonalPrivacySelectionDialog(
    title: String,
    subtitle: String? = null,
    options: List<PersonalPrivacyOption<T>>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    onDismissRequest: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Dialog(onDismissRequest = onDismissRequest) {
        NexaGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            surfaceColor = if (isDark) Color(0xF0181818) else Color(0xF8FFFFFF),
            elevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = secondaryText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                SettingsDivider()
                Spacer(modifier = Modifier.height(4.dp))

                options.forEachIndexed { index, option ->
                    if (index > 0) {
                        SettingsDivider()
                    }
                    SettingsRadioRow(
                        title = option.title,
                        subtitle = option.subtitle,
                        selected = option.value == selectedOption,
                        onClick = {
                            onOptionSelected(option.value)
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Personal Privacy: visibility controls enforced entirely on-device before packets
 * are formed (read receipts, typing pulses, presence, transport badges).
 *
 * Each setting is presented in its own dedicated card, mirroring the main Settings layout,
 * with option-based selections presented via modal glass selection dialogs.
 */
@Composable
fun PersonalPrivacyScreen(
    chatPrivacyPrefs: ChatPrivacyPrefs,
    chatRepository: ChatRepository? = null,
    onNavigateBack: () -> Unit
) {
    var readReceipts by remember { mutableStateOf(chatPrivacyPrefs.readReceiptsEnabled) }
    var typingMode by remember { mutableStateOf(chatPrivacyPrefs.typingIndicatorMode) }
    var showOnlineStatus by remember { mutableStateOf(chatPrivacyPrefs.showOnlineStatus) }
    var presenceMode by remember { mutableStateOf(chatPrivacyPrefs.onlinePresenceMode) }
    var offlinePresenceMode by remember { mutableStateOf(chatPrivacyPrefs.offlinePresenceMode) }
    var showBadges by remember { mutableStateOf(chatPrivacyPrefs.showTransportBadges) }
    var route by remember { mutableStateOf(chatPrivacyPrefs.preferredTransportRoute) }

    // Dialog state
    var showTypingDialog by remember { mutableStateOf(false) }
    var showPresenceDialog by remember { mutableStateOf(false) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    var showRouteDialog by remember { mutableStateOf(false) }

    SettingsDetailPage(title = "Personal Privacy", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "These controls are applied locally before any packet leaves your device. " +
                "NEXA never uploads read receipts or presence to the relay."
        )

        // 1. Visibility Section
        SettingsSectionLabel("Visibility")

        // Setting 1: Read Receipts (Toggle Card)
        SettingsCard {
            SettingsSwitchRow(
                title = "Read Receipts",
                subtitle = "Let contacts know when you have read their messages.",
                checked = readReceipts,
                onCheckedChange = { checked ->
                    readReceipts = checked
                    chatPrivacyPrefs.readReceiptsEnabled = checked
                }
            )
        }

        // Setting 2: Typing Indicator (Selection Card)
        SettingsCard {
            SettingsNavRow(
                title = "Typing Indicator",
                subtitle = "Show a typing pulse while you compose a message.",
                value = typingMode.label,
                onClick = { showTypingDialog = true }
            )
        }

        // 2. Online Status Section
        SettingsSectionLabel("Online Status")

        // Setting 3: Show My Online Status (Toggle Card)
        SettingsCard {
            SettingsSwitchRow(
                title = "Show My Online Status",
                subtitle = "Hide your online and offline status indicators from everyone when turned off.",
                checked = showOnlineStatus,
                onCheckedChange = { checked ->
                    showOnlineStatus = checked
                    chatPrivacyPrefs.showOnlineStatus = checked
                    val effectiveStatus = if (checked && presenceMode != OnlinePrivacyMode.NOBODY) {
                        PresenceStatus.PRESENCE_ONLINE
                    } else {
                        PresenceStatus.PRESENCE_HIDDEN
                    }
                    chatRepository?.sendPresenceStatus(effectiveStatus)
                }
            )
        }

        // Setting 4: Who Can See My Online Status (Selection Card)
        SettingsCard {
            SettingsNavRow(
                title = "Who Can See My Online Status",
                subtitle = "Choose who can see when you are online",
                value = presenceMode.label,
                onClick = { showPresenceDialog = true }
            )
        }

        // 3. When I'm Offline Section
        SettingsSectionLabel("When I'm Offline")

        // Setting 5: When I'm Offline (Selection Card)
        SettingsCard {
            SettingsNavRow(
                title = "When I'm Offline",
                subtitle = "Choose what status to show when you're disconnected",
                value = offlinePresenceMode.label,
                onClick = { showOfflineDialog = true }
            )
        }

        // 4. Transport Section
        SettingsSectionLabel("Transport")

        // Setting 6: Show Transport Badges (Toggle Card)
        SettingsCard {
            SettingsSwitchRow(
                title = "Show Transport Badges",
                subtitle = "Display how each message travelled (Internet, Mesh, or Data SMS).",
                checked = showBadges,
                onCheckedChange = { checked ->
                    showBadges = checked
                    chatPrivacyPrefs.showTransportBadges = checked
                }
            )
        }

        // Setting 7: Preferred Route (Selection Card)
        SettingsCard {
            SettingsNavRow(
                title = "Preferred Route",
                subtitle = "Choose default transport route for outgoing messages",
                value = route.label,
                onClick = { showRouteDialog = true }
            )
        }
    }

    // Modal Selection Dialog: Typing Indicator
    if (showTypingDialog) {
        PersonalPrivacySelectionDialog(
            title = "Typing Indicator",
            subtitle = "Show a typing pulse while you compose a message.",
            options = listOf(
                PersonalPrivacyOption(
                    value = TypingPrivacyMode.EVERYONE,
                    title = "Everyone",
                    subtitle = "Show a typing pulse while you compose a message"
                ),
                PersonalPrivacyOption(
                    value = TypingPrivacyMode.NOBODY,
                    title = "Nobody",
                    subtitle = "Never transmit typing state"
                )
            ),
            selectedOption = typingMode,
            onOptionSelected = { selected ->
                typingMode = selected
                chatPrivacyPrefs.typingIndicatorMode = selected
            },
            onDismissRequest = { showTypingDialog = false }
        )
    }

    // Modal Selection Dialog: Who Can See My Online Status
    if (showPresenceDialog) {
        PersonalPrivacySelectionDialog(
            title = "Who Can See My Online Status",
            subtitle = "Choose who can see when you are active on NEXA",
            options = listOf(
                PersonalPrivacyOption(
                    value = OnlinePrivacyMode.EVERYONE,
                    title = "Everyone",
                    subtitle = "All eligible NEXA users can see your online state"
                ),
                PersonalPrivacyOption(
                    value = OnlinePrivacyMode.MY_CONTACTS,
                    title = "My Contacts",
                    subtitle = "Only people in your contacts can see your online state"
                ),
                PersonalPrivacyOption(
                    value = OnlinePrivacyMode.NOBODY,
                    title = "Nobody",
                    subtitle = "No one can see your online state"
                )
            ),
            selectedOption = presenceMode,
            onOptionSelected = { selected ->
                presenceMode = selected
                chatPrivacyPrefs.onlinePresenceMode = selected
                val effectiveStatus = if (showOnlineStatus && selected != OnlinePrivacyMode.NOBODY) {
                    PresenceStatus.PRESENCE_ONLINE
                } else {
                    PresenceStatus.PRESENCE_HIDDEN
                }
                chatRepository?.sendPresenceStatus(effectiveStatus)
            },
            onDismissRequest = { showPresenceDialog = false }
        )
    }

    // Modal Selection Dialog: When I'm Offline
    if (showOfflineDialog) {
        PersonalPrivacySelectionDialog(
            title = "When I'm Offline",
            subtitle = "Choose what status to show when you're disconnected",
            options = listOf(
                PersonalPrivacyOption(
                    value = OfflinePresenceMode.SHOW_OFFLINE,
                    title = OfflinePresenceMode.SHOW_OFFLINE.label,
                    subtitle = "Display standard Offline status"
                ),
                PersonalPrivacyOption(
                    value = OfflinePresenceMode.LAST_SEEN_RECENTLY,
                    title = OfflinePresenceMode.LAST_SEEN_RECENTLY.label,
                    subtitle = "Display approximate last seen status"
                ),
                PersonalPrivacyOption(
                    value = OfflinePresenceMode.TAKING_A_BREAK,
                    title = OfflinePresenceMode.TAKING_A_BREAK.label,
                    subtitle = "Display break status message to contacts"
                )
            ),
            selectedOption = offlinePresenceMode,
            onOptionSelected = { selected ->
                offlinePresenceMode = selected
                chatPrivacyPrefs.offlinePresenceMode = selected
            },
            onDismissRequest = { showOfflineDialog = false }
        )
    }

    // Modal Selection Dialog: Preferred Route
    if (showRouteDialog) {
        PersonalPrivacySelectionDialog(
            title = "Preferred Route",
            subtitle = "Choose default transport route for outgoing messages",
            options = listOf(
                PersonalPrivacyOption(
                    value = TransportRouteMode.AUTO,
                    title = TransportRouteMode.AUTO.label,
                    subtitle = "Choose the best available transport per message"
                ),
                PersonalPrivacyOption(
                    value = TransportRouteMode.INTERNET,
                    title = TransportRouteMode.INTERNET.label,
                    subtitle = "Use the encrypted WebSocket relay only"
                ),
                PersonalPrivacyOption(
                    value = TransportRouteMode.MESH,
                    title = TransportRouteMode.MESH.label,
                    subtitle = "Use Nearby Mesh / DTN store-and-forward only"
                ),
                PersonalPrivacyOption(
                    value = TransportRouteMode.SMS,
                    title = TransportRouteMode.SMS.label,
                    subtitle = "Use binary Data SMS on port 8901"
                )
            ),
            selectedOption = route,
            onOptionSelected = { selected ->
                route = selected
                chatPrivacyPrefs.preferredTransportRoute = selected
            },
            onDismissRequest = { showRouteDialog = false }
        )
    }
}

/**
 * Blocked Contacts Screen: dedicated management screen matching the main Settings design system.
 *
 * Displays:
 * - Count of blocked contacts
 * - Individual blocked user cards with avatar fallback, name, NX ID, and "Blocked" status
 * - Separate "Unblock" button with confirmation dialog
 * - Card click opens that user's conversation without modifying block status
 * - Proper loading, empty, and error states
 */
@Composable
fun BlockedContactsScreen(
    contactRepository: ContactRepository,
    chatRepository: ChatRepository? = null,
    onNavigateToChat: (nxId: String, displayName: String) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val scope = rememberCoroutineScope()
    val blockedUsers = remember { mutableStateListOf<BlockedUserItemDto>() }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var userToUnblock by remember { mutableStateOf<BlockedUserItemDto?>(null) }

    fun loadBlockedUsers() {
        isLoading = true
        errorMessage = null
        scope.launch {
            contactRepository.getBlockedUsers()
                .onSuccess {
                    blockedUsers.clear()
                    blockedUsers.addAll(it)
                }
                .onFailure {
                    errorMessage = "Could not load blocked contacts: ${it.localizedMessage ?: "network or storage error"}"
                }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadBlockedUsers()
    }

    SettingsDetailPage(title = "Blocked Contacts", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "Manage people you have blocked on NEXA. Blocked contacts cannot send you messages, " +
                "view your online status, or connect with you. You can unblock them at any time."
        )

        // Error State Card with Retry Action
        if (errorMessage != null) {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = errorMessage!!,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { loadBlockedUsers() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Retry", fontSize = 12.sp, color = Color.Black)
                    }
                }
            }
        }

        // Section Label with Dynamic Count
        SettingsSectionLabel("Blocked Users (${blockedUsers.size})")

        when {
            isLoading -> {
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Loading blocked contacts…",
                            fontSize = 13.sp,
                            color = secondaryText
                        )
                    }
                }
            }
            blockedUsers.isEmpty() && errorMessage == null -> {
                // Empty State matching Settings design
                SettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0x33262626) else Color(0x33E5E7EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_block),
                                contentDescription = null,
                                tint = secondaryText,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Blocked Contacts",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "You haven't blocked anyone. People you block will appear here.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = secondaryText,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            else -> {
                // Individual Blocked User Cards
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    blockedUsers.forEach { user ->
                        SettingsCard(
                            modifier = Modifier.clickable {
                                onNavigateToChat(user.nxId, user.displayName.ifBlank { user.nxId })
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Fallback Avatar
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(if (isDark) Color(0xFF262626) else Color(0xFFE5E7EB)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val initial = (user.displayName.ifBlank { user.nxId }).take(1).uppercase()
                                    Text(
                                        text = initial,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        color = if (isDark) Color.White else Color(0xFF111111)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                // Contact Info Column
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.displayName.ifBlank { user.nxId },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = primaryText
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = user.nxId,
                                        fontSize = 12.sp,
                                        color = secondaryText
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = "Blocked",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Separate Unblock Button (does not trigger card navigation)
                                Button(
                                    onClick = { userToUnblock = user },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isDark) Color(0xFF262626) else Color(0xFFEEEEEE),
                                        contentColor = primaryText
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text("Unblock", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Action Feedback Notification
        if (actionFeedback != null) {
            Text(
                text = actionFeedback!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }

    // Confirmation Dialog: Unblock Contact
    userToUnblock?.let { target ->
        UnblockContactConfirmationDialog(
            contactName = target.displayName.ifBlank { target.nxId },
            onConfirmUnblock = {
                val toRemove = target
                userToUnblock = null
                scope.launch {
                    contactRepository.unblockUser(toRemove.nxId)
                        .onSuccess {
                            blockedUsers.remove(toRemove)
                            actionFeedback = "${toRemove.displayName.ifBlank { toRemove.nxId }} unblocked"
                        }
                        .onFailure {
                            actionFeedback = "Unblock failed: ${it.localizedMessage ?: "unknown error"}"
                        }
                }
            },
            onDismiss = { userToUnblock = null }
        )
    }
}

/**
 * Backward-compatible alias for navigation graphs or tests referencing PrivacySafetyScreen.
 */
@Deprecated("Renamed to BlockedContactsScreen", ReplaceWith("BlockedContactsScreen(contactRepository, chatRepository, onNavigateToChat, onNavigateBack)"))
@Composable
fun PrivacySafetyScreen(
    contactRepository: ContactRepository,
    onNavigateBack: () -> Unit
) {
    BlockedContactsScreen(
        contactRepository = contactRepository,
        onNavigateBack = onNavigateBack
    )
}
