package com.nexa.app.presentation.contacts.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.identity.PeerIdentityResolver
import com.nexa.app.core.identity.ResolvedPeerIdentity
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.viewer.ProfileImageViewerDialog

/**
 * 3-Tier Identity Profile Dialog for NEXA.
 * Clearly separates and displays:
 * 1. Tier 1: Local Nickname (Zero-Knowledge alias on this device)
 * 2. Tier 2: Phone Contact Book (Device address-book name)
 * 3. Tier 3: Registered Profile Identity (Cryptographically signed by peer)
 *
 * Prevents phishing/impersonation while maintaining full personal convenience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerProfileDialog(
    nxId: String,
    originalRegisteredName: String,
    customNickname: String?,
    deviceContactName: String?,
    avatarUrl: String? = null,
    deviceContactPhotoUri: String? = null,
    onEditNickname: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resolvedIdentity = PeerIdentityResolver.resolveIdentityDisplay(
        customNickname = customNickname,
        deviceContactName = deviceContactName,
        originalRegisteredName = originalRegisteredName,
        nxId = nxId
    )

    var fullScreenPhotoModel by remember { mutableStateOf<Any?>(null) }

    if (fullScreenPhotoModel != null) {
        ProfileImageViewerDialog(
            imageModel = fullScreenPhotoModel!!,
            onDismiss = { fullScreenPhotoModel = null }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NexaAvatar(
                        nexaAvatarUrl = avatarUrl,
                        deviceContactPhotoUri = deviceContactPhotoUri,
                        displayName = resolvedIdentity.primaryDisplayName,
                        size = 36.dp,
                        onPhotoClick = { model -> fullScreenPhotoModel = model }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Peer Identity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header Display Name Summary with 72dp Hero Avatar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NexaAvatar(
                            nexaAvatarUrl = avatarUrl,
                            deviceContactPhotoUri = deviceContactPhotoUri,
                            displayName = resolvedIdentity.primaryDisplayName,
                            size = 72.dp,
                            onPhotoClick = { model -> fullScreenPhotoModel = model }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = resolvedIdentity.primaryDisplayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        resolvedIdentity.topBarSubtitle?.let { sub ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // --- TIER 1: Local Custom Nickname Card ---
                IdentityTierCard(
                    tierNumber = 1,
                    tierTitle = "Local Nickname",
                    tierBadge = "Tier 1 • Client Only",
                    badgeColor = NexaWarningOrange,
                    icon = Icons.Default.Edit,
                    valueText = customNickname?.takeIf { it.isNotBlank() } ?: "None set",
                    isValueSet = !customNickname.isNullOrBlank(),
                    description = "Stored strictly in local SQLCipher vault. Zero server leakage. Overrides all other names.",
                    actionButton = {
                        OutlinedButton(
                            onClick = onEditNickname,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (customNickname.isNullOrBlank()) "Set" else "Edit", fontSize = 11.sp)
                        }
                    }
                )

                // --- TIER 2: Device Contact Book Card ---
                IdentityTierCard(
                    tierNumber = 2,
                    tierTitle = "Device Address Book",
                    tierBadge = "Tier 2 • On-Device",
                    badgeColor = MaterialTheme.colorScheme.primary,
                    icon = Icons.Default.ContactPhone,
                    valueText = deviceContactName?.takeIf { it.isNotBlank() } ?: "Not in phone contacts",
                    isValueSet = !deviceContactName.isNullOrBlank(),
                    description = "Read directly from your phone's address book via ContentResolver. Never uploaded.",
                    actionButton = null
                )

                // --- TIER 3: Registered Profile Identity Card ---
                IdentityTierCard(
                    tierNumber = 3,
                    tierTitle = "Registered Profile",
                    tierBadge = "Tier 3 • Verified",
                    badgeColor = NexaVerifiedGreen,
                    icon = Icons.Default.VerifiedUser,
                    valueText = originalRegisteredName.ifBlank { "Unknown" },
                    isValueSet = originalRegisteredName.isNotBlank(),
                    description = "Cryptographically signed by peer using Ed25519 identity key. Immutable network profile.",
                    actionButton = null
                )

                // --- NX-ID Public Handle Card ---
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PERMANENT NX-ID",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = nxId,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = NexaBadgeMonospace
                            )
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("NX-ID", nxId)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "NX-ID copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy NX-ID",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun IdentityTierCard(
    tierNumber: Int,
    tierTitle: String,
    tierBadge: String,
    badgeColor: androidx.compose.ui.graphics.Color,
    icon: ImageVector,
    valueText: String,
    isValueSet: Boolean,
    description: String,
    actionButton: (@Composable () -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isValueSet) badgeColor.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: Title + Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tierTitle,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Surface(
                    color = badgeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = tierBadge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 9.sp
                        ),
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Value + Optional Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isValueSet) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (isValueSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )

                actionButton?.invoke()
            }

            // Explanatory note
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}
