# NEXA — Master System Architecture Blueprint
## 02. Android Client Architecture & Core Engines

---

### 1. Architectural Model & Package Organization

The NEXA Android client is engineered strictly under **Clean Architecture** and **Unidirectional Data Flow (UDF)** principles. The codebase cleanly separates presentation, business logic (domain), data persistence (data), and hardware/cryptographic engines (core).

```
app/src/main/java/com/nexa/app/
│
├── core/                       # Independent Security, Cryptography, Media & Network Engines
│   ├── auth/                   # Truecaller, Firebase Phone OTP & Credential Manager bridges
│   ├── backup/                 # BIP-39 mnemonic derivation, streaming AES-GCM vault packager
│   ├── crypto/                 # Double Ratchet, X3DH, ChaCha20-Poly1305, Ed25519, Base91
│   ├── dtn/                    # Delay-Tolerant Networking, pairwise routing tokens, bloom filters
│   ├── hardware/               # Direct memory buffer pools (64KB), thermal throttling monitors
│   ├── keystore/               # AndroidKeyStore hardware-backed AES-256 master key management
│   ├── media/                  # Media3 Transformer HEVC transcoding, EXIF metadata sanitization
│   ├── mesh/                   # MANET mesh engines: LQI decay, composite routing, Aloha throttle
│   ├── network/                # WebSocket client, OkHttp connection monitor, network callbacks
│   ├── notification/           # Notification channels, priority dispatching, privacy banners
│   ├── proto/                  # Compiled Protobuf wire protocol message structures
│   ├── security/               # Dual-Gate malware scanner (Magic Bytes + Bloom Filter + YARA)
│   ├── sms/                    # Module 3 encrypted SMS fallback (NX1 protocol, multipart assembler)
│   ├── sound/                  # Custom audio player, sound settings, haptic engine
│   ├── transport/              # Universal multi-transport ingestion pipeline & medium abstraction
│   └── webrtc/                 # Stream WebRTC P2P DataChannels, LAN mDNS discovery bridge
│
├── data/                       # Data Persistence, Network Gateways & Repository Implementations
│   ├── drive/                  # Google Drive REST client, resumable uploader, auth coordinator
│   ├── local/                  # Room Database (v28), 27 SQLCipher entities, DAOs, Preferences
│   ├── remote/                 # Remote API services, push handling, signaling clients, swarm coordinator
│   ├── repository/             # Concrete implementations of Domain Repository interfaces
│   └── transport/              # Transport implementations (Internet, SMS, Mesh) and TransportRouter
│
├── domain/                     # Pure Business Logic, Models, Interfaces & Managers
│   ├── manager/                # Scheduled message manager, conversation coordinator
│   ├── model/                  # Domain-level entities (Message, ChatConversation, UserProfile, Transfer)
│   └── repository/             # Abstract Repository contracts defining system boundaries
│
└── presentation/               # Jetpack Compose UI, ViewModels, Liquid Glass & Navigation
    ├── chat/                   # 1-to-1 and group chat screens, media preview, voice recorder
    ├── contacts/               # Contact list, pending requests, user discovery, nickname editor
    ├── dashboard/              # Main tabs, conversation list, floating action buttons, status pills
    ├── navigation/             # Navigation graphs, type-safe routes, deep-link handlers
    ├── qr/                     # QR display and scanner for Day-0 cryptographic verification
    ├── security/               # Biometric app lock gate, PIN keypad, auto-lock lifecycle timer
    ├── settings/               # Account, Appearance, Sound, Notifications, Privacy, Storage screens
    ├── setup/                  # First-time profile setup, avatar selection, bio input
    ├── splash/                 # Startup splash, database initialization, session restoration
    ├── status/                 # Ephemeral 24h stories, sequential story viewer, viewer bottom sheet
    ├── theme/                  # Liquid Glass theme, dark/light color schemes, typography, shapes
    ├── transfer/               # Live P2P file transfer dashboard, thermal badges, progress cards
    ├── verification/           # Phone verification selection, SMS OTP, reverse SMS screens
    └── welcome/                # Welcome screen, returning user login, passkey prompt
```

---

### 2. Presentation Layer Architecture

#### A. Unidirectional Data Flow (UDF) & MVI Pattern
NEXA presentation components enforce strict Unidirectional Data Flow:
1. **User Action / Intent:** The UI emits high-level user actions (e.g., `SendMessage`, `AcceptTransfer`, `ToggleMute`) to the ViewModel.
2. **ViewModel Processing:** The ViewModel processes the intent inside `viewModelScope`, interacting exclusively with Domain Repositories.
3. **State Mutation:** Repository updates emit immutable data classes via Kotlin `StateFlow`.
4. **Recomposition:** Jetpack Compose composables observe the `StateFlow` as State, triggering atomic, surgical UI recompositions.

