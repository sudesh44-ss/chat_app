# NEXA — Master System Architecture Blueprint
## 01. Complete Technology Stack, Frameworks & Protocols

---

### 1. Architectural Stack Inventory

This document provides a comprehensive, component-level inventory of every technology, programming language, framework, external library, protocol, and runtime component utilized across the NEXA Android client, Go relay backend, and supporting infrastructure.

Every entry identifies:
1. **Technology & Version:** The exact release or commit used.
2. **Where Used:** The source files, Gradle targets, or Go packages where the component is integrated.
3. **Why Used (Technical Rationale):** The specific architectural requirement that necessitated its selection over alternatives.

---

### 2. Android Client Technology Stack

```
+---------------------------------------------------------------------------------------------------+
|                               ANDROID CLIENT CORE RUNTIME ENVIRONMENT                             |
|  - Language: Kotlin 2.2.10 | Kotlin JVM Target: 17 | Compile SDK: 37 | Target SDK: 35 | Min SDK: 24   |
|  - Build System: Gradle 9.4.1 (AGP 9.4.1) | Kotlin Symbol Processing (KSP 2.2.10-2.0.2)           |
|  - Desugaring: desugar_jdk_libs 2.1.4 (Java 8+ API support down to Android API 24)                |
+---------------------------------------------------------------------------------------------------+
```

#### A. UI & Presentation Framework

| Technology | Version / Group | Location in Codebase | Technical Rationale & Architectural Purpose |
|---|---|---|---|
| **Jetpack Compose BOM** | `2026.02.01` (`androidx.compose`) | `app/build.gradle.kts:83`, `presentation/` | Modern reactive declarative UI toolkit. Eliminates XML view bloat, enables fine-grained recomposition, and powers custom "Liquid Glass" frosted-glass shaders. |
| **Compose Material 3** | BOM `2026.02.01` (`material3`) | `presentation/theme/`, all UI screens | Dynamic theming, Material You palette generation, accessible typography, elevation shadows, and standard Material components. |
| **Material Icons Extended** | BOM `2026.02.01` (`material-icons-extended`) | `presentation/` screens | Comprehensive vector icon set for chat statuses, delivery double-ticks, mesh indicators, transport selectors, and security badges. |
| **Navigation Compose** | `2.8.8` (`navigation-compose`) | `presentation/navigation/`, `MainActivity.kt` | Single-Activity navigation architecture. Enforces type-safe destination graphs and manages backstack restoration across process death. |
| **Activity Compose** | `1.13.0` (`activity-compose`) | `MainActivity.kt:35` | Bridges Jetpack Compose directly into `ComponentActivity`, managing back press dispatching and window insets edge-to-edge. |
| **Lifecycle ViewModel Compose** | `2.11.0` (`lifecycle-viewmodel-compose`) | `presentation/**/ViewModel.kt` | Scopes ViewModels to Compose navigation graph destinations; survives configuration changes and binds UI to coroutine `StateFlow`. |
| **Coil Compose** | `2.7.0` (`io.coil-kt:coil-compose`) | `presentation/components/`, `ChatScreen.kt` | Memory-efficient asynchronous image loading pipeline. Directly renders user avatars, contact pictures, and media thumbnails without UI stutter. |

#### B. Cryptography & Security Libraries

| Technology | Version / Group | Location in Codebase | Technical Rationale & Architectural Purpose |
|---|---|---|---|
| **Android Keystore System** | Native Android Hardware API | `core/keystore/HardwareKeystoreVault.kt`, `CryptoKeystoreManager.kt` | Hardware-backed Keymaster / StrongBox key generation. Ensures private keys and the SQLCipher database master key can never be extracted from RAM or disk. |
| **Google Tink Android** | `1.15.0` (`com.google.crypto.tink`) | `core/crypto/ChannelEncryptor.kt`, `DualScanEngine.kt` | Audited, misuse-resistant cryptographic library. Used for symmetric authenticated encryption (AES-256-GCM) and tamper-proof MAC evaluations. |
| **BouncyCastle Provider** | `1.80` (`org.bouncycastle:bcprov-jdk18on`) | `core/crypto/Ed25519Signer.kt`, `DoubleRatchetEngine.kt` | High-performance implementations of modern elliptic curve cryptography: Ed25519 digital signatures and X25519 Diffie-Hellman key exchange. |
| **AndroidX Security Crypto** | `1.10.0-alpha06` (`security-crypto`) | `core/security/SecureStorage.kt` | Provides `EncryptedSharedPreferences` backed by MasterKeys in the Android Keystore for secure storage of session tokens and seeds. |
| **Zetetic SQLCipher Android** | `4.6.1` (`net.zetetic:sqlcipher-android`) | `data/local/NexaDatabase.kt:62`, `app/build.gradle.kts:114` | Complete on-disk transparent 256-bit AES page encryption for SQLite. Guarantees zero plaintext exposure if the physical device is rooted or dumped. 16 KB page-aligned. |
| **Native YARA JNI Bridge** | Custom Native C++ / JNI | `core/security/YaraNativeBridge.kt`, `DualScanEngine.kt` | Client-side rule-based pattern matching engine for incoming files, flagging malicious signatures before saving to local storage. |

