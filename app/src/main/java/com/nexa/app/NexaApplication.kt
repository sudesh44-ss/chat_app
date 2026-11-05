package com.nexa.app

import android.app.Application
import android.util.Log
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.keystore.HardwareKeystoreVault
import com.nexa.app.core.network.NetworkMonitor
import com.nexa.app.core.network.WebSocketManager
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.webrtc.WebRtcClient
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.remote.api.AuthApiService
import com.nexa.app.data.remote.api.AuthApiServiceImpl
import com.nexa.app.data.remote.api.DtnRelayApi
import com.nexa.app.data.remote.api.DtnRelayApiImpl
import com.nexa.app.data.remote.signaling.SignalingClient
import com.nexa.app.data.remote.signaling.SwarmSignalingClient
import com.nexa.app.data.remote.sms.NexaDataSmsSender
import com.nexa.app.data.remote.swarm.P2PSwarmCoordinator
import com.nexa.app.data.remote.swarm.QuarantineSandbox
import com.nexa.app.data.repository.AuthRepositoryImpl
import com.nexa.app.data.repository.ChatRepositoryImpl
import com.nexa.app.data.repository.DtnRepositoryImpl
import com.nexa.app.data.repository.P2PTransferRepositoryImpl
import com.nexa.app.data.repository.SecureFileTransferRepositoryImpl
import com.nexa.app.data.repository.SecureMessageRepositoryImpl
import com.nexa.app.data.transport.InternetTransport
import com.nexa.app.data.transport.MeshTransport
import com.nexa.app.data.transport.SmsTransport
import com.nexa.app.data.transport.TransportRouter
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.DtnRepository
import com.nexa.app.domain.repository.P2PTransferRepository
import com.nexa.app.domain.repository.SecureFileTransferRepository
import com.nexa.app.domain.repository.SecureMessageRepository

class NexaApplication : Application() {

    lateinit var secureStorage: SecureStorage
        private set

    lateinit var keystoreManager: CryptoKeystoreManager
        private set

    lateinit var database: NexaDatabase
        private set

    lateinit var apiService: AuthApiService
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var webSocketManager: WebSocketManager
        private set

    lateinit var networkMonitor: NetworkMonitor
        private set

    lateinit var dataSmsSender: NexaDataSmsSender
        private set

    lateinit var dtnRelayApi: DtnRelayApi
        private set

    lateinit var dtnRepository: DtnRepository
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var signalingClient: SignalingClient
        private set

    lateinit var webRtcClient: WebRtcClient
        private set

    lateinit var p2pTransferRepository: P2PTransferRepository
        private set

    lateinit var quarantineSandbox: QuarantineSandbox
        private set

    lateinit var swarmSignalingClient: SwarmSignalingClient
        private set

    lateinit var swarmCoordinator: P2PSwarmCoordinator
        private set

    lateinit var secureFileTransferRepository: SecureFileTransferRepository
        private set

    lateinit var hardwareKeystoreVault: HardwareKeystoreVault
        private set

    lateinit var doubleRatchetEngine: DoubleRatchetEngine
        private set

    lateinit var transportRouter: TransportRouter
        private set

    lateinit var pushManager: com.nexa.app.data.remote.push.NexaPushManager
        private set

    lateinit var secureMessageRepository: SecureMessageRepository
        private set

    lateinit var backupPrefs: com.nexa.app.data.local.preferences.BackupPrefs
        private set

    lateinit var googleDriveAuthManager: com.nexa.app.data.drive.GoogleDriveAuthManager
        private set

    lateinit var backupCryptoEngine: com.nexa.app.core.backup.BackupCryptoEngine
        private set

    lateinit var driveAppDataService: com.nexa.app.data.drive.DriveAppDataService
        private set

    lateinit var driveResumableUploader: com.nexa.app.data.drive.DriveResumableUploader
        private set

    lateinit var driveBackupRepository: com.nexa.app.domain.repository.DriveBackupRepository
        private set

    lateinit var handshakeApi: com.nexa.app.data.remote.api.HandshakeApi
        private set

