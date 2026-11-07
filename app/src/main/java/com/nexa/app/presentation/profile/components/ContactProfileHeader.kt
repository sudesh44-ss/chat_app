package com.nexa.app.presentation.profile.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Contact Profile Header
 *
 * Implements:
 * - Back button
 * - Centered 128dp circular avatar with subtle glass border
 * - Online status indicator at bottom-right of avatar
 * - Circular glass Edit User Detail button at upper-right of avatar section
 * - Contact Display Name, secondary alias, and copyable NEXA ID capsule badge
 */
@Composable
fun ContactProfileHeader(
    displayName: String,
    customNickname: String?,
    originalRegisteredName: String?,
    nxId: String,
    avatarUrl: String?,
    deviceContactPhotoUri: String?,
    isOnline: Boolean,
    onBack: () -> Unit,
    onEditDetails: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val avatarBorderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val statusDotBorder = if (isDark) Color.Black else Color.White
    val editBtnSurface = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Back Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = primaryTextColor
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Center Avatar Container with Online Indicator and Edit Button
        Box(
            modifier = Modifier.size(144.dp),
            contentAlignment = Alignment.Center
        ) {
            // Large Avatar (128dp)
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, avatarBorderColor, CircleShape)
                    .clickable { onAvatarClick() },
                contentAlignment = Alignment.Center
            ) {
                NexaAvatar(
                    nexaAvatarUrl = avatarUrl,
                    deviceContactPhotoUri = deviceContactPhotoUri,
                    displayName = displayName,
                    size = 128.dp
                )
            }

            // Online Indicator Dot at Bottom-Right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 8.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(statusDotBorder)
                    .padding(2.5.dp)
                    .clip(CircleShape)
                    .background(if (isOnline) NexaVerifiedGreen else Color(0xFF737C8D))
            )

            // Edit User Detail Button at Top-Right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 4.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(editBtnSurface)
                    .border(1.dp, avatarBorderColor, CircleShape)
                    .clickable { onEditDetails() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_profile_edit),
                    contentDescription = "Edit User Detail",
                    tint = primaryTextColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Contact Primary Display Name
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            ),
            color = primaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Secondary Display Name / Registered Name if alias set
        if (!customNickname.isNullOrBlank() && !originalRegisteredName.isNullOrBlank() && customNickname != originalRegisteredName) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Registered: $originalRegisteredName",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = secondaryTextColor
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // NEXA ID Badge (Copy on click)
        NexaGlassSurface(
            modifier = Modifier
                .clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NEXA ID", nxId))
                    Toast.makeText(context, "Copied NEXA ID", Toast.LENGTH_SHORT).show()
                },
            shape = RoundedCornerShape(20.dp),
            surfaceColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f),
            borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
            elevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = nxId,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp
                    ),
                    color = primaryTextColor.copy(alpha = 0.85f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy ID",
                    tint = secondaryTextColor,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
