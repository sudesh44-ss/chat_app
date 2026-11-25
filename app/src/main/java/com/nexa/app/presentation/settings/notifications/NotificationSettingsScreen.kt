package com.nexa.app.presentation.settings.notifications

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexa.app.R
import com.nexa.app.core.notification.NexaNotificationChannelManager
import com.nexa.app.core.notification.QuietHoursEngine
import com.nexa.app.core.theme.NexaPureBlack
import com.nexa.app.core.theme.NexaPureWhite
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.isNexaAmoled
import com.nexa.app.data.local.preferences.LockScreenMode
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow

/**
 * Redesigned Notifications Settings Screen for NEXA.
 *
 * Implements:
 * 1. Direct Message Alerts & Message Preview privacy.
 * 2. Group Chats alerts & Mentions Only filtering.
 * 3. Incoming Voice & Video Call Alerts with Quiet Hours exception.
 * 4. Lock Screen Privacy selection (Show All, Hide Sensitive Content, Don't Show).
 * 5. Full Quiet Hours scheduling (Start/End times, 7-day weekday pills, live status).
 * 6. File Transfer Progress & Mesh Discovery toggles.
 * 7. Live Android permission & disabled notification channel detection.
 * 8. Full theme compliance (System, Dark, AMOLED, Light).
 */
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.preferencesState.collectAsState()
    val isSystemPermissionGranted by viewModel.isSystemPermissionGranted.collectAsState()
    val disabledChannels by viewModel.disabledChannels.collectAsState()

    // Permission launcher for Android 13+ (TIRAMISU, API 33)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onEvent(NotificationSettingsEvent.UpdateSystemPermissionState(granted))
        val disabled = NexaNotificationChannelManager.getDisabledChannels(context)
        viewModel.onEvent(NotificationSettingsEvent.UpdateDisabledChannels(disabled))
    }

    // Refresh notification & channel permission state on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                viewModel.onEvent(NotificationSettingsEvent.UpdateSystemPermissionState(enabled))
                val disabled = NexaNotificationChannelManager.getDisabledChannels(context)
                viewModel.onEvent(NotificationSettingsEvent.UpdateDisabledChannels(disabled))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsDetailPage(
        title = "Notifications",
        onBack = onBack
    ) {
        // 1. Permission Warning Banner (displayed only when notifications are disabled)
        if (!isSystemPermissionGranted) {
            NotificationPermissionBanner(
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val status = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        if (status != PermissionChecker.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            NexaNotificationChannelManager.openAppNotificationSettings(context)
                        }
                    } else {
                        NexaNotificationChannelManager.openAppNotificationSettings(context)
                    }
                }
            )
        }

        // 2. Disabled Channel Notice (if user disabled individual channels in Android OS)
        if (disabledChannels.isNotEmpty() && isSystemPermissionGranted) {
            DisabledChannelWarningCard(
                disabledChannels = disabledChannels,
                onOpenChannel = { channelId ->
                    NexaNotificationChannelManager.openChannelSettings(context, channelId)
                }
            )
        }

        // ==========================================
        // SECTION 1: DIRECT MESSAGES
        // ==========================================
        SettingsSectionLabel(text = "Direct Messages")
        SettingsCard {
            SettingsSwitchRow(
                title = "Direct Message Alerts",
                subtitle = "Heads-up alert banners and notifications for private chats",
                checked = state.directMessagesEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleDirectMessages(it)) }
            )

            SettingsDivider()

            SettingsSwitchRow(
                title = "Message Preview",
                subtitle = "Show decrypted text snippet in notification previews",
                checked = state.messagePreviewEnabled,
                enabled = state.directMessagesEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleMessagePreview(it)) }
            )

            SettingsDivider()

            ChannelLinkRow(
                label = "Configure Sound & Vibration in Android",
                onClick = {
                    NexaNotificationChannelManager.openChannelSettings(
                        context,
                        NexaNotificationChannelManager.CHANNEL_MESSAGES_DIRECT
                    )
                }
            )
        }

        // ==========================================
        // SECTION 2: GROUP CHATS
        // ==========================================
        SettingsSectionLabel(text = "Group Chats")
        SettingsCard {
            SettingsSwitchRow(
                title = "Group Notifications",
                subtitle = "Notification tray alerts and soft chimes for group conversations",
                checked = state.groupsEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleGroups(it)) }
            )

            SettingsDivider()

            SettingsSwitchRow(
                title = "Mentions Only",
                subtitle = "Only alert when your @handle or name is directly mentioned",
                checked = state.groupMentionsOnly,
                enabled = state.groupsEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleGroupMentionsOnly(it)) }
            )

            SettingsDivider()

            ChannelLinkRow(
                label = "Configure Group Channel Settings",
                onClick = {
                    NexaNotificationChannelManager.openChannelSettings(
                        context,
                        NexaNotificationChannelManager.CHANNEL_MESSAGES_GROUP
                    )
                }
            )
        }

        // ==========================================
        // SECTION 3: VOICE & VIDEO CALLS
        // ==========================================
        SettingsSectionLabel(text = "Voice & Video Calls")
        SettingsCard {
            SettingsSwitchRow(
                title = "Incoming Call Alerts",
                subtitle = "Ringtone and full-screen alerts for incoming P2P calls",
                checked = state.callsEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleCalls(it)) }
            )

            SettingsDivider()

            SettingsSwitchRow(
                title = "Allow Calls During Quiet Hours",
                subtitle = "Urgent incoming calls can bypass DND quiet hours suppression",
                checked = state.allowCallsDuringQuietHours,
                enabled = state.callsEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleAllowCallsDuringQuietHours(it)) }
            )

            SettingsDivider()

            ChannelLinkRow(
                label = "Configure Ringtone in Android Settings",
                onClick = {
                    NexaNotificationChannelManager.openChannelSettings(
                        context,
                        NexaNotificationChannelManager.CHANNEL_CALLS_INCOMING
                    )
                }
            )
        }

        // ==========================================
        // SECTION 4: LOCK SCREEN PRIVACY
        // ==========================================
        SettingsSectionLabel(text = "Lock Screen Privacy")
        SettingsCard {
            Text(
                text = "Controls how encrypted notifications appear when your device is locked:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            LockScreenMode.values().forEachIndexed { index, mode ->
                if (index > 0) SettingsDivider()
                LockScreenModeOptionRow(
                    mode = mode,
                    isSelected = state.lockScreenVisibility == mode,
                    onSelect = { viewModel.onEvent(NotificationSettingsEvent.SetLockScreenMode(mode)) }
                )
            }
        }

        // ==========================================
        // SECTION 5: QUIET HOURS (DO NOT DISTURB)
        // ==========================================
        SettingsSectionLabel(text = "Quiet Hours (Do Not Disturb)")
        SettingsCard {
            SettingsSwitchRow(
                title = "Enable Quiet Hours",
                subtitle = "Mute chimes and vibrations during your chosen schedule",
                checked = state.isQuietHoursEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleQuietHours(it)) }
            )

            if (state.isQuietHoursEnabled) {
                SettingsDivider()

                // Status Indicator Badge
                val isCurrentlyActive = QuietHoursEngine.isWithinQuietHoursWindow(state)
                val statusDescription = QuietHoursEngine.getQuietHoursStatusDescription(state)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isCurrentlyActive) NexaVerifiedGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusDescription,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isCurrentlyActive) NexaVerifiedGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SettingsDivider()

                // Start & End Time Pickers
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuietHoursTimeButton(
                        label = "START TIME",
                        hour = state.quietHoursStartHour,
                        minute = state.quietHoursStartMinute,
                        onTimeSelected = { h, m ->
                            viewModel.onEvent(
                                NotificationSettingsEvent.SetQuietHoursWindow(
                                    startHour = h,
                                    startMin = m,
                                    endHour = state.quietHoursEndHour,
                                    endMin = state.quietHoursEndMinute
                                )
                            )
                        }
                    )

                    Text(
                        text = "to",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    QuietHoursTimeButton(
                        label = "END TIME",
                        hour = state.quietHoursEndHour,
                        minute = state.quietHoursEndMinute,
                        onTimeSelected = { h, m ->
                            viewModel.onEvent(
                                NotificationSettingsEvent.SetQuietHoursWindow(
                                    startHour = state.quietHoursStartHour,
                                    startMin = state.quietHoursStartMinute,
                                    endHour = h,
                                    endMin = m
                                )
                            )
                        }
                    )
                }

                SettingsDivider()

                // 7-Day Weekday Selector Pills
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "ACTIVE DAYS: ${state.quietHoursDaysFormatted.uppercase()}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
                        for (day in 1..7) {
                            val isSelected = state.quietHoursDays.contains(day)
                            DayPillButton(
                                label = dayLabels[day - 1],
                                isSelected = isSelected,
                                onClick = { viewModel.onEvent(NotificationSettingsEvent.ToggleQuietHoursDay(day)) }
                            )
                        }
                    }
                }

                SettingsDivider()

                Text(
                    text = "Zero Server Knowledge: Quiet hours operate strictly on your phone's clock with support for overnight schedules.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // ==========================================
        // SECTION 6: TRANSFERS & MESH DISCOVERY
        // ==========================================
        SettingsSectionLabel(text = "Transfers & Mesh Discovery")
        SettingsCard {
            SettingsSwitchRow(
                title = "File Transfer Progress",
                subtitle = "Silent ongoing progress bar while streaming files over WebRTC",
                checked = state.fileTransfersEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleFileTransfers(it)) }
            )

            SettingsDivider()

            SettingsSwitchRow(
                title = "Mesh Discovery Alerts",
                subtitle = "Passive silent ping when new Bluetooth LE mesh nodes connect (debounced)",
                checked = state.meshDiscoveryAlertsEnabled,
                onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleMeshDiscovery(it)) }
            )
        }

        // ==========================================
        // SECTION 7: SYSTEM CHANNELS MASTER LINK
        // ==========================================
        OutlinedButton(
            onClick = { NexaNotificationChannelManager.openAppNotificationSettings(context) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_settings),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Manage All Notification Channels in Android Settings",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LockScreenModeOptionRow(
    mode: LockScreenMode,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = secondaryText
            )
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.label,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = primaryText
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = mode.description,
                fontSize = 12.sp,
                color = secondaryText
            )
        }
    }
}