    lateinit var contactRepository: com.nexa.app.domain.repository.ContactRepository
        private set

    lateinit var googleCodeScannerEngine: com.nexa.app.data.remote.scanner.GoogleCodeScannerEngine
        private set

    lateinit var qrSyncApi: com.nexa.app.data.remote.api.QrSyncApi
        private set

    lateinit var qrHandshakeRepository: com.nexa.app.domain.repository.QrHandshakeRepository
        private set

    lateinit var videoCompressionEngine: com.nexa.app.core.media.VideoCompressionEngine
        private set

    lateinit var mediaPrefs: com.nexa.app.data.local.preferences.MediaPrefs
        private set

    lateinit var chatPrivacyPrefs: com.nexa.app.data.local.preferences.ChatPrivacyPrefs
        private set

    lateinit var mediaProcessingRepository: com.nexa.app.domain.repository.MediaProcessingRepository
        private set

    lateinit var threatBloomFilter: com.nexa.app.core.security.ThreatBloomFilter
        private set

    lateinit var yaraNativeBridge: com.nexa.app.core.security.YaraNativeBridge
        private set

    lateinit var dualScanEngine: com.nexa.app.core.security.DualScanEngine
        private set

    lateinit var quarantineSandboxSink: com.nexa.app.data.remote.transfer.QuarantineSandboxSink
        private set

    lateinit var securityScannerRepository: com.nexa.app.domain.repository.SecurityScannerRepository
        private set

    lateinit var memoryBufferPool: com.nexa.app.core.hardware.MemoryBufferPool
        private set

    lateinit var thermalThrottlingMonitor: com.nexa.app.core.hardware.ThermalThrottlingMonitor
        private set

    lateinit var chunkCipherEngine: com.nexa.app.core.crypto.ChunkCipherEngine
        private set

    lateinit var lanDiscoveryBridge: com.nexa.app.core.webrtc.LanDiscoveryBridge
        private set

    lateinit var robustTransferRepository: com.nexa.app.data.repository.RobustTransferRepositoryImpl
        private set

    lateinit var lqiDecayEngine: com.nexa.app.core.mesh.engine.LqiDecayEngine
        private set

    lateinit var compositeMetricEngine: com.nexa.app.core.mesh.engine.CompositeMetricEngine
        private set

    lateinit var backpressureController: com.nexa.app.core.mesh.engine.BackpressureController
        private set

    lateinit var bloomDuplicateFilter: com.nexa.app.core.mesh.engine.BloomDuplicateFilter
        private set

    lateinit var custodyTransferManager: com.nexa.app.core.mesh.dtn.CustodyTransferManager
        private set

    lateinit var notificationPrefs: com.nexa.app.data.local.preferences.NotificationPrefs
        private set

    lateinit var notificationDispatcher: com.nexa.app.core.notification.NexaNotificationDispatcher
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize hardware keystore and secure preferences
        secureStorage = SecureStorage(this)
        keystoreManager = CryptoKeystoreManager(this)

        // 2. Initialize encrypted SQLite database with SQLCipher SupportFactory
        val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
        database = NexaDatabase.getInstance(this, dbPassphrase)

        // 3. Initialize remote API service
        apiService = AuthApiServiceImpl()

        // 4. Initialize domain repository coordinator
        authRepository = AuthRepositoryImpl(
            apiService = apiService,
            userDao = database.userDao(),
            keystoreManager = keystoreManager,
            secureStorage = secureStorage
        )

