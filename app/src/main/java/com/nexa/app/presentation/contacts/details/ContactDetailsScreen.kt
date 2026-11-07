package com.nexa.app.presentation.contacts.details

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contact Details Screen
 *
 * Implements:
 * 1. Top Bar with back navigation
 * 2. Contact Information Card: Avatar, Name, Phone Number
 * 3. Additional Information: Sync with Phone (ON/OFF), Connected Email
 * 4. Remove Contact Destructive Action with confirmation dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailsScreen(
    participantNxId: String,
    conversationId: String,
    contactDao: ContactDao,
    userDao: UserDao,
    contactRepository: ContactRepository,
    onBack: () -> Unit,
    onContactRemoved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val screenBg = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)
    val dangerColor = MaterialTheme.colorScheme.error

    var contact by remember { mutableStateOf<ContactEntity?>(null) }
    var userEmail by remember { mutableStateOf<String?>(null) }
    var isPhoneSyncEnabled by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(participantNxId) {
        withContext(Dispatchers.IO) {
            val c = contactDao.getContact(participantNxId)
            contact = c
            userEmail = null // Enforces Zero-Knowledge: peer emails are never exposed or queried
            isPhoneSyncEnabled = c?.isPhoneInLocalAddressBook ?: false
        }
    }

    val displayName = contact?.effectiveDisplayName ?: participantNxId
    val phoneNumber = contact?.phoneNumber ?: "Not available"

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Contact Details",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = primaryTextColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = primaryTextColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. Contact Information Card
            NexaGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                elevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NexaAvatar(
                        nexaAvatarUrl = contact?.effectiveAvatarUrl,
                        deviceContactPhotoUri = contact?.deviceContactPhotoUri,
                        displayName = displayName,
                        size = 56.dp
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = primaryTextColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = phoneNumber,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = secondaryTextColor
                        )
                    }
                }
            }

            // 2. Additional Information Card (Sync with Phone & Email)
            NexaGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                elevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // Sync with Phone
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sync with Phone",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                ),
                                color = primaryTextColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isPhoneSyncEnabled) "Matched in device address book" else "NEXA-only connection",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = secondaryTextColor
                            )
                        }

                        Switch(
                            checked = isPhoneSyncEnabled,
                            onCheckedChange = { enabled ->
                                isPhoneSyncEnabled = enabled
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (enabled) "Phone contact sync linked" else "Phone contact sync detached"
                                    )
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                            )
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = dividerColor,
                        thickness = 1.dp
                    )

                    // Connected Email
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Connected Email",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                ),
                                color = primaryTextColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = userEmail ?: "No email linked by contact",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = secondaryTextColor
                            )
                        }
                    }
                }
            }

            // 3. Remove Contact Destructive Action
            NexaGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRemoveDialog = true },
                shape = RoundedCornerShape(22.dp),
                surfaceColor = surfaceColor,
                borderColor = borderColor,
                elevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_delete),
                        contentDescription = "Remove Contact",
                        tint = dangerColor,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Remove Contact",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = dangerColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Remove from saved contacts without deleting chat",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = dangerColor.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }

        // Confirmation Dialog
        if (showRemoveDialog) {
            RemoveContactDialog(
                contactName = displayName,
                onConfirmRemove = {
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            contactDao.deleteContact(participantNxId)
                            contactRepository.removeConnection(participantNxId)
                        }
                        onContactRemoved()
                    }
                },
                onDismiss = { showRemoveDialog = false }
            )
        }
    }
}
