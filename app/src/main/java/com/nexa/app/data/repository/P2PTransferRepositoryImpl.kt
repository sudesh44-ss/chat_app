package com.nexa.app.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nexa.app.core.dtn.BlindTokenGenerator
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.webrtc.FileChunk
import com.nexa.app.core.webrtc.FileDataChannelBridge
import com.nexa.app.core.webrtc.PeerLifecycleState
import com.nexa.app.core.webrtc.WebRtcClient
import com.nexa.app.core.webrtc.WebRtcSession
import com.nexa.app.data.local.dao.FileTransferDao
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.data.remote.service.P2PTransferService
import com.nexa.app.data.remote.signaling.SignalingClient
import com.nexa.app.data.remote.signaling.SignalingMessage
import com.nexa.app.domain.repository.P2PTransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import com.nexa.app.core.network.NetworkChangeEvent
import com.nexa.app.core.network.NetworkMonitor
import com.nexa.app.core.network.NetworkTypeState
import com.nexa.app.core.security.ChunkIntegrityVerifier
import com.nexa.app.core.security.ChunkVerificationResult
import com.nexa.app.core.security.FileClassification
import com.nexa.app.core.security.FileReleasePolicy
import com.nexa.app.core.security.FileReleaseClassifier
import com.nexa.app.core.security.FileSecurityManager
import com.nexa.app.core.security.ReleaseAuthorization
import com.nexa.app.core.security.SecurityScanResult
import com.nexa.app.core.security.ThreatType
import com.nexa.app.core.security.TransferCompletionResult
import com.nexa.app.core.util.FileShredder
import com.nexa.app.data.remote.media.R2MediaFallbackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class P2PTransferRepositoryImpl(
    private val context: Context,
    private val transferDao: FileTransferDao,
    private val signalingClient: SignalingClient,
    private val webRtcClient: WebRtcClient,
    private val secureStorage: SecureStorage,
    private val networkMonitor: NetworkMonitor? = null,
    private val r2FallbackService: R2MediaFallbackService? = null,
    private val fileSecurityManager: FileSecurityManager = FileSecurityManager(context),
    private val chunkIntegrityVerifier: ChunkIntegrityVerifier = ChunkIntegrityVerifier()
) : P2PTransferRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkDebounceJob: Job? = null

    private val _incomingOffers = MutableSharedFlow<SignalingMessage.OfferTransfer>(extraBufferCapacity = 16)

    // Active WebRTC PeerConnections, DataChannels, Sessions and file handles indexed by transferId
    private val activePeerConnections = ConcurrentHashMap<Long, PeerConnection>()
    private val activeDataChannels = ConcurrentHashMap<Long, DataChannel>()
    private val activeSessions = ConcurrentHashMap<Long, WebRtcSession>()
    private val activeFileDescriptors = ConcurrentHashMap<Long, ParcelFileDescriptor>()
    private val activeRandomAccessFiles = ConcurrentHashMap<Long, RandomAccessFile>()

    init {
        observeSignalingMessages()
        observeNetworkChanges()
        recoverInterruptedQuarantineScans()
    }

    override fun observeTransfer(transferId: Long): Flow<TransferEntity?> {
        return transferDao.observeTransfer(transferId)
    }

    override fun observeTransfersForConversation(conversationId: String): Flow<List<TransferEntity>> {
        return transferDao.observeTransfersForConversation(conversationId)
    }

    override fun observeIncomingOffers(): Flow<SignalingMessage.OfferTransfer> {
        return _incomingOffers.asSharedFlow()
    }

    override suspend fun initiateTransfer(
        conversationId: String,
        recipientNxId: String,
        fileUri: Uri,
        fileName: String,
        fileSizeBytes: Long
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val myNxId = getMyNxId()
            val transferId = System.currentTimeMillis()
            val totalChunks = ((fileSizeBytes + FileChunk.DEFAULT_CHUNK_SIZE - 1) / FileChunk.DEFAULT_CHUNK_SIZE).toInt()

            // 1. GATE 1: SENDER PRE-DISPATCH LOCAL SECURITY SCAN
            val scanVerdict = fileSecurityManager.scanOutbound(
                uri = fileUri,
                declaredName = fileName,
                fileSizeBytes = fileSizeBytes
            )

            val fileHash = when (scanVerdict) {
                is SecurityScanResult.Safe -> scanVerdict.hash
                is SecurityScanResult.Rejected -> {
                    Log.w(TAG, "Gate 1 rejected file '$fileName': ${scanVerdict.reason} (threat=${scanVerdict.threatType})")
                    val entity = TransferEntity(
                        transferId = transferId,
                        conversationId = conversationId,
                        fileName = fileName,
                        fileSizeBytes = fileSizeBytes,
                        localUri = fileUri.toString(),
                        fileHash = "BLOCKED_${scanVerdict.threatType}",
                        chunksCompleted = 0,
                        totalChunks = 0,
                        status = "REJECTED_SENDER",
                        isIncoming = false
                    )
                    transferDao.insertTransfer(entity)
                    return@withContext Result.failure(
                        SecurityException("File transfer blocked by security policy: ${scanVerdict.reason}")
                    )
                }
                is SecurityScanResult.Corrupted -> {
                    Log.w(TAG, "Gate 1 corrupted source file '$fileName': ${scanVerdict.message}")
                    return@withContext Result.failure(
                        IllegalArgumentException("Cannot send corrupted file: ${scanVerdict.message}")
                    )
                }
                is SecurityScanResult.Inconclusive -> {
                    return@withContext Result.failure(
                        IllegalStateException("Pre-dispatch scan inconclusive: ${scanVerdict.reason}")
                    )
                }
            }

            // 2. Persist in local SQLCipher DB
            val entity = TransferEntity(
                transferId = transferId,
                conversationId = conversationId,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                localUri = fileUri.toString(),
                fileHash = fileHash,
                chunksCompleted = 0,
                totalChunks = totalChunks,
                status = "PENDING",
                isIncoming = false,
                securityState = "WAITING_FOR_RECEIVER",
                scanResult = "SAFE",
                senderScanReported = "SAFE",
                isExplicitlyAccepted = true
            )
            transferDao.insertTransfer(entity)

            // 3. Keep PFD open for subsequent streaming
            val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
            if (pfd != null) {
                activeFileDescriptors[transferId] = pfd
            }

            // 4. Send Offer envelope over Signaling WebSocket
            val offer = SignalingMessage.OfferTransfer(
                transferId = transferId,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                fileHash = fileHash,
                senderNxId = myNxId,
                recipientNxId = recipientNxId,
                senderScanStatus = "PASSED"
            )
            signalingClient.sendMessage(offer)

            // 5. Start Foreground Service with WakeLocks
            P2PTransferService.start(context, transferId, fileName)

            Log.i(TAG, "Initiated P2P file transfer #$transferId for $fileName ($fileSizeBytes bytes)")
            Result.success(transferId)
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating P2P transfer", e)
            Result.failure(e)
        }
    }

    override suspend fun acceptTransfer(transferId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val transfer = transferDao.getTransferById(transferId)
            ?: return@withContext Result.failure(IllegalArgumentException("Transfer not found"))

        val myNxId = getMyNxId()
        chunkIntegrityVerifier.registerSession(
            transferId = transferId,
            senderNxId = transfer.conversationId,
            recipientNxId = myNxId,
            expectedTotalChunks = transfer.totalChunks,
            expectedTotalBytes = transfer.fileSizeBytes,
            expectedFileHash = transfer.fileHash
        )

        transferDao.updateSecurityState(transferId, "TRANSFERRING")
        transferDao.updateStatus(transferId, "CONNECTING")

        // 1. Initialize WebRTC PeerConnection for Receiver
        val pc = setupReceiverPeerConnection(transferId, transfer)
        if (pc == null) {
            transferDao.updateSecurityState(transferId, "FAILED")
            transferDao.updateStatus(transferId, "FAILED")
            return@withContext Result.failure(IllegalStateException("Failed to create PeerConnection"))
        }
        activePeerConnections[transferId] = pc

        // 2. Send Accept message over Signaling
        val acceptMsg = SignalingMessage.AcceptTransfer(
            transferId = transferId,
            senderNxId = transfer.conversationId, // Participant ID
            recipientNxId = myNxId
        )
        signalingClient.sendMessage(acceptMsg)

        // 3. Start Foreground Service
        P2PTransferService.start(context, transferId, transfer.fileName)
        Log.i(TAG, "Accepted incoming transfer #$transferId for ${transfer.fileName}")
        Result.success(Unit)
    }

    override suspend fun declineTransfer(transferId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val transfer = transferDao.getTransferById(transferId) ?: return@withContext Result.success(Unit)
        val myNxId = getMyNxId()
        transferDao.updateSecurityState(transferId, "DECLINED")
        transferDao.updateStatus(transferId, "DECLINED")

        val declineMsg = SignalingMessage.DeclineTransfer(
            transferId = transferId,
            senderNxId = transfer.conversationId,
            recipientNxId = myNxId
        )
        signalingClient.sendMessage(declineMsg)
        cleanupTransfer(transferId)
        shredTransferQuarantine(transferId)
        Result.success(Unit)
    }

    override suspend fun pauseTransfer(transferId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        transferDao.updateStatus(transferId, "PAUSED")
        cleanupTransfer(transferId)
        Result.success(Unit)
    }

    override suspend fun resumeTransfer(transferId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val transfer = transferDao.getTransferById(transferId)
            ?: return@withContext Result.failure(IllegalArgumentException("Transfer not found"))

        val myNxId = getMyNxId()
        val resumeMsg = SignalingMessage.ResumeFromChunk(
            transferId = transferId,
            startChunkIndex = transfer.chunksCompleted,
            senderNxId = transfer.conversationId,
            recipientNxId = myNxId
        )
        signalingClient.sendMessage(resumeMsg)
        Result.success(Unit)
    }

    override suspend fun cancelTransfer(transferId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val transfer = transferDao.getTransferById(transferId)
        transferDao.updateStatus(transferId, "CANCELLED")
        cleanupTransfer(transferId)
        if (transfer?.isIncoming == true) {
            val quarantineFile = File(incomingQuarantineDirectory(), "quarantine_${transferId}.tmp")
            if (quarantineFile.exists() && !FileShredder.shred(quarantineFile)) {
                return@withContext Result.failure(IllegalStateException("Unable to purge cancelled transfer quarantine"))
            }
            transferDao.updateQuarantinePath(transferId, null)
            transferDao.updateScanResult(transferId, "CANCELLED", "CANCELLED", "Receiver cancelled the transfer")
        }
        Result.success(Unit)
    }

    private fun observeSignalingMessages() {
        scope.launch {
            signalingClient.incomingMessages.collect { msg ->
                try {
                    when (msg) {
                        is SignalingMessage.OfferTransfer -> handleIncomingOffer(msg)
                        is SignalingMessage.AcceptTransfer -> handleSenderAccepted(msg)
                        is SignalingMessage.DeclineTransfer -> handleSenderDeclined(msg)
                        is SignalingMessage.SignalingSdp -> handleIncomingSdp(msg)
                        is SignalingMessage.SignalingIce -> handleIncomingIce(msg)
                        is SignalingMessage.ResumeFromChunk -> handleResumeRequest(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling signaling message: ${msg.action}", e)
                }
            }
        }
    }

    private suspend fun handleIncomingOffer(offer: SignalingMessage.OfferTransfer) {
        if (transferDao.getTransferById(offer.transferId) != null) {
            Log.w(TAG, "Rejected duplicate incoming transfer id ${offer.transferId}")
            return
        }
        if (offer.transferId <= 0L ||
            offer.fileSizeBytes !in 1..FileSecurityManager.MAX_FILE_SIZE_BYTES ||
            !offer.fileHash.matches(Regex("(?i)[0-9a-f]{64}"))
        ) {
            Log.w(TAG, "Rejected incoming transfer offer with invalid metadata")
            return
        }
        val totalChunks = ((offer.fileSizeBytes + FileChunk.DEFAULT_CHUNK_SIZE - 1) / FileChunk.DEFAULT_CHUNK_SIZE).toInt()
        val entity = TransferEntity(
            transferId = offer.transferId,
            conversationId = offer.senderNxId,
            fileName = offer.fileName,
            fileSizeBytes = offer.fileSizeBytes,
            localUri = "",
            fileHash = offer.fileHash,
            chunksCompleted = 0,
            totalChunks = totalChunks,
            status = "OFFERED",
            isIncoming = true,
            securityState = "OFFERED",
            scanResult = "SCANNING",
            senderScanReported = offer.senderScanStatus
                ?.uppercase()
                ?.takeIf { it in setOf("PASSED", "FAILED", "UNKNOWN") }
                ?: "UNKNOWN",
            isExplicitlyAccepted = false
        )
        transferDao.insertTransfer(entity)
        _incomingOffers.emit(offer)
    }

    private suspend fun handleSenderAccepted(msg: SignalingMessage.AcceptTransfer) {
        val transferId = msg.transferId
        val transfer = transferDao.getTransferById(transferId) ?: return

        transferDao.updateStatus(transferId, "CONNECTING")

        // 1. Build Sender PeerConnection
        val pc = setupSenderPeerConnection(transferId, transfer) ?: return
        activePeerConnections[transferId] = pc

        // 2. Create SCTP DataChannel: "file_stream"
        val dataChannel = webRtcClient.createDataChannel(pc, WebRtcClient.DATA_CHANNEL_LABEL)
        if (dataChannel != null) {
            activeDataChannels[transferId] = dataChannel
            activeSessions[transferId]?.dataChannel = dataChannel
            dataChannel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    if (dataChannel.state() == DataChannel.State.OPEN) {
                        Log.i(TAG, "DataChannel is OPEN on Sender! Commencing 64 KB slice stream...")
                        startSenderStreaming(transferId, transfer, dataChannel)
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {}
            })
        }

        // 3. Generate SDP Offer
        val offerResult = webRtcClient.createOffer(pc)
        offerResult.onSuccess { sdpOffer ->
            webRtcClient.setLocalDescription(pc, sdpOffer)
            val sdpMsg = SignalingMessage.SignalingSdp(
                transferId = transferId,
                sdpType = "offer",
                sdpDescription = sdpOffer.description,
                senderNxId = getMyNxId(),
                recipientNxId = msg.recipientNxId
            )
            signalingClient.sendMessage(sdpMsg)
            Log.d(TAG, "Dispatched SDP Offer to ${msg.recipientNxId}")
        }
    }

    private suspend fun handleSenderDeclined(msg: SignalingMessage.DeclineTransfer) {
        transferDao.updateStatus(msg.transferId, "DECLINED")
        cleanupTransfer(msg.transferId)
    }

    private suspend fun handleIncomingSdp(msg: SignalingMessage.SignalingSdp) {
        val transferId = msg.transferId
        val pc = activePeerConnections[transferId] ?: return

        val sdpType = if (msg.sdpType.equals("offer", ignoreCase = true)) {
            SessionDescription.Type.OFFER
        } else {
            SessionDescription.Type.ANSWER
        }
        val remoteSdp = SessionDescription(sdpType, msg.sdpDescription)
        webRtcClient.setRemoteDescription(pc, remoteSdp)

        if (sdpType == SessionDescription.Type.OFFER) {
            // Receiver generates SDP Answer
            val answerResult = webRtcClient.createAnswer(pc)
            answerResult.onSuccess { sdpAnswer ->
                webRtcClient.setLocalDescription(pc, sdpAnswer)
                val answerMsg = SignalingMessage.SignalingSdp(
                    transferId = transferId,
                    sdpType = "answer",
                    sdpDescription = sdpAnswer.description,
                    senderNxId = getMyNxId(),
                    recipientNxId = msg.senderNxId
                )
                signalingClient.sendMessage(answerMsg)
                Log.d(TAG, "Dispatched SDP Answer to ${msg.senderNxId}")
            }
        }
    }

    private fun handleIncomingIce(msg: SignalingMessage.SignalingIce) {
        val pc = activePeerConnections[msg.transferId] ?: return
        val session = activeSessions[msg.transferId]
        if (msg.sdpCandidate.contains("typ relay", ignoreCase = true)) {
            session?.activeCandidateType = "relay"
        }
        val iceCandidate = IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.sdpCandidate)
        pc.addIceCandidate(iceCandidate)
    }

    private suspend fun handleResumeRequest(msg: SignalingMessage.ResumeFromChunk) {
        val transfer = transferDao.getTransferById(msg.transferId) ?: return
        val channel = activeDataChannels[msg.transferId] ?: return
        startSenderStreaming(msg.transferId, transfer, channel, msg.startChunkIndex)
    }

    private fun setupSenderPeerConnection(transferId: Long, transfer: TransferEntity): PeerConnection? {
        val pc = webRtcClient.buildPeerConnection(object : SimplePeerConnectionObserver() {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                val session = activeSessions[transferId]
                val hasTurn = session?.hasTurnConfig?.get() ?: false
                val diag = WebRtcClient.determineDiagnostic(state, session?.activeCandidateType, hasTurn)
                session?.updateDiagnostic(diag)
                Log.d(TAG, "Sender transfer #$transferId ICE state: $state, diagnostic: $diag")

                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        session?.transitionTo(PeerLifecycleState.CONNECTED)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        session?.transitionTo(PeerLifecycleState.DISCONNECTED)
                        Log.w(TAG, "Sender transfer #$transferId ICE disconnected")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        session?.transitionTo(PeerLifecycleState.FAILED)
                        Log.w(TAG, "Sender transfer #$transferId ICE failed. Triggering controlled ICE restart...")
                        scope.launch {
                            triggerIceRestart(transferId, transfer)
                        }
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        session?.transitionTo(PeerLifecycleState.CLOSED)
                    }
                    else -> {}
                }
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                val session = activeSessions[transferId]
                if (candidate.sdp.contains("typ relay", ignoreCase = true)) {
                    session?.activeCandidateType = "relay"
                } else if (session?.activeCandidateType == null) {
                    session?.activeCandidateType = if (candidate.sdp.contains("typ srflx", ignoreCase = true)) "srflx" else "host"
                }
                val iceMsg = SignalingMessage.SignalingIce(
                    transferId = transferId,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    sdpCandidate = candidate.sdp,
                    senderNxId = getMyNxId(),
                    recipientNxId = transfer.conversationId
                )
                signalingClient.sendMessage(iceMsg)
            }
        }) ?: return null

        val session = WebRtcSession(transferId, pc)
        session.hasTurnConfig.set(com.nexa.app.core.webrtc.TurnConfigManager.turnConfigs.isNotEmpty())
        session.transitionTo(PeerLifecycleState.INITIALIZING)
        activeSessions[transferId] = session
        return pc
    }

    private fun observeNetworkChanges() {
        val monitor = networkMonitor ?: return
        scope.launch {
            monitor.networkChangeEvents.collect { event ->
                onNetworkInterfaceChanged(event)
            }
        }
    }

    private fun onNetworkInterfaceChanged(event: NetworkChangeEvent) {
        networkDebounceJob?.cancel()
        networkDebounceJob = scope.launch {
            // Debounce rapid callbacks (800ms) to allow OS route stabilization
            delay(800)

            if (!event.isOnline && event.networkState is NetworkTypeState.Disconnected) {
                Log.d(TAG, "Network disconnected, holding active P2P transfers in pending state...")
                return@launch
            }

            Log.i(TAG, "Network interface transition detected: ${event.networkState} (handle=${event.networkHandle})")

            for ((transferId, session) in activeSessions) {
                val pc = activePeerConnections[transferId] ?: continue
                if (session.isClosed.get()) continue

                val transfer = transferDao.getTransferById(transferId) ?: continue
                val status = transfer.status
                if (status != "CONNECTING" && status != "TRANSFERRING") {
                    continue
                }

                val iceState = pc.iceConnectionState()
                val needsRestart = iceState == PeerConnection.IceConnectionState.DISCONNECTED ||
                        iceState == PeerConnection.IceConnectionState.FAILED ||
                        (session.activeNetworkHandle != 0L && session.activeNetworkHandle != event.networkHandle)

                if (needsRestart) {
                    Log.i(TAG, "Triggering controlled ICE restart for transfer #$transferId due to network interface change")
                    session.activeNetworkHandle = event.networkHandle
                    triggerIceRestart(transferId, transfer)
                } else {
                    Log.d(TAG, "Transfer #$transferId connection is healthy ($iceState), skipping unnecessary restart")
                }
            }
        }
    }

    private suspend fun triggerIceRestart(transferId: Long, transfer: TransferEntity) {
        val pc = activePeerConnections[transferId] ?: return
        val session = activeSessions[transferId] ?: return

        // Prevent concurrent simultaneous ICE restarts
        if (!session.isRestartingIce.compareAndSet(false, true)) {
            Log.d(TAG, "ICE restart already in progress for transfer #$transferId, ignoring duplicate trigger")
            return
        }

        // Rate-limit: at least 3 seconds between restarts on the same transfer
        val now = System.currentTimeMillis()
        if (now - session.lastRestartTimestamp < 3000L) {
            session.isRestartingIce.set(false)
            return
        }
        session.lastRestartTimestamp = now

        try {
            session.transitionTo(PeerLifecycleState.RECONNECTING)
            Log.i(TAG, "Executing controlled ICE restart for transfer #$transferId")

            val offerResult = webRtcClient.restartIce(pc)
            offerResult.onSuccess { sdpOffer ->
                val sdpMsg = SignalingMessage.SignalingSdp(
                    transferId = transferId,
                    sdpType = "offer",
                    sdpDescription = sdpOffer.description,
                    senderNxId = getMyNxId(),
                    recipientNxId = transfer.conversationId
                )
                signalingClient.sendMessage(sdpMsg)
                Log.d(TAG, "Dispatched controlled ICE restart offer for transfer #$transferId")
            }.onFailure { e ->
                Log.e(TAG, "ICE restart failed for transfer #$transferId", e)
                session.transitionTo(PeerLifecycleState.FAILED)
            }
        } finally {
            scope.launch {
                delay(2000)
                session.isRestartingIce.set(false)
            }
        }
    }

    private fun setupReceiverPeerConnection(transferId: Long, transfer: TransferEntity): PeerConnection? {
        require(fileSecurityManager.validateFileSize(transfer.fileSizeBytes))
        val quarantineDir = incomingQuarantineDirectory().apply {
            check(exists() || mkdirs()) { "Unable to create private quarantine storage" }
        }
        val tmpFile = File(quarantineDir, "quarantine_${transferId}.tmp")
        val randomAccessFile = RandomAccessFile(tmpFile, "rw")
        randomAccessFile.setLength(transfer.fileSizeBytes)
        activeRandomAccessFiles[transferId] = randomAccessFile

        val pc = webRtcClient.buildPeerConnection(object : SimplePeerConnectionObserver() {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                val session = activeSessions[transferId]
                val hasTurn = session?.hasTurnConfig?.get() ?: false
                val diag = WebRtcClient.determineDiagnostic(state, session?.activeCandidateType, hasTurn)
                session?.updateDiagnostic(diag)
                Log.d(TAG, "Receiver transfer #$transferId ICE state: $state, diagnostic: $diag")

                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        session?.transitionTo(PeerLifecycleState.CONNECTED)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        session?.transitionTo(PeerLifecycleState.DISCONNECTED)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        session?.transitionTo(PeerLifecycleState.FAILED)
                        Log.w(TAG, "Receiver transfer #$transferId ICE failed. Requesting sender resume...")
                        scope.launch {
                            val currentTransfer = transferDao.getTransferById(transferId)
                            if (currentTransfer != null) {
                                val resumeMsg = SignalingMessage.ResumeFromChunk(
                                    transferId = transferId,
                                    startChunkIndex = currentTransfer.chunksCompleted,
                                    senderNxId = getMyNxId(),
                                    recipientNxId = currentTransfer.conversationId
                                )
                                signalingClient.sendMessage(resumeMsg)
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        session?.transitionTo(PeerLifecycleState.CLOSED)
                    }
                    else -> {}
                }
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                val session = activeSessions[transferId]
                if (candidate.sdp.contains("typ relay", ignoreCase = true)) {
                    session?.activeCandidateType = "relay"
                } else if (session?.activeCandidateType == null) {
                    session?.activeCandidateType = if (candidate.sdp.contains("typ srflx", ignoreCase = true)) "srflx" else "host"
                }
                val iceMsg = SignalingMessage.SignalingIce(
                    transferId = transferId,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    sdpCandidate = candidate.sdp,
                    senderNxId = getMyNxId(),
                    recipientNxId = transfer.conversationId
                )
                signalingClient.sendMessage(iceMsg)
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.i(TAG, "Receiver accepted DataChannel: ${dataChannel.label()}")
                activeDataChannels[transferId] = dataChannel
                activeSessions[transferId]?.dataChannel = dataChannel

                dataChannel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        Log.d(TAG, "Receiver DataChannel state: ${dataChannel.state()}")
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        scope.launch {
                            try {
                                val chunk = FileChunk.deserialize(buffer.data)

                                // CHECKPOINT 2: TRANSFER-TIME CHUNK INTEGRITY VERIFICATION
                                val verifyResult = chunkIntegrityVerifier.verifyChunk(
                                    chunk = chunk,
                                    senderNxId = transfer.conversationId,
                                    recipientNxId = getMyNxId()
                                )

                                when (verifyResult) {
                                    is ChunkVerificationResult.DuplicateOrReplayed -> {
                                        Log.w(TAG, "Transfer #$transferId duplicate/replayed chunk #${chunk.chunkIndex} received. Dropping.")
                                        return@launch
                                    }
                                    is ChunkVerificationResult.Rejected -> {
                                        Log.e(TAG, "Transfer #$transferId Checkpoint 2 rejection on chunk #${chunk.chunkIndex}: ${verifyResult.reason} (${verifyResult.threatCategory})")
                                        try {
                                            activeRandomAccessFiles.remove(transferId)?.close()
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error closing RAF", e)
                                        }
                                        FileShredder.shred(tmpFile)
                                        chunkIntegrityVerifier.unregisterSession(transferId)
                                        transferDao.updateScanResult(
                                            transferId = transferId,
                                            securityState = "REJECTED_TRANSFER",
                                            scanResult = "MALICIOUS",
                                            threatDetails = verifyResult.reason
                                        )
                                        transferDao.updateStatus(transferId, "REJECTED_CORRUPTED")
                                        cleanupTransfer(transferId)
                                        return@launch
                                    }
                                    is ChunkVerificationResult.Valid -> {
                                        // Proceed to write chunk to disk
                                    }
                                }

                                val offset = chunk.chunkIndex.toLong() * FileChunk.DEFAULT_CHUNK_SIZE
                                randomAccessFile.seek(offset)
                                randomAccessFile.write(chunk.data)

                                val completed = chunk.chunkIndex + 1
                                transferDao.updateTransferProgress(transferId, completed, "TRANSFERRING")

                                val percent = if (transfer.totalChunks > 0) (completed * 100) / transfer.totalChunks else 0
                                P2PTransferService.updateProgress(context, transferId, percent)

                                if (chunk.isEof) {
                                    Log.i(TAG, "Receiver reached EOF for transfer #$transferId. Finalizing...")
                                    try {
                                        activeRandomAccessFiles.remove(transferId)?.close()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error closing RAF on EOF", e)
                                    }

                                    // CHECKPOINT 2: Finalize session and verify completeness
                                    val completionResult = chunkIntegrityVerifier.finalizeSession(transferId)
                                    chunkIntegrityVerifier.unregisterSession(transferId)

                                    when (completionResult) {
                                        is TransferCompletionResult.Incomplete -> {
                                            Log.e(TAG, "Transfer #$transferId EOF reached but missing ${completionResult.missingChunks.size} chunks")
                                            FileShredder.shred(tmpFile)
                                            transferDao.updateScanResult(
                                                transferId = transferId,
                                                securityState = "FAILED",
                                                scanResult = "CORRUPTED",
                                                threatDetails = "Incomplete transfer: missing ${completionResult.missingChunks.size} chunks"
                                            )
                                            transferDao.updateStatus(transferId, "REJECTED_CORRUPTED")
                                            cleanupTransfer(transferId)
                                            return@launch
                                        }
                                        is TransferCompletionResult.Corrupted -> {
                                            Log.e(TAG, "Transfer #$transferId verification corrupted: ${completionResult.reason}")
                                            FileShredder.shred(tmpFile)
                                            transferDao.updateScanResult(
                                                transferId = transferId,
                                                securityState = "FAILED",
                                                scanResult = "CORRUPTED",
                                                threatDetails = completionResult.reason
                                            )
                                            transferDao.updateStatus(transferId, "REJECTED_CORRUPTED")
                                            cleanupTransfer(transferId)
                                            return@launch
                                        }
                                        is TransferCompletionResult.Verified -> {
                                            Log.i(TAG, "Transfer #$transferId Checkpoint 2 passed (${completionResult.totalBytes} bytes, ${completionResult.totalChunks} chunks). Proceeding to Checkpoint 3...")
                                            finalizeIncomingFile(transferId, transfer, tmpFile)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing incoming file chunk", e)
                            }
                        }
                    }
                })
            }
        }) ?: return null

        val session = WebRtcSession(transferId, pc)
        session.hasTurnConfig.set(com.nexa.app.core.webrtc.TurnConfigManager.turnConfigs.isNotEmpty())
        session.transitionTo(PeerLifecycleState.INITIALIZING)
        activeSessions[transferId] = session
        return pc
    }

    private fun startSenderStreaming(
        transferId: Long,
        transfer: TransferEntity,
        dataChannel: DataChannel,
        startChunk: Int = 0
    ) {
        scope.launch {
            try {
                transferDao.updateStatus(transferId, "TRANSFERRING")
                val bridge = FileDataChannelBridge(dataChannel)

                val pfd = activeFileDescriptors[transferId]
                    ?: context.contentResolver.openFileDescriptor(Uri.parse(transfer.localUri), "r")
                    ?: throw IllegalStateException("Cannot open ParcelFileDescriptor for ${transfer.localUri}")

                activeFileDescriptors[transferId] = pfd

                bridge.streamFromParcelFileDescriptor(
                    pfd = pfd,
                    transferId = transferId,
                    totalBytes = transfer.fileSizeBytes,
                    startChunkIndex = startChunk
                ) { sentBytes, totalBytes, chunkIndex ->
                    scope.launch {
                        transferDao.updateTransferProgress(transferId, chunkIndex, "TRANSFERRING")
                        val percent = if (totalBytes > 0) ((sentBytes * 100) / totalBytes).toInt() else 0
                        P2PTransferService.updateProgress(context, transferId, percent)
                    }
                }

                transferDao.updateStatus(transferId, "COMPLETED")
                P2PTransferService.stop(context, transferId)
                Log.i(TAG, "P2P transfer #$transferId completed successfully on Sender side!")
            } catch (e: Exception) {
                Log.e(TAG, "Error during sender file streaming", e)
                transferDao.updateStatus(transferId, "FAILED")
                P2PTransferService.stop(context, transferId)
            }
        }
    }

    private suspend fun finalizeIncomingFile(
        transferId: Long,
        transfer: TransferEntity,
        tmpFile: File
    ) = withContext(Dispatchers.IO) {
        try {
            transferDao.markScanning(transferId)
            if (tmpFile.length() != transfer.fileSizeBytes) {
                FileShredder.shred(tmpFile)
                transferDao.updateScanResult(
                    transferId,
                    "FAILED",
                    "CORRUPTED",
                    "Received file size does not match the offered size"
                )
                transferDao.updateStatus(transferId, "REJECTED_CORRUPTED")
                P2PTransferService.stop(context, transferId)
                return@withContext
            }
            var classification = FileReleaseClassifier.classify(tmpFile, transfer.fileName)
            transferDao.updateFileClassification(transferId, classification.name)

            // GATE 2: INBOUND QUARANTINE SCAN & INTEGRITY VERIFICATION
            val verdict = fileSecurityManager.scanInboundQuarantine(
                tempFile = tmpFile,
                expectedHash = transfer.fileHash,
                declaredName = transfer.fileName
            )

            when (verdict) {
                is SecurityScanResult.Safe -> {
                    // Retain in quarantine awaiting explicit receiver acceptance (Save to NEXA)
                    transferDao.updateQuarantinePath(transferId, tmpFile.absolutePath)
                    transferDao.updateScanResult(
                        transferId = transferId,
                        securityState = "WAITING_FOR_USER_ACCEPTANCE",
                        scanResult = "SAFE",
                        threatDetails = null
                    )
                    transferDao.updateFileClassification(transferId, classification.name)
                    transferDao.updateStatus(transferId, "SCAN_PASSED")
                    P2PTransferService.stop(context, transferId)
                    Log.i(TAG, "Incoming file #$transferId passed the receiver scan; awaiting its type-specific release gate.")
                }
                is SecurityScanResult.Corrupted -> {
                    Log.e(TAG, "Quarantined file #$transferId integrity verification failed: ${verdict.message}")
                    FileShredder.shred(tmpFile)
                    transferDao.updateScanResult(
                        transferId = transferId,
                        securityState = "FAILED",
                        scanResult = "MALICIOUS",
                        threatDetails = verdict.message
                    )
                    transferDao.updateStatus(transferId, "REJECTED_CORRUPTED")
                    P2PTransferService.stop(context, transferId)
                }
                is SecurityScanResult.Rejected -> {
                    Log.w(TAG, "Quarantined file #$transferId security scan rejected: ${verdict.reason}")
                    if (verdict.threatType == ThreatType.ARCHIVE_EXECUTABLE_CONTENT) {
                        classification = FileClassification.EXECUTABLE
                    }
                    transferDao.updateFileClassification(transferId, classification.name)
                    transferDao.updateQuarantinePath(transferId, tmpFile.absolutePath)
                    transferDao.updateScanResult(
                        transferId = transferId,
                        securityState = "SCAN_REJECTED",
                        scanResult = "MALICIOUS",
                        threatDetails = verdict.reason
                    )
                    transferDao.updateStatus(transferId, "REJECTED_MALICIOUS")
                    P2PTransferService.stop(context, transferId)
                }
                is SecurityScanResult.Inconclusive -> {
                    Log.w(TAG, "Quarantined file #$transferId scan inconclusive: ${verdict.reason}")
                    transferDao.updateQuarantinePath(transferId, tmpFile.absolutePath)
                    transferDao.updateScanResult(
                        transferId,
                        "RESTRICTED",
                        "INCONCLUSIVE",
                        threatDetails = verdict.reason
                    )
                    transferDao.updateStatus(transferId, "SCAN_INCONCLUSIVE")
                    P2PTransferService.stop(context, transferId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing incoming file", e)
            if (tmpFile.exists()) transferDao.updateQuarantinePath(transferId, tmpFile.absolutePath)
            transferDao.updateScanResult(
                transferId,
                "RESTRICTED",
                "INCONCLUSIVE",
                e.localizedMessage ?: "Receiver scan failed"
            )
            transferDao.updateStatus(transferId, "SCAN_ERROR")
            P2PTransferService.stop(context, transferId)
        }
    }

    override suspend fun releaseTransfer(
        transferId: Long,
        authorization: ReleaseAuthorization
    ): Result<File> = withContext(Dispatchers.IO) {
        val transfer = transferDao.getTransferById(transferId)
            ?: return@withContext Result.failure(IllegalArgumentException("Transfer #$transferId not found"))

        if (!transfer.isIncoming) {
            return@withContext Result.failure(SecurityException("Only received files can be released from quarantine"))
        }

        val quarantineDir = incomingQuarantineDirectory()
        val tmpFile = File(quarantineDir, "quarantine_${transferId}.tmp")
        if (!tmpFile.exists() || !tmpFile.isFile || tmpFile.length() != transfer.fileSizeBytes) {
            return@withContext Result.failure(IllegalStateException("Quarantined payload missing or incomplete for transfer #$transferId"))
        }

        try {
            val classification = FileReleaseClassifier.classify(tmpFile, transfer.fileName)
            if (classification.name != transfer.fileClassification) {
                return@withContext Result.failure(SecurityException("File type changed after the receiver scan"))
            }

            if (!FileReleasePolicy.isAuthorized(
                    classification,
                    transfer.status,
                    transfer.scanResult,
                    authorization
                )
            ) {
                return@withContext Result.failure(
                    SecurityException("The scan result, file type, or confirmation policy does not permit release")
                )
            }

            val actualHash = fileSecurityManager.computeSha256(tmpFile)
            if (!actualHash.equals(transfer.fileHash, ignoreCase = true)) {
                FileShredder.shred(tmpFile)
                transferDao.updateScanResult(transferId, "SCAN_INVALIDATED", "INCONCLUSIVE", "File changed after scanning")
                transferDao.updateStatus(transferId, "REJECTED_CORRUPTED")
                return@withContext Result.failure(SecurityException("File changed after scanning; release was blocked"))
            }

            val safeVaultDir = File(context.filesDir, "vault/safe")
            check(safeVaultDir.exists() || safeVaultDir.mkdirs()) { "Unable to create the safe vault" }
            val safeName = transfer.fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trim('.')
                .take(100)
                .ifBlank { "received-file" }
            val safeDestination = File(safeVaultDir, "${transferId}_${safeName}")
            check(safeDestination.canonicalFile.parentFile == safeVaultDir.canonicalFile) {
                "Release destination escaped the safe vault"
            }
            if (safeDestination.exists()) {
                return@withContext Result.failure(IllegalStateException("A released file already exists for this transfer"))
            }

            val stagingFile = File.createTempFile("release_${transferId}_", ".tmp", safeVaultDir)
            try {
                FileInputStream(tmpFile).use { input ->
                    java.io.FileOutputStream(stagingFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                if (!fileSecurityManager.computeSha256(stagingFile).equals(actualHash, ignoreCase = true)) {
                    throw SecurityException("File content changed while being released")
                }
                check(stagingFile.renameTo(safeDestination)) { "Unable to atomically move file into the safe vault" }
            } finally {
                if (stagingFile.exists()) stagingFile.delete()
            }

            if (transferDao.markReleased(transferId, safeDestination.absolutePath) != 1) {
                safeDestination.delete()
                return@withContext Result.failure(IllegalStateException("Transfer state changed before release could be recorded"))
            }
            if (!FileShredder.shred(tmpFile)) {
                Log.w(TAG, "Released transfer #$transferId but could not fully remove its quarantine copy")
            }
            Log.i(TAG, "Transfer #$transferId was explicitly released to the private safe vault")
            Result.success(safeDestination)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release transfer #$transferId", e)
            Result.failure(e)
        }
    }

    override suspend fun shredTransferQuarantine(transferId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            cleanupTransfer(transferId)
            val quarantineDir = incomingQuarantineDirectory()
            val tmpFile = File(quarantineDir, "quarantine_${transferId}.tmp")
            if (tmpFile.exists() && !FileShredder.shred(tmpFile)) {
                return@withContext Result.failure(IllegalStateException("Unable to purge quarantined file #$transferId"))
            }
            transferDao.deleteTransfer(transferId)
            Log.i(TAG, "Shredded quarantined payload and deleted transfer #$transferId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error shredding quarantine for transfer #$transferId", e)
            Result.failure(e)
        }
    }

    override suspend fun getVerifiedSafeFile(transferId: Long): File? = withContext(Dispatchers.IO) {
        val transfer = transferDao.getTransferById(transferId) ?: return@withContext null
        if (transfer.isIncoming &&
            (transfer.status != "COMPLETED" ||
                transfer.securityState != "RELEASED" ||
                !transfer.isExplicitlyAccepted)
        ) {
            Log.w(TAG, "Attempted to access unreleased transfer #$transferId")
            return@withContext null
        }
        val file = File(transfer.localUri)
        val safeVault = File(context.filesDir, "vault/safe").canonicalFile
        if (file.exists() && file.isFile && file.canonicalFile.parentFile == safeVault) file else null
    }

    private fun incomingQuarantineDirectory(): File =
        File(context.filesDir, "security/incoming_quarantine")

    private fun recoverInterruptedQuarantineScans() {
        scope.launch {
            val interrupted = transferDao.getTransfersBySecurityState("SCANNING")
            for (transfer in interrupted) {
                if (!transfer.isIncoming || transfer.status != "VERIFYING") continue
                val file = File(incomingQuarantineDirectory(), "quarantine_${transfer.transferId}.tmp")
                if (file.isFile && file.length() == transfer.fileSizeBytes) {
                    Log.i(TAG, "Resuming receiver scan for interrupted transfer #${transfer.transferId}")
                    finalizeIncomingFile(transfer.transferId, transfer, file)
                } else {
                    transferDao.updateScanResult(
                        transfer.transferId,
                        "RESTRICTED",
                        "INCONCLUSIVE",
                        "Incomplete quarantine file after interrupted scan"
                    )
                    transferDao.updateStatus(transfer.transferId, "SCAN_ERROR")
                }
            }
        }
    }

    private fun cleanupTransfer(transferId: Long) {
        chunkIntegrityVerifier.unregisterSession(transferId)
        val session = activeSessions.remove(transferId)
        if (session != null) {
            session.close()
        } else {
            val dc = activeDataChannels.remove(transferId)
            val pc = activePeerConnections.remove(transferId)
            WebRtcClient.safeDisposeSession(dc, pc)
        }
        activeDataChannels.remove(transferId)
        activePeerConnections.remove(transferId)

        try {
            activeRandomAccessFiles.remove(transferId)?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal error closing RAF for transfer #$transferId", e)
        }

        try {
            activeFileDescriptors.remove(transferId)?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal error closing PFD for transfer #$transferId", e)
        }

        P2PTransferService.stop(context, transferId)
    }

    private fun computeFastHash(uri: Uri): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val fis = FileInputStream(pfd?.fileDescriptor)
            val buffer = ByteArray(16384)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
            fis.close()
            pfd?.close()
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "fast_hash_${System.currentTimeMillis()}"
        }
    }

    private fun getMyNxId(): String {
        return secureStorage.getActiveNxId() ?: "NX-ANONYMOUS"
    }

    companion object {
        private const val TAG = "P2PTransferRepo"
    }
}

private open class SimplePeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dataChannel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
}