@Composable
private fun QuietHoursTimeButton(
    label: String,
    hour: Int,
    minute: Int,
    onTimeSelected: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val isAmoled = isNexaAmoled()
    val buttonBg = when {
        !isDark -> Color(0xFFF1F5F9)
        isAmoled -> Color(0xFF161618)
        else -> Color(0xFF242933)
    }

    Surface(
        color = buttonBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable {
            TimePickerDialog(
                context,
                { _, selectedHour, selectedMinute ->
                    onTimeSelected(selectedHour, selectedMinute)
                },
                hour,
                minute,
                true // 24-hour format
            ).show()
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = String.format("%02d:%02d", hour, minute),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DayPillButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val isAmoled = isNexaAmoled()

    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primary
        !isDark -> Color(0xFFF1F5F9)
        isAmoled -> Color(0xFF161618)
        else -> Color(0xFF242933)
    }

    val textCol = when {
        isSelected -> NexaPureBlack
        isDark -> NexaPureWhite
        else -> Color(0xFF111111)
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .background(bg, CircleShape)
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0x22888888),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = textCol
        )
    }
}

@Composable
private fun ChannelLinkRow(
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun NotificationPermissionBanner(
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAction)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.NotificationsOff,
                contentDescription = "Notifications Disabled",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Notifications Disabled in System Settings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Android is currently blocking all alerts. Tap here to enable notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DisabledChannelWarningCard(
    disabledChannels: List<String>,
    onOpenChannel: (String) -> Unit
) {
    val firstChannel = disabledChannels.first()
    val channelLabel = when (firstChannel) {
        NexaNotificationChannelManager.CHANNEL_MESSAGES_DIRECT -> "Direct Messages"
        NexaNotificationChannelManager.CHANNEL_MESSAGES_GROUP -> "Group Chats"
        NexaNotificationChannelManager.CHANNEL_CALLS_INCOMING -> "Incoming Calls"
        NexaNotificationChannelManager.CHANNEL_FILE_TRANSFER -> "File Transfers"
        NexaNotificationChannelManager.CHANNEL_MESH_DISCOVERY -> "Mesh Discovery"
        else -> "Notification Channel"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0x33FFB74D)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenChannel(firstChannel) }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = "Channel Disabled",
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$channelLabel channel is disabled",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Android is blocking alerts for this category. Tap to configure channel settings.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
