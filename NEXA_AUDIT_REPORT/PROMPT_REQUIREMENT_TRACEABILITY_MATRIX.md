# NEXA — Prompt Requirement Traceability Matrix

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Mapping Standard:** Original Requirement -> Expected Behaviour -> Actual Implementation -> Source Code Evidence -> Status -> Compliance %.

---

| ID | Original Requirement | Expected Behaviour | Actual Implementation | Evidence File Path & Lines | Status | Compliance |
|---|---|---|---|---|:---:|:---:|
| **REQ-01** | **Local Encrypted Vault** | Encrypt device database with AES-256; zero plaintext data at rest | Room Database + SQLCipher (Version 10); 12 tables encrypted | `data/local/NexaDatabase.kt:32-108` | FULLY COMPLIED | **100%** |
| **REQ-02** | **Hardware Keystore Protection** | Protect master cryptographic keys via AndroidKeyStore StrongBox/TEE | `CryptoKeystoreManager` generates AES-256 GCM key in KeyStore | `core/security/CryptoKeystoreManager.kt:30-75` | FULLY COMPLIED | **100%** |
| **REQ-03** | **Signal Double Ratchet E2EE** | Continuous ratchet per-message forward secrecy & post-compromise security | `DoubleRatchetEngine` implemented with skipped keys; disconnected from repo | `core/crypto/DoubleRatchetEngine.kt:20-140` | PARTIALLY COMPLIED | **60%** |
| **REQ-04** | **Zero-Cloud-Storage Relay** | Cloud server relays messages via RAM; zero chats stored on disk | Go WebSocket server with in-memory routing and ephemeral Redis 24h TTL | `backend/internal/connection/hub.go:70-135` | FULLY COMPLIED | **100%** |
| **REQ-05** | **Port 8901 Binary Data SMS** | SMS fallback invisible to Google Messages / default SMS apps | `SmsManager.sendDataMessage` on port 8901; `NexaDataSmsReceiver` | `data/remote/sms/NexaDataSmsSender.kt:35-85` | FULLY COMPLIED | **95%** |
| **REQ-06** | **WebRTC P2P DataChannels** | Direct file streaming without intermediate cloud storage | Stream WebRTC SCTP DataChannels; SDP & ICE relaying | `core/webrtc/ResilientDataStreamer.kt:40-120` | FULLY COMPLIED | **98%** |
| **REQ-07** | **Sparse Bitfield Resumption** | Mid-transfer drops resume without re-downloading received chunks | 64KB sparse `.nxdl` files + thread-safe `BitSet` bitfield checkpoints | `core/webrtc/BitfieldTracker.kt:20-60` | FULLY COMPLIED | **100%** |
| **REQ-08** | **Zero-Garbage Memory Pool** | Prevent GC pauses during multi-gigabyte transfers | `MemoryBufferPool` bounded queue of 16 pre-allocated 64KB byte arrays | `core/hardware/MemoryBufferPool.kt:15-40` | FULLY COMPLIED | **100%** |
| **REQ-09** | **Thermal Throttling Backoff** | Throttle transfer rate on device thermal alerts | `PowerManager.OnThermalStatusChangedListener` triggers 50ms delays | `core/hardware/ThermalThrottlingMonitor.kt:30-55` | FULLY COMPLIED | **100%** |
| **REQ-10** | **LAN Auto-Discovery** | Discover peers on same Wi-Fi subnet at 30–50 MB/s | Android `NsdManager` mDNS registration and service resolution | `core/webrtc/LanDiscoveryBridge.kt:30-85` | FULLY COMPLIED | **95%** |
| **REQ-11** | **Swarm Cross-Seeding** | Group transfers distribute chunks round-robin across peers | `SwarmCrossSeeder` computes peer chunk ownership matrices | `core/swarm/SwarmCrossSeeder.kt:15-45` | FULLY COMPLIED | **95%** |
| **REQ-12** | **TURN Circuit Breaker** | Rate-limit relay throughput to prevent cloud bandwidth abuse | Go backend limits TURN transfers to 1.5 MB/s; rejects files > 100 MB | `backend/internal/signaling/circuit_breaker.go:15-60`| FULLY COMPLIED | **100%** |
| **REQ-13** | **7-Day Orphan `.nxdl` Cleanup**| Daily WorkManager job purges stale incomplete transfer files | `OrphanCleanupWorker` scans cache dir for `.nxdl` inactive > 7 days | `data/worker/OrphanCleanupWorker.kt:20-55` | FULLY COMPLIED | **100%** |
| **REQ-14** | **3-State LQI Decay Engine** | EMA smoothed RSSI with FER packet loss overrides | EMA smoothing ($\alpha=0.3$); FER > 0.40 flags `LinkState.UNSTABLE` | `core/mesh/engine/LqiDecayEngine.kt:25-55` | FULLY COMPLIED | **100%** |
| **REQ-15** | **MANET Composite Cost Metric**| Factor medium weights and battery degradation curves | BLE cost 4.0, Wi-Fi 1.0; battery < 5% disables relay participation | `core/mesh/engine/CompositeMetricEngine.kt:20-60` | FULLY COMPLIED | **100%** |
| **REQ-16** | **25% Hysteresis & Dwell Lock**| Prevent high-frequency route flapping in mobile mesh | Requires $\text{Cost} < 0.75 \times \text{Cost}_{\text{current}}$ and 3-second dwell lock expiry | `core/mesh/engine/CompositeMetricEngine.kt:62-85` | FULLY COMPLIED | **100%** |
| **REQ-17** | **Leaky-Bucket Credit Backpressure**| Throttle mesh throughput and cut off relaying at high buffer | Leaky bucket rate limiter; cuts off transit relay when buffer > 80% | `core/mesh/engine/BackpressureController.kt:20-55`| FULLY COMPLIED | **100%** |
| **REQ-18** | **Slotted-Aloha Broadcast Throttle**| Mitigate Route Request (RREQ) broadcast storms | 30–200ms randomized delay; neighbor overhear cancels transmission | `core/mesh/engine/AlohaBroadcastThrottle.kt:20-45`| FULLY COMPLIED | **100%** |
| **REQ-19** | **Merkle Cluster Gossip Sync** | Synchronize partition clusters on merge using delta gossip | 8-byte Merkle tree root fingerprint comparison | `core/mesh/engine/MerkleClusterSync.kt:20-55` | FULLY COMPLIED | **100%** |
| **REQ-20** | **DTN Custody Transfer & Dump** | Store envelopes in outbox until ACKed; dump to cloud on Wi-Fi | `CustodyTransferManager` holds envelopes; `DtnOpportunisticDumpWorker` | `core/mesh/dtn/CustodyTransferManager.kt:20-60` | FULLY COMPLIED | **95%** |
| **REQ-21** | **Strict 7-Hop Limit Ceiling** | Prevent infinite packet circulation in dense mesh | Drops any frame where `hopCount >= 7` | `core/mesh/engine/CompositeMetricEngine.kt:88-95` | FULLY COMPLIED | **100%** |
| **REQ-22** | **Dual-Gate Malware Defense** | Pre-dispatch and pre-save malware inspection pipeline | Magic byte inspection + 1.2MB Bloom Filter + YARA native bridge | `core/security/DualScanEngine.kt:35-110` | FULLY COMPLIED | **98%** |
| **REQ-23** | **Native YARA Scanner (JNI)** | Compile YARA signatures to native C++ bytecode | C++ JNI bridge executes pattern matching against file descriptors | `app/src/main/cpp/yara_bridge.cpp:15-80` | FULLY COMPLIED | **95%** |
| **REQ-24** | **Hardware Video Compression** | Transcode video to H.265/HEVC 720p 30fps before sending | AndroidX Media3 Transformer executes hardware-accelerated transcode | `core/media/VideoCompressionEngine.kt:30-80` | FULLY COMPLIED | **96%** |
| **REQ-25** | **Hidden Drive Vault Backup** | Zero-lock snapshot pushed to Google Drive `appDataFolder` | `VACUUM INTO` snapshot, BIP-39 seed, Argon2id stretch, XChaCha20 | `data/drive/GoogleDriveBackupRepositoryImpl.kt` | FULLY COMPLIED | **95%** |
| **REQ-26** | **Day-0 Instant QR Exchange** | Swap identity keys and peer IDs via offline QR scan | ZXing matrix generator + Google Code Scanner + Ed25519 signature | `presentation/qr/QrViewModel.kt:30-90` | FULLY COMPLIED | **94%** |
| **REQ-27** | **Client-Side Micro-PoW** | Hashcash micro-PoW on friend requests to prevent spam | `ProofOfWorkEngine.solve` computes 16 leading zero bits (~300ms) | `core/crypto/ProofOfWorkEngine.kt:15-50` | FULLY COMPLIED | **100%** |
| **REQ-28** | **NEXA ID Generation** | Deterministic derivation from Ed25519 public key hash | In-memory `SecureRandom` allocation (`NX-XXXX-XXX`) | `data/remote/api/AuthApiService.kt:56-65` | INCORRECTLY IMPLEMENTED | **40%** |
| **REQ-29** | **Phone OTP Verification** | SIM ownership verification via SMS Gateway | In-memory simulated delay; accepts any 6-digit numeric OTP | `data/remote/api/AuthApiService.kt:47-55` | PARTIALLY COMPLIED | **40%** |
| **REQ-30** | **FCM Push Notifications** | Wake up sleeping devices on new incoming message | Log-only stub in Go backend (`[FCM] Dispatching...`); no Android SDK | `backend/internal/connection/hub.go:206` | NOT IMPLEMENTED | **10%** |
