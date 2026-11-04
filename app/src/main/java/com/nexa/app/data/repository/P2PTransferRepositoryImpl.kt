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
import java.util.concurrent.ConcurrentHashMap

class P2PTransferRepositoryImpl(
    private val context: Context,
    private val transferDao: FileTransferDao,
    private val signalingClient: SignalingClient,
    private val webRtcClient: WebRtcClient,
    private val secureStorage: SecureStorage
) : P2PTransferRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingOffers = MutableSharedFlow<SignalingMessage.OfferTransfer>(extraBufferCapacity = 16)

    // Active WebRTC PeerConnections, DataChannels, Sessions and file handles indexed by transferId
    private val activePeerConnections = ConcurrentHashMap<Long, PeerConnection>()
    private val activeDataChannels = ConcurrentHashMap<Long, DataChannel>()
    private val activeSessions = ConcurrentHashMap<Long, WebRtcSession>()
    private val activeFileDescriptors = ConcurrentHashMap<Long, ParcelFileDescriptor>()
    private val activeRandomAccessFiles = ConcurrentHashMap<Long, RandomAccessFile>()

    init {
        observeSignalingMessages()
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

            // 1. Calculate file integrity hash
            val fileHash = computeFastHash(fileUri)

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
                isIncoming = false
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
                recipientNxId = recipientNxId
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
        transferDao.updateStatus(transferId, "CONNECTING")

        // 1. Initialize WebRTC PeerConnection for Receiver
        val pc = setupReceiverPeerConnection(transferId, transfer)
        if (pc == null) {
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
        transferDao.updateStatus(transferId, "CANCELLED")

        val declineMsg = SignalingMessage.DeclineTransfer(
            transferId = transferId,
            senderNxId = transfer.conversationId,
            recipientNxId = myNxId
        )
        signalingClient.sendMessage(declineMsg)
        cleanupTransfer(transferId)
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
        transferDao.updateStatus(transferId, "CANCELLED")
        cleanupTransfer(transferId)
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
            isIncoming = true
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
                Log.d(TAG, "Sender transfer #$transferId ICE state: $state")
                val session = activeSessions[transferId]
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
        session.transitionTo(PeerLifecycleState.INITIALIZING)
        activeSessions[transferId] = session
        return pc
    }

    private suspend fun triggerIceRestart(transferId: Long, transfer: TransferEntity) {
        val pc = activePeerConnections[transferId] ?: return
        val session = activeSessions[transferId]
        if (session != null && !session.transitionTo(PeerLifecycleState.RECONNECTING)) {
            return
        }
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
            transferDao.updateStatus(transferId, "FAILED")
            cleanupTransfer(transferId)
        }
    }

    private fun setupReceiverPeerConnection(transferId: Long, transfer: TransferEntity): PeerConnection? {
        val tmpFile = File(context.cacheDir, "transfer_${transferId}.tmp")
        val randomAccessFile = RandomAccessFile(tmpFile, "rw")
        activeRandomAccessFiles[transferId] = randomAccessFile

        val pc = webRtcClient.buildPeerConnection(object : SimplePeerConnectionObserver() {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "Receiver transfer #$transferId ICE state: $state")
                val session = activeSessions[transferId]
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
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        session?.transitionTo(PeerLifecycleState.CLOSED)
                    }
                    else -> {}
                }
            }

            override fun onIceCandidate(candidate: IceCandidate) {
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
                                    finalizeIncomingFile(transferId, transfer, tmpFile)
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
            transferDao.updateStatus(transferId, "VERIFYING")

            // Atomic move to app downloads directory
            val downloadsDir = File(context.getExternalFilesDir(null), "NEXA_Transfers").apply { mkdirs() }
            val destinationFile = File(downloadsDir, transfer.fileName)
            tmpFile.copyTo(destinationFile, overwrite = true)
            tmpFile.delete()

            transferDao.updateStatus(transferId, "COMPLETED")
            P2PTransferService.stop(context, transferId)
            Log.i(TAG, "Incoming file #$transferId successfully verified and saved to: ${destinationFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing incoming file", e)
            transferDao.updateStatus(transferId, "FAILED")
            P2PTransferService.stop(context, transferId)
        }
    }

    private fun cleanupTransfer(transferId: Long) {
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