        // 5. Initialize network monitoring & WebSocket pipeline
        webSocketManager = WebSocketManager()
        webSocketManager.setIdentitySigner { dataToSign ->
            val prefs = getSharedPreferences("nexa_handshake_keys", android.content.Context.MODE_PRIVATE)
            val privEncHex = prefs.getString("sign_priv_enc", null)
            val privIvHex = prefs.getString("sign_priv_iv", null)
            val pubHex = prefs.getString("sign_pub", null)
            val privKey = if (!privEncHex.isNullOrBlank() && !privIvHex.isNullOrBlank()) {
                try {
                    keystoreManager.decryptWithMasterKey(hexToBytes(privIvHex), hexToBytes(privEncHex))
                } catch (e: Exception) {
                    ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                }
            } else {
                val newPriv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                val (iv, enc) = keystoreManager.encryptWithMasterKey(newPriv)
                val pub = com.nexa.app.core.crypto.Ed25519Signer.derivePublicKey(newPriv)
                prefs.edit()
                    .putString("sign_priv_enc", bytesToHex(enc))
                    .putString("sign_priv_iv", bytesToHex(iv))
                    .putString("sign_pub", bytesToHex(pub))
                    .apply()
                newPriv
            }
            val pubKey = if (!pubHex.isNullOrBlank()) {
                try { hexToBytes(pubHex) } catch (e: Exception) { com.nexa.app.core.crypto.Ed25519Signer.derivePublicKey(privKey) }
            } else {
                com.nexa.app.core.crypto.Ed25519Signer.derivePublicKey(privKey)
            }
            val signature = com.nexa.app.core.crypto.Ed25519Signer.sign(dataToSign, privKey)
            Pair(signature, pubKey)
        }
        networkMonitor = NetworkMonitor(this)

        // 6. Initialize Data SMS Sender for Port 8901 binary transmission
        dataSmsSender = NexaDataSmsSender(this)

        // 7. Initialize DTN Opportunistic Cloud Bridge & Mesh Repository
        dtnRelayApi = DtnRelayApiImpl()
        dtnRepository = DtnRepositoryImpl(
            context = this,
            envelopeDao = database.dtnEnvelopeDao(),
            messageDao = database.messageDao(),
            chatDao = database.chatDao(),
            userDao = database.userDao(),
            secureStorage = secureStorage,
            keystoreManager = keystoreManager,
            relayApi = dtnRelayApi
        )