#### C. Local Persistence & Database

| Technology | Version / Group | Location in Codebase | Technical Rationale & Architectural Purpose |
|---|---|---|---|
| **AndroidX Room Runtime & KTX** | `2.6.1` (`androidx.room`) | `data/local/NexaDatabase.kt`, `data/local/dao/` | Type-safe compile-time SQLite abstraction layer. Automates schema verification, coroutine Flow queries, and explicit migration sequences (v1 to v28). |
| **AndroidX SQLite KTX** | `2.4.0` (`androidx.sqlite:sqlite-ktx`) | `data/local/NexaDatabase.kt:127` | Provides direct low-level `SupportSQLiteDatabase` handles for performing zero-lock WAL snapshots (`VACUUM INTO`) during backup bundling. |
| **Room Compiler (KSP)** | `2.6.1` (`androidx.room:room-compiler`) | `app/build.gradle.kts:113` | Generates Kotlin DAO implementations and schema validators during compilation via Kotlin Symbol Processing. |

#### D. Networking & Transports

| Technology | Version / Group | Location in Codebase | Technical Rationale & Architectural Purpose |
|---|---|---|---|
| **Square OkHttp** | `4.12.0` (`com.squareup.okhttp3`) | `core/network/WebSocketManager.kt`, `data/remote/api/` | Robust HTTP/2 and WebSocket transport client. Manages connection pooling, TLS 1.3 socket negotiation, ping/pong keepalives, and automatic reconnection. |
| **Stream WebRTC Android** | `1.3.10` (`io.getstream:stream-webrtc-android`) | `core/webrtc/WebRtcClient.kt`, `P2PTransferRepositoryImpl.kt` | Direct peer-to-peer audio, video, and binary DataChannel transport. Handles NAT traversal via STUN/TURN, ICE candidate negotiation, and SCTP streaming. |
| **Android Telephony SmsManager** | Android SDK System Service | `data/remote/sms/NexaDataSmsSender.kt`, `SmsComposerLauncher.kt` | Sends raw multi-part binary data over cellular SMS (port 8901) and generates compliant intent intents for Module 3 encrypted SMS fallback. |
| **Bluetooth Low Energy (BLE)** | Android Bluetooth API | `core/mesh/transport/BleTransport.kt` | Provides L2CAP Connection-Oriented Channels (CoC) for ultra-low-power peer-to-peer transmission of packets <= 64 KB in off-grid environments. |
| **Wi-Fi Aware (NAN)** | Android Wi-Fi Aware System Service | `core/mesh/transport/WifiAwareTransport.kt` | High-bandwidth neighbor-discovery and socket establishment without an existing Wi-Fi access point or internet connection. |
| **AndroidX WorkManager** | `2.10.0` (`androidx.work:work-runtime-ktx`) | `core/dtn/DtnSyncWorker.kt`, `data/drive/BackupWorker.kt` | Guaranteed background execution for opportunistic DTN data-mule synchronization and scheduled Google Drive cloud backups. |

#### E. Hardware Acceleration, Media & System Integrations