```
       +---------------------------------------------------------+
       |                 Jetpack Compose Screen                  |
       |  (e.g., ChatScreen, DashboardScreen, TransferDashboard) |
       +---------------------------------------------------------+
                |                                        ^
       User Action (Event)                     Observed StateFlow (State)
                v                                        |
       +---------------------------------------------------------+
       |                   AndroidX ViewModel                    |
       |    (e.g., ChatViewModel, SetupViewModel, OtpViewModel)  |
       +---------------------------------------------------------+
                |                                        ^
       Executes Coroutine                      Collects Reactive Flows
                v                                        |
       +---------------------------------------------------------+
       |              Domain Repositories & Engines              |
       | (ChatRepository, DoubleRatchetEngine, P2PTransferRepo)  |
       +---------------------------------------------------------+
```

#### B. Liquid Glass Design Language
The UI design language of NEXA combines Material 3 with a proprietary **Liquid Glass** aesthetic:
- **Frosted Translucency:** Surface layers utilize custom Compose blur modifiers (`Modifier.blur()`) with semi-transparent tinted backgrounds (`Color.White.copy(alpha = 0.08f)`).
- **Subtle Specular Borders:** Cards and dialogs feature ultra-thin 0.5 dp gradient borders simulating edge reflections.
- **Micro-Interactions & Haptics:** Touch targets trigger immediate feedback via `NexaSoundManager` and haptic vibrators.

---

### 3. Core Cryptographic & Security Engines

#### A. DoubleRatchetEngine (`core/crypto/DoubleRatchetEngine.kt`)
Implements the Signal-compliant **Double Ratchet Protocol**, providing continuous Forward Secrecy (FS) and Post-Compromise Security (PCS):
- **DH Ratchet:** X25519 elliptic curve Diffie-Hellman step executed on every conversation turn.
- **Symmetric KDF Chains:** Two separate symmetric ratchet chains (Sending Chain and Receiving Chain) driven by HKDF-SHA256.
- **AEAD Encryption:** Every message is encrypted using `ChaCha20-Poly1305` with a 12-byte deterministic AEAD nonce and a 16-byte Poly1305 authentication tag.
- **Out-of-Order Recovery:** Skipped message keys are safely stored in the encrypted `skipped_keys` table with a bounded retention limit (1000 keys) to handle delayed or out-of-order packets.

#### B. GroupSenderKeyEngine (`core/crypto/GroupSenderKeyEngine.kt`)
Implements **Sender Keys** for efficient multi-party group messaging:
- **Tree-KDF Chains:** Avoids O(N^2) pairwise encryption overhead. Each group member maintains an independent Sender Key chain.
- **Pairwise Key Distribution:** Sender Key distribution messages are encapsulated and delivered to each group participant using standard 1-to-1 Double Ratchet channels.
- **Forward Secrecy:** Each sent group message advances the sender's ratchet chain, discarding prior message keys.

#### C. X3dhHandshakeEngine (`core/crypto/X3dhHandshakeEngine.kt`)
Coordinates the Extended Triple Diffie-Hellman (X3DH) asynchronous cryptographic handshake:
- Authenticates the initiator and recipient using long-term Ed25519 identity keys.
- Combines four Diffie-Hellman operations: $DH1 = DH(IK_A, SPK_B)$, $DH2 = DH(EK_A, IK_B)$, $DH3 = DH(EK_A, SPK_B)$, and $DH4 = DH(EK_A, OPK_B)$.
- Derives the master shared secret ($SK$) using HKDF-SHA256 to seed the initial Double Ratchet Root Key.

#### D. CryptoKeystoreManager & HardwareKeystoreVault
- **Location:** `core/keystore/HardwareKeystoreVault.kt` & `core/security/CryptoKeystoreManager.kt`
- **Master Key Security:** Generates a 256-bit AES master key inside the hardware-backed `AndroidKeyStore` provider (`KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT`).
- **TEE / StrongBox Enforcement:** Flags `setIsStrongBoxBacked(true)` where hardware hardware security modules (HSM) exist.
- **Zero In-Memory Retention:** Private keys decrypted for cryptographic operations are zeroized (`Arrays.fill(rawBytes, 0.toByte())`) immediately following execution.

