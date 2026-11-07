package com.nexa.app.presentation.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Floating Quick Action Menu.
 *
 * Exact mandatory 5-item order:
 * 1. Nearby Mesh Discovery
 * 2. Connection Requests
 * 3. New Conversation
 * 4. Contacts
 * 5. Create Group
 */
@Composable
fun NexaQuickActionMenu(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onNavigateNearbyMesh: () -> Unit,
    onNavigateConnectionRequests: () -> Unit,
    onNavigateNewConversation: () -> Unit,
    onNavigateContacts: () -> Unit,
    onNavigateCreateGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss) // Scrim tap to dismiss
    ) {
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(tween(180)) + scaleIn(tween(220), transformOrigin = TransformOrigin(0.9f, 0.95f)),
            exit = fadeOut(tween(150)) + scaleOut(tween(180), transformOrigin = TransformOrigin(0.9f, 0.95f)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 90.dp, end = 20.dp)
        ) {
            NexaGlassSurface(
                modifier = Modifier.widthIn(min = 230.dp, max = 270.dp),
                shape = RoundedCornerShape(NexaGlassTokens.SmallCardRadius),
                elevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    // 1. Nearby Mesh Discovery
                    QuickActionMenuItem(
                        label = "Nearby Mesh Discovery",
                        iconRes = R.drawable.ic_nexa_mesh,
                        onClick = { onDismiss(); onNavigateNearbyMesh() }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // 2. Connection Requests
                    QuickActionMenuItem(
                        label = "Connection Requests",
                        iconRes = R.drawable.ic_nexa_handshake,
                        onClick = { onDismiss(); onNavigateConnectionRequests() }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // 3. New Conversation
                    QuickActionMenuItem(
                        label = "New Conversation",
                        iconRes = R.drawable.ic_nexa_chats,
                        onClick = { onDismiss(); onNavigateNewConversation() }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // 4. Contacts
                    QuickActionMenuItem(
                        label = "Contacts",
                        iconRes = R.drawable.ic_nexa_contacts,
                        onClick = { onDismiss(); onNavigateContacts() }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // 5. Create Group
                    QuickActionMenuItem(
                        label = "Create Group",
                        iconRes = R.drawable.ic_nexa_create_group,
                        onClick = { onDismiss(); onNavigateCreateGroup() }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionMenuItem(
    label: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
