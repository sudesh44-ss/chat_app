package com.nexa.app.presentation.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexa.app.core.contacts.ContactsProviderHelper
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.domain.model.DeviceContact
import com.nexa.app.presentation.common.avatar.NexaAvatar
import com.nexa.app.presentation.common.viewer.ProfileImageViewerDialog
import com.nexa.app.presentation.contacts.components.AddContactByPhoneDialog
import com.nexa.app.presentation.contacts.components.AddContactDialog
import com.nexa.app.presentation.contacts.components.EditNicknameDialog
import com.nexa.app.presentation.contacts.components.PeerProfileDialog

/**
 * Dedicated Contacts screen displaying device address-book contacts,
 * real-time NEXA user discovery, permission management, and contact addition.
 *
 * Strict Privacy: Full address-book entries are never uploaded or stored on any cloud server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    viewModel: ContactViewModel,
    onBack: () -> Unit = {},
    onStartChat: (nxId: String, participantName: String) -> Unit,
    onNavigateToPending: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    var showAddPhoneDialog by remember { mutableStateOf(false) }
    var showAddNxIdDialog by remember { mutableStateOf(false) }
    var contactForNicknameEdit by remember { mutableStateOf<ContactEntity?>(null) }
    var contactForProfileView by remember { mutableStateOf<ContactEntity?>(null) }
    var activeViewerPhoto by remember { mutableStateOf<Any?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val photoToView = activeViewerPhoto
    if (photoToView != null) {
        ProfileImageViewerDialog(
            imageModel = photoToView,
            onDismiss = { activeViewerPhoto = null }
        )
    }

    // 1. Permission Check and Request
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val permanentlyDenied = !isGranted && if (context is android.app.Activity) {
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                context,
                Manifest.permission.READ_CONTACTS
            )
        } else false
        viewModel.setPermissionState(isGranted, permanentlyDenied)
    }

    // Sync permission on Resume (e.g. returning from System Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
                viewModel.setPermissionState(hasPermission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setPermissionState(hasPermission)
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // 2. Real-Time Address-Book Synchronization via ContentObserver
    DisposableEffect(Unit) {
        val observer = ContactsProviderHelper.ContactsChangeObserver(coroutineScope) {
            viewModel.refreshContacts()
        }

        try {
            context.contentResolver.registerContentObserver(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                true,
                observer
            )
        } catch (e: Exception) {
            // Ignore if security or system issue
        }

        onDispose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Contacts",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${uiState.deviceContacts.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshContacts() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Contacts")
                    }
                    IconButton(onClick = { showAddNxIdDialog = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Discover User by NX-ID")
                    }
                    IconButton(onClick = { showAddPhoneDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact by Phone")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddNxIdDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact by NX-ID")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // 1. Interactive Search Field
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { Text("Search by name or number...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 2. Network Warning Banner (if offline/lookup failed, but keeping local contacts)
            if (uiState.isNetworkError) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Offline: Showing cached local contacts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = { viewModel.refreshContacts() }) {
                            Text("Retry", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 4. Content Area: Permission Denied State vs Loading vs Empty vs Contact List
            when {
                !uiState.isPermissionGranted -> {
                    PermissionDeniedCard(
                        isPermanentlyDenied = uiState.isPermissionDeniedPermanently,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        },
                        onOpenSettings = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        }
                    )
                }

                uiState.isLoading && uiState.deviceContacts.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Reading local address book...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                uiState.deviceContacts.isEmpty() && uiState.mutualFriends.isEmpty() -> {
                    EmptyContactsCard(
                        hasSearch = uiState.searchQuery.isNotEmpty(),
                        onAddContact = { showAddPhoneDialog = true }
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (uiState.mutualFriends.isNotEmpty()) {
                            item {
                                Text(
                                    text = "NEXA Contacts (${uiState.mutualFriends.size})",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            itemsIndexed(
                                items = uiState.mutualFriends,
                                key = { index, contact -> "mutual_${contact.nxId}_$index" }
                            ) { _, contact ->
                                MutualContactItemRow(
                                    contact = contact,
                                    onStartChat = {
                                        onStartChat(contact.nxId, contact.effectiveDisplayName)
                                    },
                                    onEditNickname = {
                                        contactForNicknameEdit = contact
                                    },
                                    onViewProfile = {
                                        contactForProfileView = contact
                                    },
                                    onPhotoClick = { photoModel ->
                                        activeViewerPhoto = photoModel
                                    }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )
                            }
                        }

                        if (uiState.deviceContacts.isNotEmpty()) {
                            if (uiState.mutualFriends.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Address Book (${uiState.deviceContacts.size})",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            itemsIndexed(
                                items = uiState.deviceContacts,
                                key = { index, contact -> "device_${contact.id}_${contact.normalizedNumber}_$index" }
                            ) { _, contact ->
                                DeviceContactItemRow(
                                    contact = contact,
                                    onStartChat = {
                                        if (contact.nexaNxId != null) {
                                            onStartChat(contact.nexaNxId, contact.displayName)
                                        }
                                    },
                                    onInvite = {
                                        val inviteIntent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("smsto:${contact.normalizedNumber}")
                                            putExtra("sms_body", "Hey! Connect with me on NEXA, the private end-to-end encrypted messenger.")
                                        }
                                        context.startActivity(inviteIntent)
                                    },
                                    onPhotoClick = { photoModel ->
                                        activeViewerPhoto = photoModel
                                    }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3-Tier Identity Profile Dialog
        contactForProfileView?.let { contact ->
            PeerProfileDialog(
                nxId = contact.nxId,
                originalRegisteredName = contact.displayName,
                customNickname = contact.customNickname,
                deviceContactName = contact.deviceContactName,
                avatarUrl = contact.effectiveAvatarUrl,
                deviceContactPhotoUri = contact.deviceContactPhotoUri,
                onEditNickname = {
                    contactForNicknameEdit = contact
                    contactForProfileView = null
                },
                onRemoveConnection = {
                    viewModel.removeConnection(contact.nxId)
                },
                onBlockUser = {
                    viewModel.blockUser(contact.nxId)
                },
                onDismiss = { contactForProfileView = null }
            )
        }

        // Edit Nickname Modal Dialog
        contactForNicknameEdit?.let { contact ->
            EditNicknameDialog(
                contactNxId = contact.nxId,
                originalProfileName = contact.displayName,
                currentNickname = contact.customNickname,
                onSaveNickname = { newNick ->
                    viewModel.setContactNickname(contact.nxId, newNick)
                    contactForNicknameEdit = null
                },
                onDismiss = { contactForNicknameEdit = null }
            )
        }

        // Add Contact by Phone Modal Dialog
        if (showAddPhoneDialog) {
            AddContactByPhoneDialog(
                onDismiss = { showAddPhoneDialog = false },
                onLookupPhone = { phone, callback ->
                    viewModel.lookupSinglePhone(phone, callback)
                },
                onStartChat = { nxId, name ->
                    showAddPhoneDialog = false
                    onStartChat(nxId, name)
                }
            )
        }

        // Phase D: Discover User by NX-ID & Send Connection Request Dialog
        if (showAddNxIdDialog) {
            AddContactDialog(
                isPoWComputing = uiState.isPoWComputing,
                isSearching = uiState.isSearchingUser,
                discoveredUser = uiState.discoveredUser,
                onDismiss = {
                    showAddNxIdDialog = false
                    viewModel.clearDiscoveredUser()
                },
                onSearch = { queryNxId ->
                    viewModel.searchUserByNxId(queryNxId)
                },
                onSendRequest = { targetNxId, introMsg ->
                    viewModel.sendFriendRequest(targetNxId, introMsg) {
                        showAddNxIdDialog = false
                        viewModel.clearDiscoveredUser()
                    }
                }
            )
        }
    }
}

@Composable
private fun DeviceContactItemRow(
    contact: DeviceContact,
    onStartChat: () -> Unit,
    onInvite: () -> Unit,
    onPhotoClick: ((Any) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { if (contact.isNexaUser) onStartChat() else onInvite() })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Universal 3-Tier Priority Avatar (Photo viewer only when photo exists)
        NexaAvatar(
            nexaAvatarUrl = null,
            deviceContactPhotoUri = contact.avatarUri,
            displayName = contact.displayName,
            size = 46.dp,
            onPhotoClick = onPhotoClick
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Name and Phone Info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (contact.isNexaUser) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NexaVerifiedGreen.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "NEXA",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = NexaVerifiedGreen,
                                fontSize = 9.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = contact.phoneNumber,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Action Trigger Button
        if (contact.isNexaUser) {
            Button(
                onClick = onStartChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NexaVerifiedGreen,
                    contentColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Chat", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = onInvite,
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Invite", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PermissionDeniedCard(
    isPermanentlyDenied: Boolean = false,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = if (isPermanentlyDenied) "Contacts Permission Blocked" else "Contacts Permission Required",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )

                Text(
                    text = if (isPermanentlyDenied) {
                        "NEXA needs access to your contacts to discover which of your friends are on NEXA.\n\nContacts permission has been denied. Please enable it in system settings to see your contacts.\n\nYour address book remains 100% private and is never uploaded."
                    } else {
                        "NEXA needs access to your contacts to discover which of your friends are already using NEXA.\n\nYour address book remains 100% private and is never uploaded or saved to our servers."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isPermanentlyDenied) {
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open App Settings")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("App Settings")
                        }

                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Grant Access")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyContactsCard(
    hasSearch: Boolean,
    onAddContact: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(52.dp)
            )

            Text(
                text = if (hasSearch) "No Matching Contacts" else "No Contacts Found",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Text(
                text = if (hasSearch) "Try a different search query." else "Add a contact by phone number to start an end-to-end encrypted chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasSearch) {
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = onAddContact) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Contact")
                }
            }
        }
    }
}

@Composable
private fun MutualContactItemRow(
    contact: ContactEntity,
    onStartChat: () -> Unit,
    onEditNickname: () -> Unit,
    onViewProfile: () -> Unit = {},
    onPhotoClick: ((Any) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onStartChat)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Universal 3-Tier Priority Avatar (Photo viewer only when photo exists)
        NexaAvatar(
            nexaAvatarUrl = contact.effectiveAvatarUrl,
            deviceContactPhotoUri = contact.deviceContactPhotoUri,
            displayName = contact.effectiveDisplayName,
            size = 46.dp,
            onPhotoClick = onPhotoClick
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onViewProfile)
        ) {
            // Main Heading: Nickname if present, else device contact, else original registered name
            Text(
                text = contact.effectiveDisplayName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1
            )

            // 3-Tier Contextual Subtitle: "Device Name • ~ Original Name" or "~ Original Name"
            contact.resolvedIdentity.topBarSubtitle?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = contact.nxId,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = NexaBadgeMonospace
            )
        }

        IconButton(onClick = onEditNickname) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit Nickname",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Button(
            onClick = onStartChat,
            colors = ButtonDefaults.buttonColors(
                containerColor = NexaVerifiedGreen,
                contentColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Chat", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
