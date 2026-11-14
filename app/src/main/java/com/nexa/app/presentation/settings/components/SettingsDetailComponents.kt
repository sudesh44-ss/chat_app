package com.nexa.app.presentation.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Shared detail-page scaffolding for every Settings sub-screen. Reuses [SettingsHeader]
 * and [NexaGlassSurface] so new sections match the existing Settings visual language.
 */
@Composable
fun SettingsDetailPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White

    Scaffold(
        containerColor = backgroundColor,
        topBar = { SettingsHeader(title = title, onBack = onBack) },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    val isDark = isSystemInDarkTheme()
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = if (isDark) Color(0xFF8E96A3) else Color(0xFF6B7280),
        modifier = Modifier.padding(start = 6.dp, top = 4.dp)
    )
}

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    NexaGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun SettingsDivider() {
    val isDark = isSystemInDarkTheme()
    HorizontalDivider(
        color = if (isDark) Color(0x1FFFFFFF) else Color(0x14000000),
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primary = if (isDark) Color.White else Color(0xFF111111)
    val secondary = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val alpha = if (enabled) 1f else 0.45f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = primary.copy(alpha = alpha)
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = secondary.copy(alpha = alpha)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
fun SettingsRadioRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primary = if (isDark) Color.White else Color(0xFF111111)
    val secondary = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = primary
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = secondary)
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_check),
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primary = if (isDark) Color.White else Color(0xFF111111)
    val secondary = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = primary)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = secondary)
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = secondary,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.size(6.dp))
        Icon(
            painter = painterResource(id = R.drawable.ic_nexa_chevron_right),
            contentDescription = null,
            tint = secondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val danger = Color(0xFFE5484D)
    val primary = when {
        !enabled -> (if (isDark) Color(0xFF6D6D6D) else Color(0xFFAAAAB0))
        destructive -> danger
        else -> (if (isDark) Color.White else Color(0xFF111111))
    }
    val secondary = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = primary)
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = secondary)
        }
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isDark) Color.White else Color(0xFF111111),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun SettingsInfoBanner(text: String, accent: Color = MaterialTheme.colorScheme.primary) {
    val isDark = isSystemInDarkTheme()
    NexaGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        surfaceColor = accent.copy(alpha = if (isDark) 0.14f else 0.10f),
        borderColor = accent.copy(alpha = 0.30f),
        elevation = 0.dp
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = if (isDark) Color(0xFFD8DEE9) else Color(0xFF3A4150),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}