#### E. DualScanEngine & Client-Side Malware Defense
- **Location:** `core/security/DualScanEngine.kt`
- **Gate 1 (Magic Byte Inspector):** Inspects the initial 16 bytes of any received file stream. Detects extension spoofing (e.g., Windows PE `.exe` disguised as `.pdf` or `.png`).
- **Gate 2 (Threat Bloom Filter):** Queries a 1.2 MB in-memory Bloom filter loaded with SHA-256 hashes of known malicious payloads.
- **Native YARA Bridge (`YaraNativeBridge.kt`):** Executes compiled native YARA rules via JNI against received file buffers. Any suspicious payload is immediately diverted into `QuarantineSandboxSink` and locked in the `quarantine_vault` database table.

---

### 4. Networking, Transport & Media Engines

#### A. Universal Multi-Transport Ingestion (`core/transport/NexaUniversalTransportIngest.kt`)
Acts as the central single point of ingestion for all incoming wire packets regardless of the underlying physical transport medium:
- Ingests packets from `INTERNET_WS`, `CELLULAR_DATA_SMS`, and `OFFLINE_MESH`.
- Executes packet deduplication via `MessageDedupDao`.
- Performs Double Ratchet AEAD decryption and verifies MAC integrity tags.
- Dispatches delivery and read receipts back across the originating transport medium.
- Pushes decrypted records into `MessageDao` and triggers UI notifications via `NexaNotificationDispatcher`.

#### B. TransportRouter (`data/transport/TransportRouter.kt`)
Evaluates network availability, peer reachability, and user privacy preferences to select the optimal transmission medium:
1. If internet connectivity is detected and peer WebSocket is active $\rightarrow$ Route via `InternetTransport`.
2. If internet is unavailable but recipient phone number is verified $\rightarrow$ Route via `SmsTransport` (Module 3 NX1 protocol).
3. If cellular is absent or local mesh nodes are detected $\rightarrow$ Route via `MeshTransport` (BLE L2CAP / Wi-Fi Aware).

#### C. VideoCompressionEngine (`core/media/VideoCompressionEngine.kt`)
- Utilizes `androidx.media3.transformer.Transformer` backed by hardware video encoders (`MediaCodec`).
- Transcodes incoming video attachments into HEVC (H.265) format, capping resolution at 720p (1280x720) at 30 frames per second.
- Reduces video transmission payload by up to 75% compared to raw camera recordings, ensuring swift P2P delivery.

#### D. P2P Direct Transfer & Swarm Coordinator
- **Stream WebRTC Client (`core/webrtc/WebRtcClient.kt`):** Manages `PeerConnection` lifecycles, STUN/TURN server configurations, and SCTP DataChannels.
- **ChunkCipherEngine (`core/crypto/ChunkCipherEngine.kt`):** Splits files into 64 KB binary chunks, encrypting each chunk with an independent ChaCha20-Poly1305 envelope.
- **MemoryBufferPool (`core/hardware/MemoryBufferPool.kt`):** Leases pre-allocated 64 KB byte arrays from a bounded 16-slot pool to eliminate Android garbage collection pauses during high-speed transfers.
- **ThermalThrottlingMonitor (`core/hardware/ThermalThrottlingMonitor.kt`):** Listens to `PowerManager.OnThermalStatusChangedListener`. Throttles transfer chunk rate if device temperature exceeds `THERMAL_STATUS_MODERATE` to prevent device shutdown.

---

### 5. Service Locator & Dependency Container (`NexaApplication.kt`)

Rather than relying on heavy reflection-based dependency injection frameworks (such as Dagger or Hilt) that bloat APK binary size and increase cold-startup latency, NEXA uses a high-performance, strictly typed **Application Service Locator** pattern implemented in `NexaApplication.kt`:

1. **Deterministic Cold Startup Sequence:**
   - Step 1: Initialize `SecureStorage` (EncryptedSharedPreferences) and `CryptoKeystoreManager`.
   - Step 2: Unlock `nexa_encrypted_vault.db` using the Keystore-protected passphrase via Zetetic SQLCipher `SupportOpenHelperFactory`.
   - Step 3: Instantiate core DAOs and local preferences.
   - Step 4: Initialize `DoubleRatchetEngine` and bind it to the local `RatchetDao`.
   - Step 5: Start `WebSocketManager` with Ed25519 cryptographic challenge signing callback.
   - Step 6: Initialize `NexaUniversalTransportIngest` and link it to all incoming transport adapters.
   - Step 7: Initialize background services (`WorkManager`, notification channels, sound managers).
2. **Global Singletons:** All repositories, crypto engines, hardware monitors, and transport coordinators are exposed as read-only properties with private setters, guaranteeing thread-safe single-instance access across all ViewModels.
