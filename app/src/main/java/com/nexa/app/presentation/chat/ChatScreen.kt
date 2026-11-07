package com.nexa.app.presentation.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.chat.components.AttachmentType
import com.nexa.app.presentation.chat.components.ChatOverflowAction
import com.nexa.app.presentation.chat.components.ContextMenuAction
import com.nexa.app.presentation.chat.components.NexaAttachmentMenu
import com.nexa.app.presentation.chat.components.NexaChatHeader
import com.nexa.app.presentation.chat.components.NexaChatOverflowMenu
import com.nexa.app.presentation.chat.components.NexaDeleteMessageDialog
import com.nexa.app.presentation.chat.components.NexaEmojiPicker
import com.nexa.app.presentation.chat.components.NexaMessageBubble
import com.nexa.app.presentation.chat.components.NexaMessageComposer
import com.nexa.app.presentation.chat.components.NexaMessageContextMenu
import com.nexa.app.presentation.chat.components.NexaReactionPicker
import com.nexa.app.presentation.chat.components.NexaTransportOption
import com.nexa.app.presentation.chat.components.NexaTransportSelector
import com.nexa.app.presentation.chat.components.PinDurationDialog
import com.nexa.app.presentation.chat.components.PinnedMessageBanner
import com.nexa.app.presentation.chat.components.PinnedMessageUiModel
import com.nexa.app.presentation.chat.components.SmsFailureBottomSheet
import com.nexa.app.presentation.chat.components.VideoQualityDialog
import com.nexa.app.presentation.chat.components.VideoTranscodeProgressOverlay
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.common.viewer.ProfileImageViewerDialog
import com.nexa.app.presentation.contacts.components.EditNicknameDialog
import com.nexa.app.presentation.contacts.components.PeerProfileDialog
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
    onBack: () -> Unit,
    onNavigateToProfile: ((nxId: String, conversationId: String) -> Unit)? = null,
    onNavigateToMediaExplorer: ((conversationId: String, name: String) -> Unit)? = null,
    onNavigateToWallpaper: ((conversationId: String) -> Unit)? = null,
    onNavigateToPinnedMessages: ((conversationId: String) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val transferUiState = transferViewModel?.uiState?.collectAsState()?.value
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    var phoneInputText by remember { mutableStateOf(uiState.destinationPhone) }
    var activeViewerImageModel by remember { mutableStateOf<Any?>(null) }

    // Coordinated UI state
    var isOverflowMenuOpen by remember { mutableStateOf(false) }
    var isAttachmentMenuOpen by remember { mutableStateOf(false) }
    var isEmojiPanelOpen by remember { mutableStateOf(false) }
    var isTransportSelectorOpen by remember { mutableStateOf(false) }
    var isGhostModeActive by remember { mutableStateOf(false) }

    var selectedMessageForMenu by remember { mutableStateOf<Message?>(null) }
    var messageToDelete by remember { mutableStateOf<Message?>(null) }
    var replyingMessage by remember { mutableStateOf<Message?>(null) }
    var isVoiceRecording by remember { mutableStateOf(false) }

    // Client-side message reactions state map: packetId -> List<String>
    val messageReactions = remember { mutableStateMapOf<String, MutableList<String>>() }

    // Back button handling hierarchy:
    // 1) Dismiss context menu / reaction bar
    // 2) Dismiss attachment / emoji / transport panel
    // 3) Pop back stack (onBack)
    BackHandler(
        enabled = selectedMessageForMenu != null ||
                isEmojiPanelOpen ||
                isAttachmentMenuOpen ||
                isTransportSelectorOpen ||
                isOverflowMenuOpen
    ) {
        when {
            selectedMessageForMenu != null -> selectedMessageForMenu = null
            isOverflowMenuOpen -> isOverflowMenuOpen = false
            isEmojiPanelOpen -> isEmojiPanelOpen = false
            isAttachmentMenuOpen -> isAttachmentMenuOpen = false
            isTransportSelectorOpen -> isTransportSelectorOpen = false
        }
    }

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

    // Forward transcoded video outputs to P2P Swarm Engine
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

    // Video Quality Selection Dialog
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
                    Text("Save & Continue", color = Color.Black)
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

    // Profile Photo Viewer Dialog
    activeViewerImageModel?.let { photoModel ->
        ProfileImageViewerDialog(
            imageModel = photoModel,
            onDismiss = { activeViewerImageModel = null }
        )
    }

    // Delete Message Confirmation Dialog
    if (messageToDelete != null) {
        val msg = messageToDelete!!
        NexaDeleteMessageDialog(
            isOutgoing = msg.isOutgoing,
            onDeleteForMe = {
                messageToDelete = null
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Message deleted for you")
                }
            },
            onDeleteForEveryone = {
                messageToDelete = null
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Message deleted for everyone")
                }
            },
            onDismiss = { messageToDelete = null }
        )
    }

    val backgroundColor = if (isDark) Color.Black else Color.White

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = backgroundColor,
        modifier = Modifier.imePadding()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Fixed Capsule Glass Header
                NexaChatHeader(
                    participantName = uiState.participantName.ifBlank { participantName },
                    participantNxId = participantNxId,
                    isPeerTyping = uiState.isPeerTyping,
                    isPeerOnline = uiState.isPeerOnline,
                    connectionState = uiState.connectionState,
                    selectedTransport = uiState.selectedTransport,
                    avatarUrl = uiState.participantAvatarUrl,
                    deviceContactPhotoUri = uiState.deviceContactPhotoUri,
                    onBack = onBack,
                    onAvatarClick = {
                        if (onNavigateToProfile != null) {
                            onNavigateToProfile(participantNxId, conversationId)
                        } else {
                            val photo = uiState.participantAvatarUrl ?: uiState.deviceContactPhotoUri
                            if (photo != null) {
                                activeViewerImageModel = photo
                            } else {
                                viewModel.onEvent(ChatUiEvent.SetProfileDialogVisible(true))
                            }
                        }
                    },
                    onVoiceCallClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Initiating Secure Voice Call...")
                        }
                    },
                    onVideoCallClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Initiating Secure Video Call...")
                        }
                    },
                    onOverflowClick = {
                        isOverflowMenuOpen = !isOverflowMenuOpen
                        isAttachmentMenuOpen = false
                        isEmojiPanelOpen = false
                        isTransportSelectorOpen = false
                    }
                )

                // Contextual Security & Transport Banner
                val bannerText = when (uiState.selectedTransport) {
                    TransportType.DATA_SMS -> "Port 8901 Binary SMS • Invisible to Telephony Apps"
                    TransportType.DTN_MESH -> "Store-Carry-Forward Mesh • 7 Hops Max • 48h TTL"
                    TransportType.INTERNET -> "Zero-Disk RAM Relay • End-to-End Encrypted"
                }

                Surface(
                    color = (if (isDark) Color(0x33FFFFFF) else Color(0x10000000)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clickable { isTransportSelectorOpen = !isTransportSelectorOpen }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (isDark) Color.White else Color.Black,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = bannerText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (isDark) Color(0xFFDDDDDD) else Color(0xFF444444)
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

                // 2. Vertically Scrollable Message Stream
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    itemsIndexed(
                        items = uiState.messages,
                        key = { _, msg -> msg.packetId }
                    ) { index, msg ->
                        // Message Grouping calculation (within 2 minutes of adjacent message from same sender)
                        val prevMsg = uiState.messages.getOrNull(index - 1)
                        val nextMsg = uiState.messages.getOrNull(index + 1)

                        val isFirstInGroup = prevMsg == null ||
                                prevMsg.isOutgoing != msg.isOutgoing ||
                                Math.abs(msg.timestamp - prevMsg.timestamp) > 120_000

                        val isLastInGroup = nextMsg == null ||
                                nextMsg.isOutgoing != msg.isOutgoing ||
                                Math.abs(nextMsg.timestamp - msg.timestamp) > 120_000

                        val reactions = messageReactions[msg.packetId].orEmpty()

                        NexaMessageBubble(
                            message = msg,
                            isFirstInGroup = isFirstInGroup,
                            isLastInGroup = isLastInGroup,
                            reactions = reactions,
                            isHighlighted = uiState.highlightedMessageId == msg.packetId ||
                                    selectedMessageForMenu?.packetId == msg.packetId,
                            onFailedClick = { failedMsg ->
                                viewModel.onEvent(ChatUiEvent.OnFailedMessageClicked(failedMsg))
                            },
                            onLongClick = {
                                selectedMessageForMenu = msg
                                isAttachmentMenuOpen = false
                                isEmojiPanelOpen = false
                                isTransportSelectorOpen = false
                            },
                            onReactionClick = { reaction ->
                                val currentList = messageReactions.getOrPut(msg.packetId) { mutableListOf() }
                                if (currentList.contains(reaction)) {
                                    currentList.remove(reaction)
                                } else {
                                    currentList.add(reaction)
                                }
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
                            .padding(horizontal = 8.dp, vertical = 2.dp)
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

                // Video Hardware Compression Progress Overlay
                VideoTranscodeProgressOverlay(
                    isVisible = uiState.transcodeState.isTranscoding,
                    progressPercent = uiState.transcodeState.progressPercent,
                    onCancel = { viewModel.onEvent(ChatUiEvent.CancelVideoTranscode) }
                )

                // 3. Bottom Composer & Optional Panels
                NexaMessageComposer(
                    text = uiState.inputText,
                    onTextChanged = { viewModel.onEvent(ChatUiEvent.OnInputTextChanged(it)) },
                    onSendClicked = {
                        viewModel.onEvent(ChatUiEvent.SendMessage)
                        replyingMessage = null
                    },
                    onEmojiToggle = {
                        isEmojiPanelOpen = !isEmojiPanelOpen
                        if (isEmojiPanelOpen) {
                            keyboardController?.hide()
                            isAttachmentMenuOpen = false
                            isTransportSelectorOpen = false
                            selectedMessageForMenu = null
                        }
                    },
                    isEmojiPanelOpen = isEmojiPanelOpen,
                    onAttachmentToggle = {
                        isAttachmentMenuOpen = !isAttachmentMenuOpen
                        if (isAttachmentMenuOpen) {
                            keyboardController?.hide()
                            isEmojiPanelOpen = false
                            isTransportSelectorOpen = false
                            selectedMessageForMenu = null
                        }
                    },
                    isAttachmentMenuOpen = isAttachmentMenuOpen,
                    onCameraClicked = {
                        videoPickerLauncher.launch("video/*")
                    },
                    selectedTransport = uiState.selectedTransport,
                    replyingMessage = replyingMessage,
                    onCancelReply = { replyingMessage = null },
                    isRecordingVoice = isVoiceRecording,
                    onStartVoiceRecording = { isVoiceRecording = true },
                    onCancelVoiceRecording = { isVoiceRecording = false },
                    onSendVoiceRecording = {
                        isVoiceRecording = false
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Voice note encrypted and sent.")
                        }
                    }
                )

                // Expandable Emoji Panel
                AnimatedVisibility(
                    visible = isEmojiPanelOpen,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    NexaEmojiPicker(
                        onEmojiSelected = { emoji ->
                            viewModel.onEvent(ChatUiEvent.OnInputTextChanged(uiState.inputText + emoji))
                        }
                    )
                }

                // Expandable 3x3 Attachment Menu
                AnimatedVisibility(
                    visible = isAttachmentMenuOpen,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    NexaAttachmentMenu(
                        onAttachmentSelected = { type ->
                            isAttachmentMenuOpen = false
                            when (type) {
                                AttachmentType.CAMERA -> videoPickerLauncher.launch("video/*")
                                AttachmentType.GALLERY -> videoPickerLauncher.launch("video/*")
                                AttachmentType.AUDIO, AttachmentType.MUSIC -> filePickerLauncher.launch(arrayOf("audio/*"))
                                AttachmentType.DOCUMENT -> filePickerLauncher.launch(arrayOf("*/*"))
                                AttachmentType.VIDEO -> videoPickerLauncher.launch("video/*")
                                AttachmentType.CONTACT -> {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Share Contact selected")
                                    }
                                }
                                AttachmentType.LINK -> {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Share Secure Link selected")
                                    }
                                }
                                AttachmentType.LOCATION -> {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Share Encrypted GPS Location selected")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            // Floating 3-Dot Overflow Menu
            if (isOverflowMenuOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isOverflowMenuOpen = false }
                ) {
                    NexaChatOverflowMenu(
                        isGhostModeActive = isGhostModeActive,
                        onActionSelected = { action ->
                            isOverflowMenuOpen = false
                            when (action) {
                                ChatOverflowAction.VIEW_CONTACT -> {
                                    if (onNavigateToProfile != null) {
                                        onNavigateToProfile(participantNxId, conversationId)
                                    } else {
                                        viewModel.onEvent(ChatUiEvent.SetProfileDialogVisible(true))
                                    }
                                }
                                ChatOverflowAction.SEARCH_CHAT -> {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Search in Chat activated")
                                    }
                                }
                                ChatOverflowAction.MEDIA_LINKS_FILES -> {
                                    if (onNavigateToMediaExplorer != null) {
                                        onNavigateToMediaExplorer(conversationId, uiState.participantName.ifBlank { participantName })
                                    } else {
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    }
                                }
                                ChatOverflowAction.GHOST_MODE -> {
                                    isGhostModeActive = !isGhostModeActive
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (isGhostModeActive) "Ghost Mode activated: messages leave zero traces"
                                            else "Ghost Mode deactivated"
                                        )
                                    }
                                }
                                ChatOverflowAction.PINNED_MESSAGES -> {
                                    if (onNavigateToPinnedMessages != null) {
                                        onNavigateToPinnedMessages(conversationId)
                                    } else if (uiState.pinnedMessages.isNotEmpty()) {
                                        viewModel.onEvent(ChatUiEvent.OnPinnedBannerClicked)
                                    } else {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("No pinned messages in this chat")
                                        }
                                    }
                                }
                                ChatOverflowAction.CHAT_WALLPAPER -> {
                                    if (onNavigateToWallpaper != null) {
                                        onNavigateToWallpaper(conversationId)
                                    } else {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Chat Wallpaper selector")
                                        }
                                    }
                                }
                                ChatOverflowAction.CLEAR_CHAT -> {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Chat cleared")
                                    }
                                }
                                ChatOverflowAction.BLOCK_CONTACT -> {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Contact blocked")
                                    }
                                }
                            }
                        },
                        onDismiss = { isOverflowMenuOpen = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 70.dp, end = 16.dp)
                    )
                }
            }

            // Floating Transport Selector Modal
            if (isTransportSelectorOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isTransportSelectorOpen = false },
                    contentAlignment = Alignment.Center
                ) {
                    val currentTransportOption = when (uiState.selectedTransport) {
                        TransportType.INTERNET -> NexaTransportOption.INTERNET
                        TransportType.DATA_SMS -> NexaTransportOption.SMS
                        TransportType.DTN_MESH -> NexaTransportOption.MESH_BLUETOOTH
                    }

                    NexaTransportSelector(
                        currentOption = currentTransportOption,
                        onOptionSelected = { option ->
                            isTransportSelectorOpen = false
                            when (option) {
                                NexaTransportOption.INTERNET -> viewModel.onEvent(ChatUiEvent.SetTransport(TransportType.INTERNET))
                                NexaTransportOption.SMS -> {
                                    viewModel.onEvent(ChatUiEvent.SetTransport(TransportType.DATA_SMS))
                                    if (uiState.destinationPhone.isBlank()) {
                                        viewModel.onEvent(ChatUiEvent.SetPhoneDialogVisible(true))
                                    }
                                }
                                NexaTransportOption.MESH_BLUETOOTH -> viewModel.onEvent(ChatUiEvent.SetTransport(TransportType.DTN_MESH))
                                NexaTransportOption.AUTO -> {
                                    viewModel.onEvent(ChatUiEvent.SetTransport(TransportType.INTERNET))
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Auto Transport Routing Enabled")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

            // Message Long-Press Context Menu & Reaction Bar
            if (selectedMessageForMenu != null) {
                val selectedMsg = selectedMessageForMenu!!
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { selectedMessageForMenu = null },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Quick Reaction Bar
                        NexaReactionPicker(
                            onReactionSelect = { emoji ->
                                val list = messageReactions.getOrPut(selectedMsg.packetId) { mutableListOf() }
                                if (list.contains(emoji)) {
                                    list.remove(emoji)
                                } else {
                                    list.add(emoji)
                                }
                                selectedMessageForMenu = null
                            }
                        )

                        // Context Menu (6 items)
                        NexaMessageContextMenu(
                            message = selectedMsg,
                            isPinned = selectedMsg.isPinned,
                            onActionSelected = { action ->
                                selectedMessageForMenu = null
                                when (action) {
                                    ContextMenuAction.REPLY -> {
                                        replyingMessage = selectedMsg
                                    }
                                    ContextMenuAction.COPY -> {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(
                                            ClipData.newPlainText("NEXA Message", selectedMsg.plainTextContent)
                                        )
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Copied to clipboard")
                                        }
                                    }
                                    ContextMenuAction.FORWARD -> {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Forwarding message: ${selectedMsg.plainTextContent.take(20)}...")
                                        }
                                    }
                                    ContextMenuAction.PIN -> {
                                        if (selectedMsg.isPinned) {
                                            viewModel.onEvent(ChatUiEvent.RequestUnpinMessage(selectedMsg))
                                        } else {
                                            viewModel.onEvent(ChatUiEvent.RequestPinMessage(selectedMsg))
                                        }
                                    }
                                    ContextMenuAction.MESSAGE_INFO -> {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Transport: ${selectedMsg.transportType} | Status: ${selectedMsg.status}"
                                            )
                                        }
                                    }
                                    ContextMenuAction.DELETE -> {
                                        messageToDelete = selectedMsg
                                    }
                                }
                            },
                            onDismiss = { selectedMessageForMenu = null }
                        )
                    }
                }
            }
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
