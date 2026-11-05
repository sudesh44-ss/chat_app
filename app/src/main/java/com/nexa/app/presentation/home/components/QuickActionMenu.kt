package com.nexa.app.presentation.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class QuickActionItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionBottomSheet(
    onDismiss: () -> Unit,
    onNavigateNearbyMesh: () -> Unit,
    onNavigateConnectionRequests: () -> Unit,
    onNavigateNewConversation: () -> Unit,
    onNavigateContacts: () -> Unit,
    onNavigateCreateGroup: () -> Unit
) {
    // Exact strict order: 1 to 5
    val menuItems = listOf(
        QuickActionItem(
            title = "Nearby Mesh Discovery",
            subtitle = "Discover local Bluetooth & Wi-Fi peers",
            icon = Icons.Default.CellTower,
            onClick = { onDismiss(); onNavigateNearbyMesh() }
        ),
        QuickActionItem(
            title = "Connection Requests",
            subtitle = "View/manage incoming connection requests",
            icon = Icons.Default.PersonAdd,
            onClick = { onDismiss(); onNavigateConnectionRequests() }
        ),
        QuickActionItem(
            title = "New Conversation",
            subtitle = "Start encrypted direct chat",
            icon = Icons.AutoMirrored.Filled.Chat,
            onClick = { onDismiss(); onNavigateNewConversation() }
        ),
        QuickActionItem(
            title = "Contacts",
            subtitle = "View and message address book peers",
            icon = Icons.Default.Contacts,
            onClick = { onDismiss(); onNavigateContacts() }
        ),
        QuickActionItem(
            title = "Create Group",
            subtitle = "Start encrypted multi-peer mesh group",
            icon = Icons.Default.GroupAdd,
            onClick = { onDismiss(); onNavigateCreateGroup() }
        )
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "New Message / Group",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            menuItems.forEach { item ->
                QuickActionRow(item = item)
            }
        }
    }
}

@Composable
private fun QuickActionRow(item: QuickActionItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
