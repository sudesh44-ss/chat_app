package com.nexa.app.presentation.settings.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.qr.QrBitmapGenerator
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.dashboard.components.BottomNavDestination
import com.nexa.app.presentation.dashboard.components.NexaFloatingBottomNavigation
import com.nexa.app.presentation.settings.components.NexaSettingsItem
import com.nexa.app.presentation.settings.components.SettingsHeader
import kotlinx.coroutines.launch

/**
 * Account Screen divided into 3 authoritative sections:
 * A. Account Information (Avatar Hero, Display Name, Gmail, Phone Number, Age, Gender, NEXA ID, QR Code)
 * B. Performance & Security (Change Phone Number, Security & Encryption)
 * C. Account Management (Account Status)
 */
@Composable
fun AccountScreen(
    authRepository: AuthRepository,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateProfile: () -> Unit,
    onNavigateChangePhone: () -> Unit,
    onNavigateSecurity: () -> Unit,
    onNavigateAccountStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val context = LocalContext.current
    val profileState by authRepository.getActiveProfile().collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var showEditNameDialog by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }
    var isUpdatingName by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Synchronize live profile from remote PostgreSQL backend on mount
    LaunchedEffect(Unit) {
        authRepository.fetchRemoteProfile()
    }

    val profile = profileState
    val displayName = profile?.fullName ?: "NEXA User"
    val phoneNumber = profile?.phoneNumber?.takeIf { it.isNotBlank() } ?: "Not linked"
    val email = profile?.email?.takeIf { it.isNotBlank() } ?: "Not linked"
    val nxId = profile?.nxId ?: "NX-PENDING"
    val avatarUrl = profile?.avatarUrl
    val ageText = profile?.age?.let { "$it years" } ?: "Not specified"
    val genderText = profile?.gender?.takeIf { it.isNotBlank() } ?: "Not specified"

    // Generate permanent NX-ID QR Code bitmap
    LaunchedEffect(nxId) {
        if (nxId.isNotBlank() && nxId != "NX-PENDING") {
            try {
                qrBitmap = QrBitmapGenerator.generate("nexa://user?id=$nxId", sizePx = 360)
            } catch (e: Exception) {
                // Non-fatal
            }
        }
    }

    // Edit Name Dialog
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = {
                Text(
                    text = "Edit Display Name",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter your preferred public display name for NEXA contacts.",
                        fontSize = 13.sp,
                        color = secondaryText
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editNameText,
                        onValueChange = { editNameText = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editNameText.isNotBlank()) {
                            isUpdatingName = true
                            coroutineScope.launch {
                                val result = authRepository.updateDisplayName(editNameText.trim())
                                isUpdatingName = false
                                showEditNameDialog = false
                                if (result.isSuccess) {
                                    snackbarHostState.showSnackbar("Display name updated successfully")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to update name: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        }
                    },
                    enabled = !isUpdatingName
                ) {
                    if (isUpdatingName) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = backgroundColor,
        bottomBar = {
            NexaFloatingBottomNavigation(
                currentDestination = BottomNavDestination.PROFILE,
                showPlusButton = false,
                onNavigateHome = onNavigateHome,
                onNavigateProfile = onNavigateProfile
            )
        },
        topBar = {
            SettingsHeader(
                title = "Account",
                onBack = onNavigateBack
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ==========================================
            // SECTION A: Account Information
            // ==========================================
            SectionHeader(title = "Account Information")

            // Profile Photo Hero with Verified Shield
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        NexaAvatar(
                            nexaAvatarUrl = avatarUrl,
                            deviceContactPhotoUri = null,
                            displayName = displayName,
                            size = 84.dp,
                            modifier = Modifier.clip(CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(NexaVerifiedGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Verified Identity",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = primaryText
                    )
                    if (email != "Not linked") {
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText
                        )
                    }
                }
            }

            // 1. Display Name
            AccountInfoCard(
                label = "Display Name",
                value = displayName,
                iconRes = R.drawable.ic_nexa_profile_edit,
                onEditClick = {
                    editNameText = displayName
                    showEditNameDialog = true
                }
            )

            // 2. Gmail / Verified Email
            AccountInfoCard(
                label = "Gmail Address",
                value = email,
                iconRes = R.drawable.ic_nexa_badge_check,
                isReadOnly = true
            )

            // 3. Phone Number
            AccountInfoCard(
                label = "Phone Number",
                value = phoneNumber,
                iconRes = R.drawable.ic_nexa_phone_call,
                onEditClick = onNavigateChangePhone
            )

            // 4. Age
            AccountInfoCard(
                label = "Age",
                value = ageText,
                iconRes = R.drawable.ic_nexa_profile,
                isReadOnly = true
            )

            // 5. Gender
            AccountInfoCard(
                label = "Gender",
                value = genderText,
                iconRes = R.drawable.ic_nexa_profile,
                isReadOnly = true
            )

            // 6. NEXA ID (Locked Unique Cryptographic Identity)
            AccountInfoCard(
                label = "NEXA ID",
                value = nxId,
                iconRes = R.drawable.ic_nexa_badge_check,
                isReadOnly = true
            )

            // 7. Identity QR Code Card
            if (qrBitmap != null) {
                NexaGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA),
                    elevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "IDENTITY QR CODE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        letterSpacing = 1.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Surface(
                                color = NexaVerifiedGreen.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = NexaVerifiedGreen,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(170.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "My Identity QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = nxId,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                ),
                                color = primaryText
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("NX-ID", nxId))
                                    Toast.makeText(context, "NX-ID copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy NX-ID",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Text(
                            text = "Scannable by NEXA contacts to establish verified peer-to-peer connection.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = secondaryText
                        )
                    }
                }
            }

            // ==========================================
            // SECTION B: Performance & Security
            // ==========================================
            SectionHeader(title = "Performance & Security")

            NexaSettingsItem(
                title = "Change Phone Number",
                subtitle = "Update or re-verify your linked phone number.",
                iconRes = R.drawable.ic_nexa_phone_call,
                onClick = onNavigateChangePhone
            )

            NexaSettingsItem(
                title = "Security & Encryption",
                subtitle = "Identity Keys, Session State and Certificates.",
                iconRes = R.drawable.ic_nexa_security_lock,
                onClick = onNavigateSecurity
            )

            // ==========================================
            // SECTION C: Account Management
            // ==========================================
            SectionHeader(title = "Account Management")

            NexaSettingsItem(
                title = "Account Status",
                subtitle = "Deactivate or delete your account.",
                iconRes = R.drawable.ic_nexa_delete,
                iconTint = MaterialTheme.colorScheme.error,
                onClick = onNavigateAccountStatus
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val isDark = isSystemInDarkTheme()
    val color = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = color,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/**
 * 18-20dp rounded Account Information Card.
 */
@Composable
private fun AccountInfoCard(
    label: String,
    value: String,
    iconRes: Int,
    isReadOnly: Boolean = false,
    onEditClick: (() -> Unit)? = null
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA)
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    NexaGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onEditClick != null && !isReadOnly) Modifier.clickable(onClick = onEditClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        surfaceColor = surfaceColor,
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                NexaGlassSurface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.15f else 0.1f),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    elevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier.size(38.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = label,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = secondaryText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryText
                )
            }

            if (!isReadOnly && onEditClick != null) {
                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_profile_edit),
                        contentDescription = "Edit $label",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else if (isReadOnly) {
                Text(
                    text = "Locked",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = secondaryText,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
    }
}
