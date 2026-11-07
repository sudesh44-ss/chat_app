package com.nexa.app.presentation.settings.account

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.settings.components.SettingsHeader

/**
 * Account Status Screen.
 *
 * Implements two distinct and completely independent workflows:
 * 1. Account Deactivation: Reversible temporary pause.
 * 2. Account Deletion: Permanent irreversible data destruction.
 */
@Composable
fun AccountStatusScreen(
    onNavigateBack: () -> Unit,
    onStartDeactivation: () -> Unit,
    onStartDeletion: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            SettingsHeader(
                title = "Account Status",
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
                text = "Manage your account presence on the NEXA network.",
                fontSize = 13.sp,
                color = secondaryText
            )

            // Option 1: Account Deactivation (Temporary)
            StatusActionCard(
                title = "Account Deactivation",
                description = "Temporarily disable your account. You can reactivate it later.",
                iconRes = R.drawable.ic_nexa_help,
                accentColor = NexaWarningOrange,
                onClick = onStartDeactivation
            )

            // Option 2: Account Deletion (Permanent)
            StatusActionCard(
                title = "Account Deletion",
                description = "Permanently delete your account and applicable associated data.",
                iconRes = R.drawable.ic_nexa_delete,
                accentColor = MaterialTheme.colorScheme.error,
                onClick = onStartDeletion
            )
        }
    }
}

@Composable
private fun StatusActionCard(
    title: String,
    description: String,
    iconRes: Int,
    accentColor: Color,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA)
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    NexaGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        surfaceColor = surfaceColor,
        borderColor = accentColor.copy(alpha = 0.25f),
        elevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(46.dp),
                contentAlignment = Alignment.Center
            ) {
                NexaGlassSurface(
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    surfaceColor = accentColor.copy(alpha = if (isDark) 0.18f else 0.12f),
                    borderColor = accentColor.copy(alpha = 0.35f),
                    elevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier.size(46.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = title,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = secondaryText,
                    lineHeight = 17.sp
                )
            }

            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_chevron_right),
                contentDescription = null,
                tint = secondaryText,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