        // 8. Initialize Hardware Keystore Vault & Double Ratchet Engine
        hardwareKeystoreVault = HardwareKeystoreVault()
        doubleRatchetEngine = DoubleRatchetEngine(
            ratchetDao = database.ratchetDao(),
            identityKeyPairProvider = {
                val prefs = getSharedPreferences("nexa_handshake_keys", android.content.Context.MODE_PRIVATE)
                val privEncHex = prefs.getString("dh_priv_enc", null)
                val privIvHex = prefs.getString("dh_priv_iv", null)
                val pubHex = prefs.getString("dh_pub", null)
                if (!privEncHex.isNullOrBlank() && !privIvHex.isNullOrBlank() && !pubHex.isNullOrBlank()) {
                    try {
                        val decryptedPriv = keystoreManager.decryptWithMasterKey(
                            hexToBytes(privIvHex),
                            hexToBytes(privEncHex)
                        )
                        Pair(decryptedPriv, hexToBytes(pubHex))
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
        )

        // 9. Initialize Privacy-First Notification Subsystem & Channels
        notificationPrefs = com.nexa.app.data.local.preferences.NotificationPrefs(this)
        com.nexa.app.core.notification.NexaNotificationChannelManager.createAllNotificationChannels(this)
        notificationDispatcher = com.nexa.app.core.notification.NexaNotificationDispatcher(this, notificationPrefs)

        // 10. Initialize Chat Repository with Outbox, WebSocket, Data SMS, DTN, and Double Ratchet E2EE
        chatPrivacyPrefs = com.nexa.app.data.local.preferences.ChatPrivacyPrefs(this)
        chatRepository = ChatRepositoryImpl(
            messageDao = database.messageDao(),
            chatDao = database.chatDao(),
            userDao = database.userDao(),
            secureStorage = secureStorage,
            keystoreManager = keystoreManager,
            webSocketManager = webSocketManager,
            dataSmsSender = dataSmsSender,
            networkMonitor = networkMonitor,
            dtnRepository = dtnRepository,
            doubleRatchetEngine = doubleRatchetEngine,
            contactDao = database.contactDao(),
            chatPrivacyPrefs = chatPrivacyPrefs,
            notificationDispatcher = notificationDispatcher
        )

        // 10. Initialize WebRTC Signaling Client, Engine & P2P Transfer Repository
        signalingClient = SignalingClient()
        webRtcClient = WebRtcClient(this)
        p2pTransferRepository = P2PTransferRepositoryImpl(
            context = this,
            transferDao = database.fileTransferDao(),
            signalingClient = signalingClient,
            webRtcClient = webRtcClient,
            secureStorage = secureStorage
        )

        // 11. Initialize Quarantine Sandbox, Swarm Coordinator & Secure File Transfer Repository
        quarantineSandbox = QuarantineSandbox(this)
        swarmSignalingClient = SwarmSignalingClient()
        swarmCoordinator = P2PSwarmCoordinator(
            signalingClient = swarmSignalingClient,
            swarmCacheDao = database.swarmCacheDao()
        )
        secureFileTransferRepository = SecureFileTransferRepositoryImpl(
            context = this,
            swarmCacheDao = database.swarmCacheDao(),
            quarantineSandbox = quarantineSandbox,
            swarmCoordinator = swarmCoordinator,
            secureStorage = secureStorage
        )

        // 12. Multi-Transport E2EE Router
        val internetTransport = InternetTransport(webSocketManager)
        val smsTransport = SmsTransport(dataSmsSender)
        val meshTransport = MeshTransport(dtnRepository)
        transportRouter = TransportRouter(
            internetTransport = internetTransport,
            smsTransport = smsTransport,
            meshTransport = meshTransport
        )

        secureMessageRepository = SecureMessageRepositoryImpl(
            messageDao = database.messageDao(),
            chatDao = database.chatDao(),
            doubleRatchetEngine = doubleRatchetEngine,
            transportRouter = transportRouter,
            secureStorage = secureStorage
        )

        val activeNxId = secureStorage.getActiveNxId()
        if (!activeNxId.isNullOrBlank()) {
            signalingClient.connect(activeNxId)
            swarmSignalingClient.connect(activeNxId)
        }

        // 12. Initialize Zero-Knowledge Google Drive Backup Subsystem
        backupPrefs = com.nexa.app.data.local.preferences.BackupPrefs(this)
        googleDriveAuthManager = com.nexa.app.data.drive.GoogleDriveAuthManager(this)
        backupCryptoEngine = com.nexa.app.core.backup.BackupCryptoEngine(this)
        driveAppDataService = com.nexa.app.data.drive.DriveAppDataService(googleDriveAuthManager)
        driveResumableUploader = com.nexa.app.data.drive.DriveResumableUploader()
        driveBackupRepository = com.nexa.app.data.repository.DriveBackupRepositoryImpl(
            context = this,
            database = database,
            backupCryptoEngine = backupCryptoEngine,
            driveService = driveAppDataService,
            resumableUploader = driveResumableUploader,
            backupPrefs = backupPrefs,
            secureStorage = secureStorage,
            authManager = googleDriveAuthManager
        )

        if (backupPrefs.autoBackupEnabled) {
            try {
                com.nexa.app.data.worker.ScheduledBackupWorker.scheduleNightlyBackup(this)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule nightly backup worker", e)
            }
        }

        // 13. Initialize Contact Discovery & Anti-Spam Handshake Subsystem
        handshakeApi = com.nexa.app.data.remote.api.HandshakeApiImpl()
        contactRepository = com.nexa.app.data.repository.ContactRepositoryImpl(
            context = this,
            contactDao = database.contactDao(),
            chatDao = database.chatDao(),
            handshakeApi = handshakeApi,
            secureStorage = secureStorage,
            keystoreManager = keystoreManager,
            doubleRatchetEngine = doubleRatchetEngine
        )
        try {
            com.nexa.app.data.remote.worker.RequestSyncWorker.schedulePeriodicSync(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule request sync worker", e)
        }

        // 14. Initialize Instant Cryptographic QR Contact Exchange & Day-0 Handshake Subsystem
        googleCodeScannerEngine = com.nexa.app.data.remote.scanner.GoogleCodeScannerEngine(this)
        qrSyncApi = com.nexa.app.data.remote.api.QrSyncApiImpl()
        qrHandshakeRepository = com.nexa.app.data.repository.QrHandshakeRepositoryImpl(
            context = this,
            contactDao = database.contactDao(),
            secureStorage = secureStorage,
            keystoreManager = keystoreManager,
            doubleRatchetEngine = doubleRatchetEngine,
            dtnRepository = dtnRepository,
            qrSyncApi = qrSyncApi,
            networkMonitor = networkMonitor
        )

        // 15. Initialize Hardware-Accelerated Video Compression & Perceptual Optimization Subsystem
        videoCompressionEngine = com.nexa.app.core.media.VideoCompressionEngine(this)
        mediaPrefs = com.nexa.app.data.local.preferences.MediaPrefs(this)
        mediaProcessingRepository = com.nexa.app.data.repository.MediaProcessingRepositoryImpl(
            context = this,
            compressionEngine = videoCompressionEngine
        )

        // 16. Initialize Dual-Gate Client-Side Malware Scanning & Isolated Quarantine Subsystem
        threatBloomFilter = com.nexa.app.core.security.ThreatBloomFilter()
        yaraNativeBridge = com.nexa.app.core.security.YaraNativeBridge()
        dualScanEngine = com.nexa.app.core.security.DualScanEngine(
            context = this,
            bloomFilter = threatBloomFilter,
            yaraScanner = yaraNativeBridge
        )
        quarantineSandboxSink = com.nexa.app.data.remote.transfer.QuarantineSandboxSink(
            context = this,
            dualScanEngine = dualScanEngine
        )
        securityScannerRepository = com.nexa.app.data.repository.SecurityScannerRepositoryImpl(
            context = this,
            dualScanEngine = dualScanEngine,
            sandboxSink = quarantineSandboxSink,
            quarantineDao = database.quarantineDao()
        )
        try {
            com.nexa.app.data.remote.security.RuleSyncWorker.scheduleWeeklySync(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule rule sync worker", e)
        }

        // 17. Initialize 50k-Scale Resilient P2P Transfer & Hardened MANET/DTN Mesh Subsystems
        memoryBufferPool = com.nexa.app.core.hardware.MemoryBufferPool()
        thermalThrottlingMonitor = com.nexa.app.core.hardware.ThermalThrottlingMonitor(this)
        chunkCipherEngine = com.nexa.app.core.crypto.ChunkCipherEngine()
        lanDiscoveryBridge = com.nexa.app.core.webrtc.LanDiscoveryBridge(this)
        robustTransferRepository = com.nexa.app.data.repository.RobustTransferRepositoryImpl(
            context = this,
            transferDao = database.transferSessionDao(),
            bufferPool = memoryBufferPool,
            thermalMonitor = thermalThrottlingMonitor,
            cipherEngine = chunkCipherEngine,
            lanDiscoveryBridge = lanDiscoveryBridge
        )

        lqiDecayEngine = com.nexa.app.core.mesh.engine.LqiDecayEngine()
        compositeMetricEngine = com.nexa.app.core.mesh.engine.CompositeMetricEngine(localPeerId = activeNxId ?: "ME")
        backpressureController = com.nexa.app.core.mesh.engine.BackpressureController()
        bloomDuplicateFilter = com.nexa.app.core.mesh.engine.BloomDuplicateFilter()
        custodyTransferManager = com.nexa.app.core.mesh.dtn.CustodyTransferManager(database.meshOutboxDao())

        com.nexa.app.data.worker.OrphanCleanupWorker.schedulePeriodicCleanup(this)
        com.nexa.app.data.worker.DtnOpportunisticDumpWorker.scheduleOpportunisticDump(this)

        // 18. Initialize Privacy-First Push Notification Subsystem
        pushManager = com.nexa.app.data.remote.push.NexaPushManager(
            context = this,
            authApiService = apiService,
            secureStorage = secureStorage
        )
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    companion object {
        private const val TAG = "NexaApplication"
    }
}
