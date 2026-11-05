package com.nexa.app.presentation.settings.notifications

import android.app.TimePickerDialog
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexa.app.core.notification.NexaNotificationChannelManager
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.data.local.preferences.LockScreenMode
import com.nexa.app.presentation.settings.components.SystemPermissionWarningBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.preferencesState.collectAsState()
    val isSystemPermissionGranted by viewModel.isSystemPermissionGranted.collectAsState()

    // Sync system notification permission on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                viewModel.onEvent(NotificationSettingsEvent.UpdateSystemPermissionState(enabled))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // System Permission Warning Banner
            SystemPermissionWarningBanner(
                isSystemPermissionGranted = isSystemPermissionGranted,
                onOpenAppSettings = { NexaNotificationChannelManager.openAppNotificationSettings(context) }
            )

            // Section 1: Direct Messages
            SettingsCategoryCard(
                categoryTitle = "Direct Messages",
                categoryIcon = Icons.AutoMirrored.Filled.Chat,
                accentColor = MaterialTheme.colorScheme.primary
            ) {
                SwitchSettingRow(
                    title = "Direct Message Alerts",
                    subtitle = "Heads-up alert banners and notifications for private chats",
                    checked = state.directMessagesEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleDirectMessages(it)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                SwitchSettingRow(
                    title = "Message Preview",
                    subtitle = "Show decrypted text snippet in the notification banner",
                    checked = state.messagePreviewEnabled,
                    enabled = state.directMessagesEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleMessagePreview(it)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                ChannelConfigLinkRow(
                    label = "Configure Sound & Vibration in Android",
                    onClick = {
                        NexaNotificationChannelManager.openChannelSettings(
                            context,
                            NexaNotificationChannelManager.CHANNEL_MESSAGES_DIRECT
                        )
                    }
                )
            }

            // Section 2: Group Messages
            SettingsCategoryCard(
                categoryTitle = "Group Chats",
                categoryIcon = Icons.Default.Group,
                accentColor = NexaVerifiedGreen
            ) {
                SwitchSettingRow(
                    title = "Group Notifications",
                    subtitle = "Notification tray alerts and soft chimes for group messages",
                    checked = state.groupsEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleGroups(it)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                SwitchSettingRow(
                    title = "Mentions Only",
                    subtitle = "Only alert when your @handle or name is directly mentioned",
                    checked = state.groupMentionsOnly,
                    enabled = state.groupsEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleGroupMentionsOnly(it)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                ChannelConfigLinkRow(
                    label = "Configure Group Channel Settings",
                    onClick = {
                        NexaNotificationChannelManager.openChannelSettings(
                            context,
                            NexaNotificationChannelManager.CHANNEL_MESSAGES_GROUP
                        )
                    }
                )
            }

            // Section 3: Calls
            SettingsCategoryCard(
                categoryTitle = "Voice & Video Calls",
                categoryIcon = Icons.Default.Call,
                accentColor = NexaWarningOrange
            ) {
                SwitchSettingRow(
                    title = "Incoming Call Alerts",
                    subtitle = "Ringtone and full-screen notifications for incoming P2P calls",
                    checked = state.callsEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleCalls(it)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                SwitchSettingRow(
                    title = "Allow Calls During Quiet Hours",
                    subtitle = "Urgent incoming calls can bypass DND quiet hours",
                    checked = state.allowCallsDuringQuietHours,
                    enabled = state.callsEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleAllowCallsDuringQuietHours(it)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                ChannelConfigLinkRow(
                    label = "Configure Ringtone in Android Settings",
                    onClick = {
                        NexaNotificationChannelManager.openChannelSettings(
                            context,
                            NexaNotificationChannelManager.CHANNEL_CALLS_INCOMING
                        )
                    }
                )
            }

            // Section 4: Lock Screen Privacy (Zero E2EE Leaks)
            SettingsCategoryCard(
                categoryTitle = "Lock Screen Privacy",
                categoryIcon = Icons.Default.Lock,
                accentColor = NexaBadgeMonospace
            ) {
                Text(
                    text = "Controls how encrypted notifications appear on your Android lock screen:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                LockScreenMode.values().forEach { mode ->
                    val isSelected = state.lockScreenVisibility == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onEvent(NotificationSettingsEvent.SetLockScreenMode(mode)) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Section 5: Quiet Hours / DND
            SettingsCategoryCard(
                categoryTitle = "Quiet Hours (Do Not Disturb)",
                categoryIcon = Icons.Default.DoNotDisturb,
                accentColor = MaterialTheme.colorScheme.error
            ) {
                SwitchSettingRow(
                    title = "Enable Quiet Hours",
                    subtitle = "Mute chimes and vibrations during your chosen schedule",
                    checked = state.isQuietHoursEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleQuietHours(it)) }
                )

                if (state.isQuietHoursEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Start Time Selector
                        TimeSelectorButton(
                            label = "Start Time",
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

                        Text("to", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // End Time Selector
                        TimeSelectorButton(
                            label = "End Time",
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

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Zero Server Knowledge: Quiet hours operate strictly on your phone's clock (${state.quietHoursFormatted}).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // Section 6: Transfers & Nearby Mesh Discovery
            SettingsCategoryCard(
                categoryTitle = "Transfers & Mesh Discovery",
                categoryIcon = Icons.Default.CellTower,
                accentColor = MaterialTheme.colorScheme.tertiary
            ) {
                SwitchSettingRow(
                    title = "File Transfer Progress",
                    subtitle = "Silent ongoing progress bar while streaming files over WebRTC",
                    checked = state.fileTransfersEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleFileTransfers(it)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                SwitchSettingRow(
                    title = "Mesh Discovery Alerts",
                    subtitle = "Passive silent ping when new Bluetooth LE mesh nodes connect (debounced)",
                    checked = state.meshDiscoveryAlertsEnabled,
                    onCheckedChange = { viewModel.onEvent(NotificationSettingsEvent.ToggleMeshDiscovery(it)) }
                )
            }

            // System Channels Master Link
            OutlinedButton(
                onClick = { NexaNotificationChannelManager.openAppNotificationSettings(context) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manage All Notification Channels in Android Settings")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    categoryTitle: String,
    categoryIcon: ImageVector,
    accentColor: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(accentColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = categoryIcon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = categoryTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            content()
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun ChannelConfigLinkRow(
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
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
private fun TimeSelectorButton(
    label: String,
    hour: Int,
    minute: Int,
    onTimeSelected: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
