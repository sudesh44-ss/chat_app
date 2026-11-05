package com.nexa.app.presentation.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.chat.components.ChatInputField
import com.nexa.app.presentation.chat.components.MessageBubble
import com.nexa.app.presentation.chat.components.NexaChatTopBar
import com.nexa.app.presentation.chat.components.PinDurationDialog
import com.nexa.app.presentation.chat.components.PinnedMessageBanner
import com.nexa.app.presentation.chat.components.PinnedMessageUiModel
import com.nexa.app.presentation.chat.components.SmsFailureBottomSheet
import com.nexa.app.presentation.chat.components.TransportToggleBar
import com.nexa.app.presentation.common.viewer.ProfileImageViewerDialog
import com.nexa.app.presentation.contacts.components.EditNicknameDialog
import com.nexa.app.presentation.contacts.components.PeerProfileDialog
import com.nexa.app.presentation.chat.components.VideoQualityDialog
import com.nexa.app.presentation.chat.components.VideoTranscodeProgressOverlay
import com.nexa.app.presentation.transfer.TransferUiEvent
import com.nexa.app.presentation.transfer.TransferViewModel
import com.nexa.app.presentation.transfer.components.IncomingTransferDialog
import com.nexa.app.presentation.transfer.components.TransferProgressCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    transferViewModel: TransferViewModel? = null,
    conversationId: String,
    participantNxId: String,
    participantName: String,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val transferUiState = transferViewModel?.uiState?.collectAsState()?.value
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var phoneInputText by remember { mutableStateOf(uiState.destinationPhone) }
    var activeViewerImageModel by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(conversationId) {
        viewModel.onEvent(
            ChatUiEvent.Init(
                conversationId = conversationId,
                participantNxId = participantNxId,
                participantName = participantName
            )
        )
        transferViewModel?.bindConversation(conversationId)
    }

    // Hardware-Accelerated Video Picker
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onEvent(ChatUiEvent.OnVideoSelected(uri))
        }
    }

    // Direct P2P File Picker via Storage Access Framework (SAF)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            if (isVideoUri(context, uri)) {
                viewModel.onEvent(ChatUiEvent.OnVideoSelected(uri))
            } else if (transferViewModel != null) {
                val (name, size) = queryFileInfo(context, uri)
                transferViewModel.onEvent(
                    TransferUiEvent.InitiateTransfer(
                        conversationId = conversationId,
                        recipientNxId = participantNxId,
                        uri = uri,
                        fileName = name,
                        fileSizeBytes = size
                    )
                )
            }
        }
    }

    // Automatically forward transcoded video outputs to the P2P Swarm Engine
    LaunchedEffect(Unit) {
        viewModel.videoReadyFlow.collect { ready ->
            if (transferViewModel != null) {
                transferViewModel.onEvent(
                    TransferUiEvent.InitiateTransfer(
                        conversationId = ready.conversationId,
                        recipientNxId = ready.recipientNxId,
                        uri = Uri.fromFile(ready.file),
                        fileName = ready.fileName,
                        fileSizeBytes = ready.fileSizeBytes
                    )
                )
            }
        }
    }

    // Auto-scroll to bottom on new message
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.onEvent(ChatUiEvent.ClearError)
        }
    }

    LaunchedEffect(transferUiState?.errorMessage) {
        transferUiState?.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            transferViewModel?.onEvent(TransferUiEvent.ClearError)
        }
    }

    LaunchedEffect(uiState.pinLimitReachedSnackbar) {
        if (uiState.pinLimitReachedSnackbar) {
            snackbarHostState.showSnackbar(
                message = "Pin limit reached: You can pin up to 4 messages in this chat. Unpin an existing message to pin another one."
            )
            viewModel.onEvent(ChatUiEvent.DismissPinLimitSnackbar)
        }
    }

    // Incoming P2P Transfer Prompt Dialog
    transferUiState?.incomingOffer?.let { offer ->
        IncomingTransferDialog(
            offer = offer,
            onAccept = { transferViewModel?.onEvent(TransferUiEvent.AcceptOffer(it)) },
            onDecline = { transferViewModel?.onEvent(TransferUiEvent.DeclineOffer(it)) }
        )
    }

    // Video Quality Selection Dialog (Modal BottomSheet)
    if (uiState.showVideoQualityDialog && uiState.pendingVideoCandidate != null) {
        val candidate = uiState.pendingVideoCandidate!!
        VideoQualityDialog(
            originalSizeBytes = candidate.metadata.sizeBytes,
            durationFormatted = candidate.metadata.durationFormatted,
            resolution = "${candidate.metadata.displayWidth}x${candidate.metadata.displayHeight}",
            estimatedCompressedBytes = candidate.estimatedCompressedBytes,
            onSelectMode = { mode, rememberChoice ->
                viewModel.onEvent(ChatUiEvent.OnSelectVideoQuality(mode, rememberChoice))
            },
            onDismiss = {
                viewModel.onEvent(ChatUiEvent.DismissVideoQualityDialog)
            }
        )
    }

    // Recipient Phone Input Dialog for Port 8901 Data SMS
    if (uiState.showPhoneDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(ChatUiEvent.SetPhoneDialogVisible(false)) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = NexaWarningOrange,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recipient Phone Number",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Data SMS routes binary PDUs over Port 8901 directly to this phone number, completely hidden from Google Messages & standard inboxes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = phoneInputText,
                        onValueChange = { phoneInputText = it },
                        label = { Text("E.164 Phone (e.g. +919876543210)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.onEvent(ChatUiEvent.SetDestinationPhone(phoneInputText.trim()))
                        viewModel.onEvent(ChatUiEvent.SetPhoneDialogVisible(false))
                        if (uiState.inputText.isNotBlank()) {
                            viewModel.onEvent(ChatUiEvent.SendMessage)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NexaWarningOrange)
                ) {
                    Text("Save & Continue", color = androidx.compose.ui.graphics.Color.Black)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onEvent(ChatUiEvent.SetPhoneDialogVisible(false)) }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Carrier SMS & Multi-Transport Failure Remediation Sheet
    if (uiState.showSmsFailureSheet && uiState.failedMessageCandidate != null) {
        SmsFailureBottomSheet(
            failedMessage = uiState.failedMessageCandidate!!,
            onRetryTransport = { transport ->
                viewModel.onEvent(ChatUiEvent.RetryFailedMessage(transport))
            },
            onDismiss = {
                viewModel.onEvent(ChatUiEvent.DismissSmsFailureSheet)
            }
        )
    }

    // 3-Tier Identity Profile Dialog
    if (uiState.showProfileDialog) {
        PeerProfileDialog(
            nxId = participantNxId,
            originalRegisteredName = uiState.originalProfileName.ifBlank { participantName },
            customNickname = uiState.customNickname,
            deviceContactName = uiState.deviceContactName,
            avatarUrl = uiState.participantAvatarUrl,
            deviceContactPhotoUri = uiState.deviceContactPhotoUri,
            onEditNickname = {
                viewModel.onEvent(ChatUiEvent.SetProfileDialogVisible(false))
                viewModel.onEvent(ChatUiEvent.SetNicknameDialogVisible(true))
            },
            onDismiss = {
                viewModel.onEvent(ChatUiEvent.SetProfileDialogVisible(false))
            }
        )
    }

    // Client-Side Contact Nickname Dialog
    if (uiState.showNicknameDialog) {
        EditNicknameDialog(
            contactNxId = participantNxId,
            originalProfileName = uiState.originalProfileName.ifBlank { participantName },
            currentNickname = uiState.customNickname,
            onSaveNickname = { newNick ->
                viewModel.onEvent(ChatUiEvent.SaveNickname(newNick))
            },
            onDismiss = {
                viewModel.onEvent(ChatUiEvent.SetNicknameDialogVisible(false))
            }
        )
    }

    // Pin Duration Selection Dialog
    if (uiState.showPinDurationDialog && uiState.candidateMessageToPin != null) {
        PinDurationDialog(
            onDismiss = {
                viewModel.onEvent(ChatUiEvent.DismissPinDurationDialog)
            },
            onConfirmPin = { duration ->
                viewModel.onEvent(ChatUiEvent.ConfirmPinMessage(duration))
            }
        )
    }

    // Full-Screen Profile Photo Viewer Dialog (Opens on photo tap, never on initial avatar)
    activeViewerImageModel?.let { photoModel ->
        ProfileImageViewerDialog(
            imageModel = photoModel,
            onDismiss = { activeViewerImageModel = null }
        )
    }

    // Contextual Message Action Dialog (Pin / Unpin / Copy)
    if (uiState.selectedMessageForAction != null) {
        val selectedMsg = uiState.selectedMessageForAction!!
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(ChatUiEvent.SetSelectedMessageForAction(null)) },
            title = {
                Text(
                    text = "Message Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = selectedMsg.plainTextContent.take(120),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Pin / Unpin Action
                    Surface(
                        onClick = {
                            if (selectedMsg.isPinned) {
                                viewModel.onEvent(ChatUiEvent.RequestUnpinMessage(selectedMsg))
                            } else {
                                viewModel.onEvent(ChatUiEvent.RequestPinMessage(selectedMsg))
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                imageVector = if (selectedMsg.isPinned) Icons.Default.RemoveCircleOutline else Icons.Default.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (selectedMsg.isPinned) "Unpin Message" else "Pin Message",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Copy Text Action
                    Surface(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("NEXA Message", selectedMsg.plainTextContent)
                            )
                            viewModel.onEvent(ChatUiEvent.SetSelectedMessageForAction(null))
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Copied to clipboard")
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Copy Text",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onEvent(ChatUiEvent.SetSelectedMessageForAction(null)) }
                ) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            NexaChatTopBar(
                participantName = uiState.participantName.ifBlank { participantName },
                participantNxId = participantNxId,
                isPeerTyping = uiState.isPeerTyping,
                isPeerOnline = uiState.isPeerOnline,
                connectionState = uiState.connectionState,
                selectedRouteMode = uiState.selectedRouteMode,
                selectedTransport = uiState.selectedTransport,
                showTransportBadges = uiState.showTransportBadges,
                originalProfileName = uiState.originalProfileName.ifBlank { participantName },
                deviceContactName = uiState.deviceContactName,
                avatarUrl = uiState.participantAvatarUrl,
                deviceContactPhotoUri = uiState.deviceContactPhotoUri,
                onSelectRouteMode = { mode -> viewModel.onEvent(ChatUiEvent.SetRouteMode(mode)) },
                onToggleTransportBadges = { show -> viewModel.onEvent(ChatUiEvent.SetShowTransportBadges(show)) },
                onEditNickname = { viewModel.onEvent(ChatUiEvent.SetNicknameDialogVisible(true)) },
                onViewProfile = { viewModel.onEvent(ChatUiEvent.SetProfileDialogVisible(true)) },
                onAvatarPhotoClick = { photoModel -> activeViewerImageModel = photoModel },
                onBack = onBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.imePadding()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Contextual Banner
            val bannerText = when (uiState.selectedTransport) {
                TransportType.DATA_SMS -> "Port 8901 Binary SMS • Invisible to Telephony Apps"
                TransportType.DTN_MESH -> "Store-Carry-Forward Mesh • 7 Hops Max • 48h TTL"
                TransportType.INTERNET -> "Zero-Disk RAM Relay • End-to-End Encrypted"
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp, bottom = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Pinned Messages Banner (Multi-pin cycle carousel)
            if (uiState.pinnedMessages.isNotEmpty()) {
                val pinnedUiModels = remember(uiState.pinnedMessages, uiState.messages) {
                    uiState.pinnedMessages.map { pin ->
                        val origIdx = uiState.messages.indexOfFirst { it.packetId == pin.packetId }.coerceAtLeast(0)
                        PinnedMessageUiModel(
                            messageId = pin.packetId,
                            previewText = pin.plainTextContent.take(60),
                            originalListIndex = origIdx
                        )
                    }
                }
                PinnedMessageBanner(
                    pinnedMessages = pinnedUiModels,
                    currentIndex = uiState.currentPinnedIndex,
                    onBannerClick = {
                        val curr = uiState.currentPinnedIndex.coerceIn(0, pinnedUiModels.lastIndex)
                        val target = pinnedUiModels[curr]
                        coroutineScope.launch {
                            listState.animateScrollToItem(target.originalListIndex)
                        }
                        viewModel.onEvent(ChatUiEvent.OnPinnedBannerClicked)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            // Message Stream List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.packetId }
                ) { msg ->
                    MessageBubble(
                        message = msg,
                        showTransportBadge = uiState.showTransportBadges,
                        isHighlighted = uiState.highlightedMessageId == msg.packetId,
                        onFailedClick = { failedMsg ->
                            viewModel.onEvent(ChatUiEvent.OnFailedMessageClicked(failedMsg))
                        },
                        onLongClick = {
                            viewModel.onEvent(ChatUiEvent.SetSelectedMessageForAction(msg))
                        }
                    )
                }
            }

            // Direct P2P File Transfer Cards
            val transfers = transferUiState?.conversationTransfers.orEmpty()
            if (transfers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    transfers.forEach { transfer ->
                        TransferProgressCard(
                            transfer = transfer,
                            speedMBps = transferUiState?.transferSpeeds?.get(transfer.transferId),
                            etaSeconds = transferUiState?.transferEtas?.get(transfer.transferId),
                            onPause = { transferViewModel?.onEvent(TransferUiEvent.PauseTransfer(it)) },
                            onResume = { transferViewModel?.onEvent(TransferUiEvent.ResumeTransfer(it)) },
                            onCancel = { transferViewModel?.onEvent(TransferUiEvent.CancelTransfer(it)) }
                        )
                    }
                }
            }

            // Video Hardware Compression Progress Overlay (Non-blocking)
            VideoTranscodeProgressOverlay(
                isVisible = uiState.transcodeState.isTranscoding,
                progressPercent = uiState.transcodeState.progressPercent,
                onCancel = { viewModel.onEvent(ChatUiEvent.CancelVideoTranscode) }
            )

            // Interactive Multi-Transport Toggle Bar
            TransportToggleBar(
                selectedTransport = uiState.selectedTransport,
                destinationPhone = uiState.destinationPhone,
                onTransportSelected = { viewModel.onEvent(ChatUiEvent.SetTransport(it)) },
                onPhoneEditClicked = {
                    phoneInputText = uiState.destinationPhone
                    viewModel.onEvent(ChatUiEvent.SetPhoneDialogVisible(true))
                }
            )

            // Bottom Input Field with dynamic transport accent & icon button
            ChatInputField(
                text = uiState.inputText,
                selectedTransport = uiState.selectedTransport,
                onTextChanged = { viewModel.onEvent(ChatUiEvent.OnInputTextChanged(it)) },
                onSendClicked = { viewModel.onEvent(ChatUiEvent.SendMessage) },
                onToggleTransport = { viewModel.onEvent(ChatUiEvent.ToggleTransport) },
                onAttachFileClicked = { filePickerLauncher.launch(arrayOf("*/*")) },
                onAttachVideoClicked = { videoPickerLauncher.launch("video/*") }
            )
        }
    }
}

private fun queryFileInfo(context: Context, uri: Uri): Pair<String, Long> {
    var name = "file_${System.currentTimeMillis()}"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (e: Exception) {
        // Fallback
    }
    return Pair(name, size)
}

private fun isVideoUri(context: Context, uri: Uri): Boolean {
    val mimeType = context.contentResolver.getType(uri)
    if (mimeType != null && mimeType.startsWith("video/")) return true
    val (name, _) = queryFileInfo(context, uri)
    val lower = name.lowercase()
    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") ||
            lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".ts")
}

@Composable
private fun ConnectionStatusIndicator(
    connectionState: ConnectionState,
    selectedTransport: TransportType
) {
    val (color, text) = when (selectedTransport) {
        TransportType.DATA_SMS -> Pair(NexaWarningOrange, "SMS Airwaves")
        TransportType.DTN_MESH -> Pair(NexaBadgeMonospace, "Nearby Mesh")
        TransportType.INTERNET -> when (connectionState) {
            is ConnectionState.Connected -> Pair(NexaVerifiedGreen, "Online")
            is ConnectionState.Connecting -> Pair(NexaWarningOrange, "Connecting...")
            else -> Pair(MaterialTheme.colorScheme.outline, "Offline")
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color
        )
    }
}
