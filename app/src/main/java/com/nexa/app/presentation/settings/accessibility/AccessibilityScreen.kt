package com.nexa.app.presentation.settings.accessibility

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.data.local.preferences.AccessibilityPreferences
import com.nexa.app.data.local.preferences.ChatsPreferences
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsRadioRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow

@Composable
fun AccessibilityScreen(
    chatsPreferences: ChatsPreferences,
    accessibilityPreferences: AccessibilityPreferences,
    onOpenFontSize: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val chatSettings by chatsPreferences.state.collectAsState()
    val accessibility by accessibilityPreferences.state.collectAsState()

    SettingsDetailPage(title = "Accessibility", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "NEXA follows the system font scale. Message text size is controlled by a single " +
                "canonical preference shared with the Chats settings screen."
        )

        SettingsSectionLabel("Text")
        SettingsCard {
            SettingsRadioRow(
                title = "Message Text Size",
                subtitle = "Current: ${chatSettings.fontSize.label}",
                selected = false,
                onClick = onOpenFontSize
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "High Contrast Messages",
                subtitle = "Use stronger foreground/background contrast inside message bubbles",
                checked = accessibility.highContrastMessages,
                onCheckedChange = { accessibilityPreferences.setHighContrastMessages(it) }
            )
        }

        SettingsSectionLabel("Motion")
        SettingsCard {
            Text(
                text = "NEXA honours Android's system animation scale. Reduce animations in " +
                    "Android Settings → Accessibility if you prefer less motion.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
