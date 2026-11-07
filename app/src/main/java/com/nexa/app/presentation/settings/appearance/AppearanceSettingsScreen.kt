package com.nexa.app.presentation.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.AppearanceMode
import com.nexa.app.data.local.preferences.AppearancePreferences
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.settings.components.SettingsHeader

/**
 * Appearance Settings Screen.
 *
 * Implements 3 theme choices with live preview and persistence:
 * 1. System Default
 * 2. Dark / AMOLED (#000000)
 * 3. Light (#FFFFFF)
 */
@Composable
fun AppearanceSettingsScreen(
    appearancePrefs: AppearancePreferences,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val currentMode by appearancePrefs.appearanceMode.collectAsState()

    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            SettingsHeader(
                title = "Appearance",
                onBack = onNavigateBack
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Choose your preferred appearance mode. NEXA uses pure AMOLED black for maximum contrast and battery efficiency on OLED displays.",
                fontSize = 13.sp,
                color = secondaryText,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 1. System Default
            AppearanceOptionCard(
                title = "System Default",
                description = "Automatically adapt to your device's system dark mode setting.",
                mode = AppearanceMode.SYSTEM,
                isSelected = currentMode == AppearanceMode.SYSTEM,
                previewBgColor = if (isDark) Color(0xFF121212) else Color(0xFFF0F0F0),
                onSelect = { appearancePrefs.setAppearanceMode(AppearanceMode.SYSTEM) }
            )

            // 2. Dark / AMOLED
            AppearanceOptionCard(
                title = "Dark / AMOLED",
                description = "Pure black #000000 background with translucent dark liquid glass.",
                mode = AppearanceMode.DARK,
                isSelected = currentMode == AppearanceMode.DARK,
                previewBgColor = Color.Black,
                onSelect = { appearancePrefs.setAppearanceMode(AppearanceMode.DARK) }
            )

            // 3. Light
            AppearanceOptionCard(
                title = "Light",
                description = "Pure white #FFFFFF background with frosted light liquid glass.",
                mode = AppearanceMode.LIGHT,
                isSelected = currentMode == AppearanceMode.LIGHT,
                previewBgColor = Color.White,
                onSelect = { appearancePrefs.setAppearanceMode(AppearanceMode.LIGHT) }
            )
        }
    }
}

@Composable
private fun AppearanceOptionCard(
    title: String,
    description: String,
    mode: AppearanceMode,
    isSelected: Boolean,
    previewBgColor: Color,
    onSelect: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA)
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    NexaGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        surfaceColor = surfaceColor,
        isSelected = isSelected,
        elevation = if (isSelected) 3.dp else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Theme Mini-Preview Swatch
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(previewBgColor, RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else Color(0x33888888),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = when (mode) {
                            AppearanceMode.SYSTEM -> R.drawable.ic_nexa_theme
                            AppearanceMode.DARK -> R.drawable.ic_nexa_ghost
                            AppearanceMode.LIGHT -> R.drawable.ic_nexa_smile
                        }
                    ),
                    contentDescription = null,
                    tint = if (mode == AppearanceMode.DARK) Color.White else if (mode == AppearanceMode.LIGHT) Color.Black else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = primaryText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = secondaryText,
                    lineHeight = 16.sp
                )
            }

            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
