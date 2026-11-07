package com.nexa.app.presentation.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * Standard Settings Header with back button, centered title, and subtle divider.
 */
@Composable
fun SettingsHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val contentColor = if (isDark) Color.White else Color.Black
    val dividerColor = if (isDark) Color(0x22FFFFFF) else Color(0x18000000)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(42.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_arrow_back),
                    contentDescription = "Back",
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        HorizontalDivider(
            color = dividerColor,
            thickness = 1.dp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Individual Settings Item with left icon area, title, optional subtitle, and trailing navigation chevron.
 */
@Composable
fun NexaSettingsItem(
    title: String,
    iconRes: Int,
    subtitle: String? = null,
    badgeText: String? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val defaultIconTint = iconTint ?: (if (isDark) Color.White else Color(0xFF222222))
    val surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA)

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        surfaceColor = surfaceColor,
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Container
            Box(
                modifier = Modifier
                    .size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                NexaGlassSurface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    surfaceColor = defaultIconTint.copy(alpha = if (isDark) 0.15f else 0.1f),
                    borderColor = defaultIconTint.copy(alpha = 0.25f),
                    elevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier.size(42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = title,
                            tint = defaultIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Title & Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryText
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = secondaryText
                    )
                }
            }

            // Optional Badge Text
            if (!badgeText.isNullOrBlank()) {
                Text(
                    text = badgeText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            // Right Chevron
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_chevron_right),
                contentDescription = null,
                tint = secondaryText,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