| Technology | Version / Group | Location in Codebase | Technical Rationale & Architectural Purpose |
|---|---|---|---|
| **AndroidX Media3 Transformer** | `1.3.1` (`androidx.media3:media3-transformer`) | `core/media/VideoCompressionEngine.kt` | Hardware-accelerated video transcoding engine. Enforces high-efficiency HEVC (H.265) compression capped at 720p 30fps to minimize payload bytes. |
| **AndroidX Media3 Effect & Common** | `1.3.1` (`androidx.media3`) | `core/media/VideoCompressionEngine.kt` | Provides presentation time stamp alignment and frame cropping effects for video attachments. |
| **AndroidX ExifInterface** | `1.3.7` (`androidx.exifinterface`) | `core/media/ExifStripper.kt`, `MediaSanitizer.kt` | Inspects and strips privacy-sensitive GPS coordinates, camera serial numbers, and EXIF metadata from shared images before transmission. |
| **Google Play Code Scanner** | `16.1.0` (`play-services-code-scanner`) | `data/remote/scanner/GoogleCodeScannerEngine.kt` | Zero-permission on-device barcode/QR scanner powered by Google Play services. Used for Day-0 out-of-band cryptographic handshakes. |
| **ZXing Core** | `3.5.3` (`com.google.zxing:core`) | `presentation/qr/QrGenerator.kt`, `QrScannerScreen.kt` | Generates high-density QR bitmaps representing Ed25519 identity keys and contact verification tokens; serves as scanner fallback on de-Googled ROMs. |
| **AndroidX CameraX** | `1.4.1` (`androidx.camera`) | `presentation/qr/CameraXScannerFallback.kt` | Complete camera preview and frame analysis pipeline fallback for devices lacking Google Play Services. |
| **AndroidX Credentials API** | `1.3.0` (`androidx.credentials`) | `core/auth/GoogleAuthManager.kt` | Modern unified authentication interface supporting Passkeys and Google ID token sign-in without legacy Play Services dependencies. |
| **Play Services Auth & Phone** | `21.3.0` / `18.1.0` | `core/auth/FirebasePhoneAuthManager.kt`, `SmsRetrieverHelper.kt` | Automated SMS verification reading via SMS Retriever API (zero SMS reading permission required). |

---

### 3. Backend Technology Stack (Go Microservices)

```
+---------------------------------------------------------------------------------------------------+
|                                 GO BACKEND RUNTIME ENVIRONMENT                                    |
|  - Language: Go 1.25.0 (64-bit) | Compilation: CGO_ENABLED=0 static ELF binary                      |
|  - Containerization: Docker (multi-stage alpine build) | Orchestration: Docker Compose            |
|  - Microservice Architecture: Modular Monolith (`server`) + 6 Auxiliary Sidecar Daemons            |
+---------------------------------------------------------------------------------------------------+
```

#### A. Core Backend Libraries & Frameworks

| Technology | Version / Module | Location in Backend | Technical Rationale & Architectural Purpose |
|---|---|---|---|
| **Gorilla WebSocket** | `v1.5.3` (`github.com/gorilla/websocket`) | `internal/connection/client.go`, `cmd/server/main.go:31` | High-throughput, RFC 6455-compliant WebSocket implementation in Go. Handles concurrent read/write pumps with 64 KB buffers and non-blocking channels. |
| **pgx/v5 (PostgreSQL Driver)** | `v5.12.0` (`github.com/jackc/pgx/v5`) | `internal/database/db.go`, `cmd/server/main.go:17` | High-performance native Go driver and connection pool (`pgxpool`) for PostgreSQL. Bypasses standard `database/sql` reflection overhead. |
| **go-redis/v9** | `v9.6.1` (`github.com/redis/go-redis/v9`) | `internal/queue/redis_buffer.go`, `internal/connection/pubsub.go` | Thread-safe Redis client supporting connection pooling, pipelining, and Pub/Sub cluster distribution across horizontally scaled server nodes. |
| **Standard Library `crypto/ed25519`** | Go 1.25 Standard Library | `internal/connection/client.go:145`, `internal/auth/handler.go` | Cryptographic verification of client authentication challenge responses. Constant-time signature checking prevents timing side-channels. |
| **Standard Library `crypto/cipher` & `sha256`** | Go 1.25 Standard Library | `internal/identity/phonecrypto.go` | Implements deterministic HMAC-SHA256 hashing for phone number matching and AES-256-GCM encryption for phone records in PostgreSQL. |

#### B. Backend Subsystems & Daemon Roles

