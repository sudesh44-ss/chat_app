package com.nexa.app.presentation.settings.appearance

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.AppearanceMode
import com.nexa.app.core.theme.NexaCyan80
import com.nexa.app.core.theme.NexaDarkCharcoalBackground
import com.nexa.app.core.theme.NexaDarkCharcoalSurface
import com.nexa.app.core.theme.NexaFontStyle
import com.nexa.app.core.theme.NexaPureBlack
import com.nexa.app.core.theme.NexaPureWhite
import com.nexa.app.core.theme.NexaTextSize
import com.nexa.app.core.theme.isNexaAmoled
import com.nexa.app.core.theme.isNexaDarkTheme
import com.nexa.app.data.local.preferences.AppearancePreferences
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow

/**
 * Appearance Settings Screen.
 *
 * Implements:
 * 1. 4 distinct theme modes with instant live preview and persistence:
 *    - System Default: Follows Android system dark/light mode
 *    - Dark: Charcoal #13151A surfaces with comfortable contrast
 *    - AMOLED: Pitch black #000000 backgrounds for OLED efficiency
 *    - Light: Clean white #FFFFFF surfaces and light cards
 * 2. Font Style customization (Default NEXA, Roboto, Serif, Monospace, Cursive)
 * 3. App Text Size scaling (Extra Small, Small, Medium, Large, Extra Large)
 * 4. Chat Text Size scaling (Extra Small, Small, Medium, Large, Extra Large)
 * 5. Live interactive dual-mode preview (UI hierarchy + Inbound/Outbound chat bubbles)
 * 6. Independent Reset to Default for font & sizing (preserves theme)
 * 7. Motion & Accessibility (Reduce Animations)
 */
