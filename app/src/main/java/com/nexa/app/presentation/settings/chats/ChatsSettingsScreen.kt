package com.nexa.app.presentation.settings.chats

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.data.local.preferences.ChatFontSize
import com.nexa.app.data.local.preferences.ChatsPreferences
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsRadioRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow

@Composable
fun ChatsSettingsScreen(
    chatsPreferences: ChatsPreferences,
    onOpenFontSize: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val settings by chatsPreferences.state.collectAsState()

    SettingsDetailPage(title = "Chats", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "Message text size is a single canonical preference used by both Chats and Accessibility."
        )

        SettingsSectionLabel("Messages")
        SettingsCard {
            SettingsRadioRow(
                title = "Message Text Size",
                subtitle = "Current: ${settings.fontSize.label}",
                selected = false,
                onClick = onOpenFontSize
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Enter to Send",
                subtitle = "Pressing the keyboard send key sends the message instead of adding a new line",
                checked = settings.enterToSend,
                onCheckedChange = { chatsPreferences.setEnterToSend(it) }
            )
        }

        SettingsSectionLabel("Media")
        SettingsCard {
            SettingsSwitchRow(
                title = "Media Visibility",
                subtitle = "Allow received files to be saved into the phone's Downloads/NEXA folder",
                checked = settings.mediaVisibilityEnabled,
                onCheckedChange = { chatsPreferences.setMediaVisibilityEnabled(it) }
            )
        }
    }
}

@Composable
fun ChatFontSizeScreen(
    chatsPreferences: ChatsPreferences,
    onNavigateBack: () -> Unit
) {
    val settings by chatsPreferences.state.collectAsState()
    val isDark = isSystemInDarkTheme()

    SettingsDetailPage(title = "Message Text Size", onBack = onNavigateBack) {
        SettingsSectionLabel("Preview")
        SettingsCard {
            Text(
                text = "NEXA keeps your conversations encrypted end-to-end.",
                fontSize = (15f * settings.fontSize.scale).sp,
                lineHeight = (20f * settings.fontSize.scale).sp,
                color = if (isDark) Color.White else Color(0xFF111111),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }

        SettingsSectionLabel("Size")
        SettingsCard {
            ChatFontSize.values().forEachIndexed { index, size ->
                if (index > 0) SettingsDivider()
                SettingsRadioRow(
                    title = size.label,
                    subtitle = "${(size.scale * 100).toInt()}% of the default message size",
                    selected = settings.fontSize == size,
                    onClick = { chatsPreferences.setFontSize(size) }
                )
            }
        }

        Text(
            text = "This preference is applied to message bubbles and the composer. " +
                "It does not change system UI or settings labels.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