```
                                      GO BACKEND DAEMONS
  [ Port 8080: nexa-relay ]           -> Core WebSocket Session Hub, Auth APIs, Prekey Directory
  [ Port 8081: nexa-swarm ]           -> P2P Swarm Coordinator (Multi-peer chunk distribution)
  [ Port 8082: nexa-blind-relay ]     -> Opportunistic DTN Cloud Dump Ingestion (/api/v1/relay/...)
  [ Port 8083: nexa-signaling ]       -> WebRTC SDP Offer/Answer & ICE Candidate Exchange
  [ Port 8084: nexa-handshake ]       -> Prekey Directory & Hashcash Proof-of-Work Verifier
  [ Port 8085: nexa-qr-sync ]         -> Day-0 Out-of-Band Cryptographic Sync Endpoint
  [ Port 8086: nexa-security-rules ]  -> OTA YARA Rule & Threat Hash Bundle Distribution
```

---

### 4. Wire Protocols & Serialization

#### A. Protocol Buffers (Protobuf v3)
- **Schema Definition:** `proto/nexa_wire.proto`
- **Generated Code:** Go (`nexa/wire`) and Kotlin (`com.nexa.app.core.proto`)
- **Packet Envelope:** `NexaWirePacket` encapsulates 18 distinct packet types:
  - `PACKET_MESSAGE` (1) — Encrypted Double Ratchet payload.
  - `PACKET_ACK` (2) — End-to-end delivery / read receipts.
  - `PACKET_PING` (3) — Socket keepalive and latency measurement.
  - `PACKET_AUTH_CHALLENGE` (4) & `PACKET_AUTH_RESPONSE` (5) — Ed25519 challenge-response handshake.
  - `PACKET_AUTH_SUCCESS` (6) & `PACKET_AUTH_FAILURE` (7) — Authentication status tokens.
  - `PACKET_TYPING` (10) — Ephemeral typing activity indicator.
  - `PACKET_DEVICE_SYNC` (11) — Multi-device state replication.
  - `PACKET_SIGNALING` (12) — WebRTC transfer coordination.
  - `PACKET_REGISTER_ROUTING_TOKENS` (13) — Blind rotating pairwise tokens.
  - `PACKET_PEER_SYNC` (14) — Delta sync inventory negotiation.
  - `PACKET_CUSTODY_ACK` (15) — Delay-tolerant custody transfer acknowledgment.
  - `PACKET_PRESENCE` (16) — Online/offline availability status.
  - `PACKET_MESSAGE_EDIT` (17) & `PACKET_MESSAGE_DELETE` (18) — Message mutation payloads.

#### B. Compact Base91 Binary Encoding
- **Implementation:** `core/crypto/Base91Codec.kt`
- **Application:** Encrypted SMS Fallback transport (Module 3).
- **Technical Advantage:** Base91 achieves 1.23x expansion compared to Base64's 1.33x expansion, saving 10% of critical SMS character payload space when fitting encrypted Double Ratchet frames into 140-byte GSM segments.

---

### 5. Cloud Infrastructure & Third-Party Services

| External Service | Role in NEXA System | Integration Method | Security Boundary & Cost |
|---|---|---|---|
| **PostgreSQL 15+** | Identity registry, prekeys, group metadata | Native `pgxpool` TLS connection | Managed database. Enforces Row-Level Security (RLS). Zero plaintext chats stored. |
| **Redis 7.x** | Ephemeral RAM buffer, Pub/Sub clustering | `go-redis` TCP connection | Ephemeral RAM only. 24h key expiration. Zero disk persistence. |
| **Google Drive API** | Encrypted user backup archives | OAuth 2.0 REST to `appDataFolder` | Hidden from user Drive UI. Backups encrypted with BIP-39 derived master key before upload. |
| **Firebase Cloud Messaging (FCM)** | Background wake-up pings | FCM HTTP v1 API (`fcm.FcmService`) | High-priority data-only wake-up pings. Contains zero user message content or sender identities. Cost: ₹0. |
| **Cloudflare R2** | Ephemeral fallback media relay | S3-Compatible Pre-signed URLs | 7-day auto-purge TTL. Files uploaded as encrypted blobs. Purged immediately upon ACK. |
| **MSG91 / Twilio SMS** | Cellular SMS OTP delivery | REST API (`auth.OtpService`) | Used only for initial phone number verification. In-flight OTPs hashed with HMAC-SHA256 pepper. |
| **Truecaller SDK** | One-tap phone verification | Android SDK (`TruecallerAuthManager.kt`) | User-consented instant profile verification without manual OTP entry. |