@Composable
fun AppearanceSettingsScreen(
    appearancePrefs: AppearancePreferences,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentMode by appearancePrefs.appearanceMode.collectAsState()
    val reduceAnimations by appearancePrefs.reduceAnimations.collectAsState()
    val fontStyle by appearancePrefs.fontStyle.collectAsState()
    val appTextSize by appearancePrefs.appTextSize.collectAsState()
    val chatTextSize by appearancePrefs.chatTextSize.collectAsState()

    var showFontDialog by remember { mutableStateOf(false) }

    val isDark = isNexaDarkTheme()
    val isAmoled = isNexaAmoled()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    SettingsDetailPage(
        title = "Appearance",
        onBack = onNavigateBack,
        modifier = modifier
    ) {
        Text(
            text = "Personalize NEXA's visual theme, font style, and typography scale. Changes apply immediately and persist across sessions.",
            fontSize = 13.sp,
            color = secondaryText,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        // -------------------------------------------------------------
        // 1. THEME SECTION
        // -------------------------------------------------------------
        SettingsSectionLabel("Theme Mode")

        // System Default
        ThemeSelectionCard(
            title = "System Default",
            description = "Automatically adapt to your device's system appearance.",
            mode = AppearanceMode.SYSTEM,
            isSelected = currentMode == AppearanceMode.SYSTEM,
            onSelect = { appearancePrefs.setAppearanceMode(AppearanceMode.SYSTEM) }
        )

        // Dark
        ThemeSelectionCard(
            title = "Dark Charcoal",
            description = "Comfortable dark surfaces with distinct elevated cards.",
            mode = AppearanceMode.DARK,
            isSelected = currentMode == AppearanceMode.DARK,
            onSelect = { appearancePrefs.setAppearanceMode(AppearanceMode.DARK) }
        )

        // AMOLED
        ThemeSelectionCard(
            title = "AMOLED Pure Black",
            description = "True #000000 black background for maximum OLED battery savings.",
            mode = AppearanceMode.AMOLED,
            isSelected = currentMode == AppearanceMode.AMOLED,
            onSelect = { appearancePrefs.setAppearanceMode(AppearanceMode.AMOLED) }
        )

        // Light
        ThemeSelectionCard(
            title = "Clean Light",
            description = "Crisp white surfaces with balanced slate contrast.",
            mode = AppearanceMode.LIGHT,
            isSelected = currentMode == AppearanceMode.LIGHT,
            onSelect = { appearancePrefs.setAppearanceMode(AppearanceMode.LIGHT) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // -------------------------------------------------------------
        // 2. FONT & TYPOGRAPHY SECTION
        // -------------------------------------------------------------
        SettingsSectionLabel("Font & Text Scaling")

        // Font Style Row
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFontDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TextFields,
                        contentDescription = "Font Style",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Font Family",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${fontStyle.displayName} (Tap to change)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = fontStyle.fontFamily
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = secondaryText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // App Text Size Selector
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FormatSize,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "App Text Size",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryText
                        )
                    }
                    Text(
                        text = "${appTextSize.displayName} (${(appTextSize.appScale * 100).toInt()}%)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Controls headers, labels, and overall user interface elements.",
                    fontSize = 11.sp,
                    color = secondaryText,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Segmented Selector
                TextSizeSegmentedRow(
                    selectedSize = appTextSize,
                    onSelectSize = { appearancePrefs.setAppTextSize(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Chat Text Size Selector
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Chat Text Size",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryText
                        )
                    }
                    Text(
                        text = "${chatTextSize.displayName} (${(chatTextSize.chatScale * 100).toInt()}%)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Independently adjusts chat message bubble readability without scaling the entire UI.",
                    fontSize = 11.sp,
                    color = secondaryText,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Segmented Selector
                TextSizeSegmentedRow(
                    selectedSize = chatTextSize,
                    onSelectSize = { appearancePrefs.setChatTextSize(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // -------------------------------------------------------------
        // 3. LIVE INTERACTIVE PREVIEW CARD
        // -------------------------------------------------------------
        SettingsSectionLabel("Live Preview")
        LiveTypographyAndChatPreviewCard(
            fontStyle = fontStyle,
            appTextSize = appTextSize,
            chatTextSize = chatTextSize,
            isDark = isDark,
            isAmoled = isAmoled
        )

        Spacer(modifier = Modifier.height(10.dp))

        // -------------------------------------------------------------
        // 4. RESET FONT & SIZING ACTION
        // -------------------------------------------------------------
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { appearancePrefs.resetFontAndText() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reset Font & Text Size",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Restores Default NEXA font and 100% sizing. Active theme remains unchanged.",
                        fontSize = 11.sp,
                        color = secondaryText,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -------------------------------------------------------------
        // 5. MOTION & ACCESSIBILITY SECTION
        // -------------------------------------------------------------
        SettingsSectionLabel("Motion & Accessibility")
        SettingsCard {
            SettingsSwitchRow(
                title = "Reduce Animations",
                subtitle = "Reduce motion and visual transitions throughout NEXA",
                checked = reduceAnimations,
                onCheckedChange = { appearancePrefs.setReduceAnimations(it) }
            )
        }
    }

    // Font Selection Dialog
    if (showFontDialog) {
        FontSelectionDialog(
            currentStyle = fontStyle,
            onSelect = { style ->
                appearancePrefs.setFontStyle(style)
                showFontDialog = false
            },
            onDismiss = { showFontDialog = false }
        )
    }
}

/**
 * Segmented row for selecting text sizes (XS, S, M, L, XL).
 */
@Composable
private fun TextSizeSegmentedRow(
    selectedSize: NexaTextSize,
    onSelectSize: (NexaTextSize) -> Unit
) {
    val isDark = isNexaDarkTheme()
    val bgTrackColor = if (isDark) Color(0x33000000) else Color(0x15000000)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgTrackColor, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NexaTextSize.entries.forEach { size ->
            val isSelected = size == selectedSize
            val activeColor = MaterialTheme.colorScheme.primary
            val itemBg = if (isSelected) activeColor else Color.Transparent
            val textColor = if (isSelected) {
                if (isDark) Color(0xFF000000) else Color.White
            } else {
                if (isDark) Color(0xFFA0A7B5) else Color(0xFF666666)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(itemBg)
                    .clickable { onSelectSize(size) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = size.shortLabel,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor
                )
            }
        }
    }
}

/**
 * Live Interactive Dual-Mode Preview:
 * 1. General UI Hierarchy: Headline, Body, and Pill Badge in selected Font & App Text Scale.
 * 2. Real Chat Bubbles: Inbound & Outbound messages in selected Font & Chat Text Scale.
 */
@Composable
private fun LiveTypographyAndChatPreviewCard(
    fontStyle: NexaFontStyle,
    appTextSize: NexaTextSize,
    chatTextSize: NexaTextSize,
    isDark: Boolean,
    isAmoled: Boolean
) {
    val surfaceColor = when {
        !isDark -> Color(0xEEF8F9FA)
        isAmoled -> Color(0xCC141416)
        else -> NexaDarkCharcoalSurface
    }
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val appScale = appTextSize.appScale
    val chatScale = chatTextSize.chatScale
    val selectedFont = fontStyle.fontFamily

    NexaGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        surfaceColor = surfaceColor,
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // UI Hierarchy Preview Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "UI Hierarchy Preview",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.5.sp
                )

                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = fontStyle.displayName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = selectedFont
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Headline Text
            Text(
                text = "NEXA Secure Communication",
                fontSize = (18f * appScale).sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                fontFamily = selectedFont,
                lineHeight = (24f * appScale).sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Body Text
            Text(
                text = "Quantum-resistant encryption with offline mesh routing and zero cloud exposure.",
                fontSize = (13f * appScale).sp,
                color = secondaryText,
                fontFamily = selectedFont,
                lineHeight = (18f * appScale).sp
            )

            Spacer(modifier = Modifier.height(16.dp))
            SettingsDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Chat Message Bubbles Preview Header
            Text(
                text = "Chat Message Preview (Scale: ${(chatScale * 100).toInt()}%)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Inbound Bubble
            val inboundBubbleBg = if (isDark) Color(0x33FFFFFF) else Color(0xFFE2E8F0)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .background(
                            inboundBubbleBg,
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = "Hey! How does the new font and text scaling look on your screen?",
                            fontSize = (14.5f * chatScale).sp,
                            lineHeight = (19f * chatScale).sp,
                            color = primaryText,
                            fontFamily = selectedFont
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "10:42 AM",
                            fontSize = 10.sp,
                            color = secondaryText,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Outbound Bubble
            val outboundBubbleBg = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.28f else 0.20f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .background(
                            outboundBubbleBg,
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = "Looks crisp, comfortable, and perfectly balanced across all themes! ✨",
                            fontSize = (14.5f * chatScale).sp,
                            lineHeight = (19f * chatScale).sp,
                            color = primaryText,
                            fontFamily = selectedFont
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "10:43 AM",
                                fontSize = 10.sp,
                                color = secondaryText
                            )
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Delivered",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modal dialog for choosing one of the 5 platform-supported fonts.
 */
@Composable
private fun FontSelectionDialog(
    currentStyle: NexaFontStyle,
    onSelect: (NexaFontStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isNexaDarkTheme()
    val isAmoled = isNexaAmoled()
    val dialogBg = when {
        !isDark -> Color(0xFFFFFFFF)
        isAmoled -> Color(0xFF101012)
        else -> NexaDarkCharcoalSurface
    }
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogBg,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.TextFields,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Select Font Family",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Select a font family for the application. All options render with zero external downloads.",
                    fontSize = 12.sp,
                    color = secondaryText,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                NexaFontStyle.entries.forEach { style ->
                    val isSelected = style == currentStyle
                    val cardBg = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        if (isDark) Color(0x1AFFFFFF) else Color(0x0A000000)
                    }
                    val borderColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cardBg)
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable { onSelect(style) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = style.displayName,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = primaryText,
                                fontFamily = style.fontFamily
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Packaged offline • The quick brown fox jumps",
                                fontSize = 11.sp,
                                color = secondaryText,
                                fontFamily = style.fontFamily
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Close", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

@Composable
private fun ThemeSelectionCard(
    title: String,
    description: String,
    mode: AppearanceMode,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val isDark = isNexaDarkTheme()
    val isAmoled = isNexaAmoled()
    val surfaceColor = when {
        !isDark -> Color(0xEEF8F9FA)
        isAmoled -> Color(0xCC181818)
        else -> NexaDarkCharcoalSurface
    }
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
            ThemePreviewSwatch(mode = mode, isSelected = isSelected)

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

@Composable
private fun ThemePreviewSwatch(
    mode: AppearanceMode,
    isSelected: Boolean
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0x33888888)

    Box(
        modifier = Modifier
            .size(44.dp)
            .then(
                when (mode) {
                    AppearanceMode.SYSTEM -> Modifier.background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFFF1F5F9), Color(0xFF13151A))
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    AppearanceMode.DARK -> Modifier.background(
                        color = NexaDarkCharcoalBackground,
                        shape = RoundedCornerShape(12.dp)
                    )
                    AppearanceMode.AMOLED -> Modifier.background(
                        color = NexaPureBlack,
                        shape = RoundedCornerShape(12.dp)
                    )
                    AppearanceMode.LIGHT -> Modifier.background(
                        color = NexaPureWhite,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            )
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        when (mode) {
            AppearanceMode.SYSTEM -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_theme),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            AppearanceMode.DARK -> {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(NexaDarkCharcoalSurface, RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }
            AppearanceMode.AMOLED -> {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color(0xCC161618), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }
            AppearanceMode.LIGHT -> {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0x1F000000), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }
        }
    }
}
