package com.nexa.app.presentation.settings.language

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.data.local.preferences.AppLanguage
import com.nexa.app.data.local.preferences.LanguagePreferences
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsRadioRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import java.util.Locale

@Composable
fun LanguageScreen(
    languagePreferences: LanguagePreferences,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val selected by languagePreferences.selectedLanguage.collectAsState()

    SettingsDetailPage(title = "Language", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "Language support is in BETA. The selected locale is applied to the app process " +
                "(including date, time and number formatting). Some NEXA interface strings are " +
                "still English-only and will be translated in future releases."
        )

        SettingsSectionLabel("App Language")
        SettingsCard {
            AppLanguage.values().forEachIndexed { index, language ->
                if (index > 0) SettingsDivider()
                SettingsRadioRow(
                    title = if (language.isBeta) "${language.label} (BETA)" else language.label,
                    subtitle = language.nativeLabel,
                    selected = selected == language,
                    onClick = {
                        languagePreferences.setLanguage(language)
                        if (language == AppLanguage.SYSTEM) {
                            val systemLocale = android.content.res.Resources.getSystem()
                                .configuration.locales[0]
                            Locale.setDefault(systemLocale)
                        } else {
                            Locale.setDefault(Locale.forLanguageTag(language.tag))
                        }
                        // Recreate so the configuration change is applied immediately.
                        context.findActivity()?.recreate()
                    }
                )
            }
        }

        Text(
            text = "The locale is stored on-device and re-applied when NEXA starts.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
