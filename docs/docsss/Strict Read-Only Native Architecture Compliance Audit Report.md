# NEXA — Strict Read-Only Native Architecture Compliance Audit Report

**Date of Inspection:** November 6, 2026  
**Target Repository:** `H:\chat_app`  
**Execution Mode:** Absolute Read-Only (Non-Mutating Inspection)  

---

## 1. Executive Summary

A comprehensive, evidence-based architectural compliance inspection of the NEXA Android Native Application and Go Backend Infrastructure was conducted. The codebase was evaluated against the specified native architecture, security design, cryptographic primitives, and deployment requirements.

### Key Inspection Findings:
1. **Native Android Application Status:**
   - **Language & Framework:** Built natively using **Kotlin 2.2.10** with **Jetpack Compose (BOM 2026.02.01)** and Material3. There is no web frontend, browser shell, or hybrid framework (no Cordova, Capacitor, React Native, or WebView).
   - **Architecture & Concurrency:** Well-structured separation into `presentation/`, `domain/`, `data/`, and `core/` layers. ViewModels use `StateFlow` and structured UI events. Asynchronous workflows run on Kotlin Coroutines and Flow (`Dispatchers.IO`, `SupervisorJob`).
   - **Dependency Injection Deviation:** Does not use standard Android DI frameworks (e.g., Hilt or Koin); uses a **manual Service Locator pattern** initialized inside `NexaApplication.onCreate()`.
   - **Authentication:** Google Sign-In is implemented using **Android Credential Manager** (`androidx.credentials:1.3.0`) and `GetGoogleIdOption`.
   - **Local Storage & Hardware Security:** Implements **Room 2.6.1** encrypted with **SQLCipher 4.6.1** (`SupportOpenHelperFactory`) and 16 progressive schema migrations. Android Keystore protects the master vault key (`AES-256-GCM`). However, a silent fallback to unencrypted `SharedPreferences` exists if Keystore initialisation fails.

2. **Backend Implementation Status:**
   - **Language & Networking:** Headless backend written in **Go 1.22** using `gorilla/websocket` and `redis/go-redis/v9`. Relays end-to-end encrypted Protobuf packets without server-side plaintext access.
   - **Multi-Service Architecture Disconnect:** The Go backend consists of 7 isolated binaries in `backend/cmd/` (`server`, `handshake`, `qr-sync`, `security-rules`, `swarm`, `relay`, `signaling`). The production `Dockerfile` and Render configuration deploy only `cmd/server/main.go` (`./nexa-relay`). Consequently, routes defined in satellite binaries (such as Prekey registration, Friend Request dispatch, QR contact exchange, and Swarm coordination) are **not mounted on the production server**, causing HTTP 404 errors for several Android client features.
   - **Database & Supabase Disconnect:** While 16 PostgreSQL migrations with strict Row-Level Security (RLS) exist in `database/migrations/`, **the Go backend does not contain any PostgreSQL driver (`database/sql`, `pgx`, `lib/pq`) or database connection pool**. User identities, devices, and sessions are stored in an in-memory struct persisted to a local flat JSON file (`data/identity_store.json`). The Android client contains **no Supabase SDK or Supabase REST configuration**.
   - **Cloudflare R2 Status:** **Not implemented.** The `/api/v1/media/upload-ticket` endpoint generates a mock URL (`https://r2.nexa.network/...`), `/api/v1/media/purge-ack` returns a dummy response, no AWS S3/R2 SDK or credentials exist on the backend, and the Android client never invokes the upload ticket endpoint.
   - **Firebase Cloud Messaging (FCM) Status:** **Not integrated in Android.** The Android app lacks the Firebase Messaging library, Google Services Gradle plugin, and `google-services.json`. The backend `FcmService` sends data-only pings but lacks Google OAuth2 token refresh logic.

3. **Cryptographic & Security Anomalies Identified:**
   - **Identity Key Derivation Flaw:** In `CryptoKeystoreManager.kt` (lines 128–138), public keys are derived by hashing the private seed with SHA-256 and bit-clamping, rather than performing elliptic curve scalar multiplication ($Q = d \cdot G$). In contrast, `NexaApplication.kt` separately derives Ed25519 keys via BouncyCastle.
   - **Unauthenticated Signaling Endpoint:** The WebRTC signaling endpoint `/ws/signaling` performs zero identity authentication or challenge verification, allowing arbitrary clients to connect and intercept signaling packets.
   - **Release Build Hardening:** ProGuard/R8 minification is disabled (`isMinifyEnabled = false`), cleartext network traffic is enabled (`android:usesCleartextTraffic="true"` and `cleartextTrafficPermitted="true"` trusting user CA certificates), and release keystore credentials are stored in plaintext in `local.properties`.

4. **Areas Verified:**
   - Full Android Kotlin source code, Jetpack Compose UI components, Room database schemas, cryptographic implementations (Double Ratchet, X3DH, ChaCha20-Poly1305, Ed25519, BLAKE3), OkHttp networking, and manifest configurations.
   - Go backend routing, WebSocket authentication, session hub, Redis buffer, OTP service, reverse SMS, and Docker/Render configurations.
   - Database DDL migrations, constraints, triggers, and RLS policies.

5. **Areas That Could Not Be Verified (External/Production Boundaries):**
   - Live SMS delivery via external gateways (MSG91 / Twilio).
   - Live external PostgreSQL instance / active Supabase dashboard policies.
   - Live Google Cloud FCM HTTP v1 message delivery to physical carrier networks.
   - Production Cloudflare R2 bucket settings.

---

## 2. Architecture Compliance Matrix

| Component | Expected Architecture | Actual Implementation | Status | Evidence |
| :--- | :--- | :--- | :---: | :--- |
| **Android Language** | Kotlin as primary application language | 100% Kotlin codebase across `app/src/main/java` | **COMPLIANT** | [build.gradle.kts](file:///H:/chat_app/app/build.gradle.kts#L4-L6), [app/src/main/java](file:///H:/chat_app/app/src/main/java) |
| **Android UI Framework** | Jetpack Compose for native UI | Jetpack Compose BOM 2026.02.01, Material3, NavHost, 25+ Compose screens | **COMPLIANT** | [libs.versions.toml](file:///H:/chat_app/gradle/libs.versions.toml#L9), [AuthNavGraph.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt) |
| **Credential Manager** | Android Credential Manager for Google Auth | `androidx.credentials:1.3.0`, `googleid:1.1.1` via `GetGoogleIdOption` | **COMPLIANT** | [WelcomeViewModel.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt#L82-L96) |
| **Layered Architecture** | Separation of UI, Domain, and Data layers | Distinct separation into `presentation/`, `domain/`, `data/`, and `core/` | **COMPLIANT** | [app/src/main/java/com/nexa/app](file:///H:/chat_app/app/src/main/java/com/nexa/app) |
| **State Management** | ViewModels and observable UI state | Dedicated ViewModels with `StateFlow`, UI state data classes, event sealed interfaces | **COMPLIANT** | [WelcomeViewModel.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt#L25-L54), [ChatViewModel.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/chat/ChatViewModel.kt) |
| **Asynchronous Engine** | Kotlin Coroutines and Flows | `kotlinx-coroutines-android:1.10.1`, `viewModelScope`, `Dispatchers.IO`, `SupervisorJob` | **COMPLIANT** | [ChatRepositoryImpl.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt#L65-L84) |
| **Dependency Injection** | Android DI framework (Hilt/Koin) | Manual Service Locator pattern inside `NexaApplication.kt` | **PARTIALLY COMPLIANT** | [NexaApplication.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/NexaApplication.kt#L206-L493) |
| **Native Networking** | Native Android HTTP/WebSocket libraries | OkHttp 4.12.0 for REST and WebSocket communication; no WebViews | **COMPLIANT** | [AuthApiServiceImpl.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt#L48-L53), [WebSocketManager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt) |
| **No Hybrid/Web Bloat** | Native Android only, zero browser shell | Zero web wrappers, no Cordova, Capacitor, React Native, or WebView usage | **COMPLIANT** | [AndroidManifest.xml](file:///H:/chat_app/app/src/main/AndroidManifest.xml), [app/build.gradle.kts](file:///H:/chat_app/app/build.gradle.kts) |
| **Google ID Verification** | Verification via backend auth provider | Backend verifies ID tokens against `GOOGLE_CLIENT_ID` via tokeninfo API | **COMPLIANT** | [google_verifier.go](file:///H:/chat_app/backend/internal/auth/google_verifier.go#L44-L90) |
| **Supabase Integration** | Client & backend Supabase Auth & Directory | No Supabase SDK in Android; backend uses local JSON file; JWT endpoint unused | **NOT IMPLEMENTED** | [app/build.gradle.kts](file:///H:/chat_app/app/build.gradle.kts), [store.go](file:///H:/chat_app/backend/internal/identity/store.go#L32-L52) |
| **Session Lifecycle** | Secure access/refresh token management | Custom session tokens (`nexa_sess_...`); no automated token refresh mechanism | **PARTIALLY COMPLIANT** | [AuthApiServiceImpl.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt#L128), [store.go](file:///H:/chat_app/backend/internal/identity/store.go#L87-L91) |
| **Credential Storage** | No plaintext secrets in repository or app | Keystore passwords in `local.properties`; keystore committed; pepper in docs | **NON-COMPLIANT** | [local.properties](file:///H:/chat_app/local.properties#L10-L12), [release.keystore](file:///H:/chat_app/app/release.keystore), [RENDER_ENV.md](file:///H:/chat_app/RENDER_ENVIRONMENT_VARIABLES.md#L15) |
| **PostgreSQL RLS** | RLS enabled on all user tables | 16 SQL migration files define RLS, but migrations are unapplied to live DB | **PARTIALLY COMPLIANT** | [012_create_rls_policies.sql](file:///H:/chat_app/database/migrations/012_create_rls_policies.sql#L8-L75) |
| **NX-ID Format & Uniqueness** | Strict `NX-[A-Z0-9]{8}` format | Backend `GenerateRandomNxID` uses Base32; SQL enforces regex constraint | **COMPLIANT** | [nxid.go](file:///H:/chat_app/backend/internal/identity/nxid.go#L24-L37), [001_create_profiles.sql](file:///H:/chat_app/database/migrations/001_create_profiles.sql#L21) |
| **Cloud Chat Privacy** | Zero plaintext message storage in cloud | Zero message text columns in SQL; Go relay stores only in RAM / 24h Redis buffer | **COMPLIANT** | [008_create_message_metadata.sql](file:///H:/chat_app/database/migrations/008_create_message_metadata.sql), [redis_buffer.go](file:///H:/chat_app/backend/internal/queue/redis_buffer.go#L15) |
| **Local Database Encrypted** | Room with SQLCipher encryption | Room 2.6.1 + SQLCipher 4.6.1 (`SupportOpenHelperFactory`) with 256-bit passphrase | **COMPLIANT** | [NexaDatabase.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt#L353-L364) |
| **Android Keystore Usage** | Hardware Keystore protects encryption keys | AndroidKeyStore AES-256-GCM Master Key; silent fallback to unencrypted XML on error | **PARTIALLY COMPLIANT** | [CryptoKeystoreManager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt#L31-L49), [SecureStorage.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L24-L27) |
| **Backup Leakage Prevention** | Android backups exclude sensitive DB/keys | `allowBackup="true"`; XML rules empty with no `<exclude>` definitions | **NON-COMPLIANT** | [AndroidManifest.xml](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L63-L65), [backup_rules.xml](file:///H:/chat_app/app/src/main/res/xml/backup_rules.xml) |
| **Local-First Outbox Pattern** | Offline-first local message persistence | Messages saved to SQLCipher with status `PENDING` before network dispatch | **COMPLIANT** | [ChatRepositoryImpl.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt#L102-L113) |
| **X25519 & Ed25519 Usage** | Correct cryptographic usage | Tink X25519 for DH, BouncyCastle Ed25519 for signatures; derivation bug in Keystore | **PARTIALLY COMPLIANT** | [X3dhHandshakeEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/X3dhHandshakeEngine.kt#L54-L67), [CryptoKeystoreManager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt#L128-L138) |
| **X3DH Key Agreement** | Extended Triple Diffie-Hellman protocol | Performs DH1, DH2, DH3, optional DH4, HKDF-SHA256, Ed25519 signature verification | **COMPLIANT** | [X3dhHandshakeEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/X3dhHandshakeEngine.kt#L45-L95) |
| **Double Ratchet Protocol** | Signal-style DH + KDF ratcheting | X25519 DH ratchet on turn rotation, symmetric KDF chains, skipped key store | **COMPLIANT** | [DoubleRatchetEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt#L118-L230) |
| **Zero-Access Server Relay** | Server cannot access message plaintext | Relays opaque Protobuf envelopes (`ciphertext`, `nonce`, `auth_tag`) | **COMPLIANT** | [hub.go](file:///H:/chat_app/backend/internal/connection/hub.go#L225-L235), [wire.go](file:///H:/chat_app/backend/internal/wire/wire.go#L35-L40) |
| **Go Backend Infrastructure** | Go server with WebSocket relay | Go 1.22 headless relay server, ping/pong heartbeats, 64KB read/write buffers | **COMPLIANT** | [main.go](file:///H:/chat_app/backend/cmd/server/main.go#L24-L39), [client.go](file:///H:/chat_app/backend/internal/connection/client.go#L184-L265) |
| **WebSocket Relay Auth** | Ephemeral challenge-response handshake | 32-byte server nonce, 60s deadline, Ed25519 signature verification, anti-replay | **COMPLIANT** | [client.go](file:///H:/chat_app/backend/internal/connection/client.go#L65-L175) |
| **WebRTC Signaling Auth** | Protected signaling channel | Signaling WebSocket (`/ws/signaling`) has **zero authentication** checks | **NON-COMPLIANT** | [main.go](file:///H:/chat_app/backend/cmd/server/main.go#L120-L138), [hub.go](file:///H:/chat_app/backend/internal/signaling/hub.go#L56-L82) |
| **Delivery Acknowledgments** | Server & device delivery tracking | `ACK_SERVER_RECEIVED`, `ACK_DEVICE_DELIVERED`, `ACK_DEVICE_READ` payloads routed | **COMPLIANT** | [hub.go](file:///H:/chat_app/backend/internal/connection/hub.go#L133-L151), [NexaWirePacket.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/proto/NexaWirePacket.kt#L26-L34) |
| **Rate Limiting** | Abuse prevention on endpoints | OTP: 5/hr per phone, 15/hr per IP; contact lookup: max 100; no HTTP/WS global limiter | **PARTIALLY COMPLIANT** | [otp_service.go](file:///H:/chat_app/backend/internal/auth/otp_service.go#L139-L148), [handler.go](file:///H:/chat_app/backend/internal/auth/handler.go#L647) |
| **WebRTC P2P DataChannels** | P2P media/file transfer via SCTP | `stream-webrtc-android:1.3.10`, SCTP DataChannel creation, Google STUN servers | **COMPLIANT** | [WebRtcClient.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt#L90-L100), [P2PTransferRepositoryImpl.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/P2PTransferRepositoryImpl.kt) |
| **P2P Circuit Breaker** | 4.5s ICE timeout gate with cloud fallback | Logic implemented in `MediaRoutingCoordinator`, but invoked **only in unit tests** | **PARTIALLY COMPLIANT** | [MediaRoutingCoordinator.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/media/MediaRoutingCoordinator.kt#L174-L196) |
| **Cloudflare R2 Storage** | Ephemeral media storage fallback | Mock URL generator in Go; no S3 client; not connected in Android client | **NOT IMPLEMENTED** | [main.go](file:///H:/chat_app/backend/cmd/server/main.go#L213-L255) |
| **Firebase Cloud Messaging** | Background wakeup push notifications | Not integrated in Android (no SDK/config); Go has mock payload sender, no OAuth2 | **NOT IMPLEMENTED** | [app/build.gradle.kts](file:///H:/chat_app/app/build.gradle.kts), [fcm_service.go](file:///H:/chat_app/backend/internal/fcm/fcm_service.go#L28-L43) |
| **Release Build Security** | Proguard/R8 minification enabled | `isMinifyEnabled = false` in release build block; Proguard disabled | **NON-COMPLIANT** | [app/build.gradle.kts](file:///H:/chat_app/app/build.gradle.kts#L47) |
| **Network Security Config** | Cleartext traffic disabled | `usesCleartextTraffic="true"` and base config permits cleartext / user CA certs | **NON-COMPLIANT** | [AndroidManifest.xml](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L70), [network_security_config.xml](file:///H:/chat_app/app/src/main/res/xml/network_security_config.xml#L3-L7) |

---

## 3. Native Android Inspection

### 3.1. Language, Framework & UI
- **Kotlin:** The Android project is built with Kotlin 2.2.10 (`app/build.gradle.kts:4`, `compileSdk = 37`, `minSdk = 24`, `targetSdk = 35`).
- **Jetpack Compose:** Compose BOM `2026.02.01` with Material3 and Navigation Compose 2.8.8. All UI screens in `app/src/main/java/com/nexa/app/presentation/` (`ChatScreen.kt`, `WelcomeScreen.kt`, `OtpVerificationScreen.kt`, `ProfileSetupScreen.kt`, `BackupSettingsScreen.kt`, `ContactListScreen.kt`, etc.) are 100% native Compose composables.
- **Zero Browser Hybrid Code:** No HTML, CSS, JavaScript, Cordova, Capacitor, or Android `WebView` is present in the application package.

### 3.2. Architecture & Concurrency
- **Layer Separation:** Clean division of responsibilities:
  - `presentation/`: Composable screens, ViewModels, UI state classes (`WelcomeUiState`, `ChatUiState`, etc.).
  - `domain/`: Pure repository interfaces (`AuthRepository`, `ChatRepository`, `DtnRepository`, `ContactRepository`, `P2PTransferRepository`).
  - `data/`: Room DAOs, entities, OkHttp API clients (`AuthApiServiceImpl`, `HandshakeApiImpl`, `QrSyncApiImpl`), transports (`InternetTransport`, `SmsTransport`, `MeshTransport`), and background workers (`ScheduledBackupWorker`, `RequestSyncWorker`, `RuleSyncWorker`).
  - `core/`: Cryptographic engines, Keystore management, WebRTC client, and hardware monitors.
- **Coroutines & Reactive State:** Uses `kotlinx.coroutines:1.10.1`. ViewModels expose reactive state via `StateFlow` and `asStateFlow()`. Background repository work uses `Dispatchers.IO` with `SupervisorJob()`.

### 3.3. Dependency Injection
- **Implementation:** **Manual Dependency Injection (Service Locator Pattern).** No DI framework (Hilt, Dagger, or Koin) is included in `build.gradle.kts`.
- **Location:** In [NexaApplication.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/NexaApplication.kt#L206-L493), all 30+ repositories, database handles, API clients, and cryptographic engines are instantiated sequentially in `onCreate()`. Activities and composables retrieve dependencies via `(application as NexaApplication).repositoryName`.

### 3.4. Google Authentication via Credential Manager
- **Implementation:** [WelcomeViewModel.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt#L82-L96) instantiates `CredentialManager.create(context)` and requests credentials via `GetGoogleIdOption.Builder().setServerClientId(webClientId).build()`.
- **Token Exchange:** The retrieved `GoogleIdTokenCredential.idToken` is submitted to the backend via `AuthRepository.loginWithGoogle(token)`.
- **Hardcoded Identifier:** The OAuth Web Client ID is embedded directly in [app/build.gradle.kts](file:///H:/chat_app/app/build.gradle.kts#L31-L33) (`buildConfigField "String", "GOOGLE_WEB_CLIENT_ID", "\"356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com\""`). While Google Client IDs are public, hardcoding complicates multi-environment staging/production builds.

### 3.5. Local Database & SQLCipher
- **Room Database:** [NexaDatabase.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt) version 16 declares 13 tables (`users`, `messages`, `chats`, `dtn_envelopes`, `file_transfers`, `file_metadata`, `ratchet_states`, `skipped_keys`, `contacts`, `quarantine_records`, `transfer_sessions`, `mesh_outbox`, `device_sync`).
- **SQLCipher Integration:** SQLCipher 4.6.1 (`net.zetetic:sqlcipher-android:4.6.1`) encrypts the database file `nexa_encrypted_vault.db` via `SupportOpenHelperFactory(passphrase)`.
- **Passphrase Generation & Keystore Protection:** [SecureStorage.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L50-L61) generates a 32-byte high-entropy passphrase via `SecureRandom` and saves it to `EncryptedSharedPreferences`.
- **Vulnerability — Unencrypted Fallback:** In [SecureStorage.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/SecureStorage.kt#L24-L27), if `EncryptedSharedPreferences` throws an exception (e.g., during Keystore initialization issues on OEM ROMs), it silently falls back to standard, unencrypted `SharedPreferences` (`Context.MODE_PRIVATE`), storing the SQLCipher database passphrase in plaintext XML.

### 3.6. Cryptographic Implementation (E2EE)
- **Diffie-Hellman:** Tink X25519 (`com.google.crypto.tink.subtle.X25519`) handles key exchanges in [X3dhHandshakeEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/X3dhHandshakeEngine.kt) and [DoubleRatchetEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt).
- **Symmetric Encryption:** ChaCha20-Poly1305 implemented in [ChaChaAeadCipher.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/ChaChaAeadCipher.kt) using Tink. Nonces are derived deterministically from the ratcheted message key and sequence number.
- **Digital Signatures:** Ed25519 implemented in [Ed25519Signer.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/Ed25519Signer.kt) using BouncyCastle (`org.bouncycastle:bcprov-jdk18on:1.80`).
- **Defect — Flawed Key Derivation in CryptoKeystoreManager:** In [CryptoKeystoreManager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt#L128-L138):
  ```kotlin
  private fun derivePublicKeyBytes(privateSeed: ByteArray): ByteArray {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      val hash = digest.digest(privateSeed)
      val publicKey = ByteArray(32)
      System.arraycopy(hash, 0, publicKey, 0, 32)
      publicKey[0] = (publicKey[0].toInt() and 248).toByte()
      publicKey[31] = (publicKey[31].toInt() and 127).toByte()
      publicKey[31] = (publicKey[31].toInt() or 64).toByte()
      return publicKey
  }
  ```
  Instead of performing actual elliptic curve point multiplication ($Q = d \cdot G$), this function takes `SHA-256(seed)` and applies Curve25519 clamping to the hash. This yields an invalid Curve25519/Ed25519 public key that cannot be verified by standard cryptographic libraries.
- **Architectural Divergence:** In [NexaApplication.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/NexaApplication.kt#L230-L259), the WebSocket identity signer bypasses `CryptoKeystoreManager` and separately generates an Ed25519 keypair in `nexa_handshake_keys` using BouncyCastle's `Ed25519Signer.derivePublicKey()`, creating two inconsistent identity key systems on the device.

### 3.7. WebRTC & P2P Engine
- WebRTC integrated via `io.getstream:stream-webrtc-android:1.3.10` in [WebRtcClient.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt).
- Configures Google STUN servers (`stun:stun.l.google.com:19302`, `stun1`, `stun2`). TURN servers are intentionally absent per architectural design.
- P2P file transfers are handled via SCTP `DataChannel` with background execution managed by `P2PTransferForegroundService` (`dataSync` type).
- The 4.5-second ICE timeout circuit breaker logic exists in [MediaRoutingCoordinator.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/media/MediaRoutingCoordinator.kt#L174-L196), but is **invoked only in unit test fixtures** (`MediaRoutingAndSignalingUnitTests.kt`), not in production chat flows.

### 3.8. Firebase Cloud Messaging (FCM)
- **Absent from Android Application:**
  - Neither `firebase-messaging` nor `com.google.gms.google-services` is declared in [app/build.gradle.kts](file:///H:/chat_app/app/build.gradle.kts).
  - No `google-services.json` configuration file exists in `app/`.
  - No `FirebaseMessagingService` is registered in [AndroidManifest.xml](file:///H:/chat_app/app/src/main/AndroidManifest.xml).
  - [NexaPushManager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/push/NexaPushManager.kt#L31) provides an `onNewPushToken()` method, but it is never called by the operating system.
  - [AndroidManifest.xml](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L101-L107) exposes an exported receiver `NexaPushWakeUpReceiver` for `com.nexa.app.PUSH_WAKE_UP` without permission protection.

---

## 4. Backend Inspection

### 4.1. Server Architecture & Deployment
- Headless backend written in **Go 1.22** (`nexa/wire`).
- Docker configuration uses a multi-stage Alpine build (`backend/Dockerfile`).
- Deployed on Render (`nexa-relay.onrender.com`).
- **Binary Splitting Issue:** The backend contains 7 distinct main packages under `backend/cmd/`:
  1. `cmd/server/main.go` (Messaging relay, Auth, WebRTC signaling, DTN cloud dump)
  2. `cmd/handshake/main.go` (X3DH Prekey storage & anti-spam PoW friend request dispatch)
  3. `cmd/qr-sync/main.go` (Instant cryptographic QR contact exchange)
  4. `cmd/security-rules/main.go` (YARA security rule differential bundle distribution)
  5. `cmd/swarm/main.go` (P2P swarm seed catalog coordinator)
  6. `cmd/relay/main.go` (Alternative blind relay)
  7. `cmd/signaling/main.go` (Alternative standalone signaling)
- **Deployment Misalignment:** [backend/Dockerfile](file:///H:/chat_app/backend/Dockerfile#L35) executes only `./nexa-relay` (`cmd/server/main.go`). The other 6 microservices are compiled but never started. Consequently, endpoints for prekeys, contact requests, QR sync, security rules, and swarms are unreachable on the production host and return HTTP 404.

### 4.2. API Authentication & WebSocket Relay
- **Messaging WebSocket (`/ws`):**
  - Requires query parameter `nx_id`.
  - Issues a cryptographically random 32-byte challenge nonce with a strict 60-second TTL ([client.go](file:///H:/chat_app/backend/internal/connection/client.go#L65-L109)).
  - Requires client to respond with an Ed25519 signature over `nonce + ":" + nxId + ":" + timestamp`.
  - Nonces are invalidated immediately upon verification attempt to prevent replay attacks ([client.go:159](file:///H:/chat_app/backend/internal/connection/client.go#L159)).
  - Unauthenticated clients are terminated with WebSocket close code 4001.
- **WebRTC Signaling WebSocket (`/ws/signaling`):**
  - Registered in [cmd/server/main.go](file:///H:/chat_app/backend/cmd/server/main.go#L120-L138).
  - **Critical Security Gap:** Performs **no authentication**. Any client can supply an arbitrary `nx_id` and receive signaling envelopes intended for that peer.

### 4.3. Presence, Delivery ACKs & Offline Queue
- **Presence:** In-memory concurrent map `map[string]map[string]*ClientSession` (NX-ID -> DeviceID -> Session) protected by `sync.RWMutex` ([hub.go](file:///H:/chat_app/backend/internal/connection/hub.go#L19)).
- **Delivery Acknowledgments:** When an encrypted message arrives, the Hub immediately generates an `ACK_SERVER_RECEIVED` packet back to the sender ([hub.go:134-151](file:///H:/chat_app/backend/internal/connection/hub.go#L134-L151)). Downstream device ACKs (`ACK_DEVICE_DELIVERED`, `ACK_DEVICE_READ`) are routed through the session table.
- **Offline Message Buffer:** Ephemeral Redis buffer ([redis_buffer.go](file:///H:/chat_app/backend/internal/queue/redis_buffer.go)) with a 24-hour TTL (`RPush` + `Expire(24h)`). When the recipient reconnects, `PopAllPending()` flushes messages and issues `Del(key)` to erase them from Redis. An in-memory map acts as a fallback when Redis is absent.
- **Multi-Device Sync:** The Hub forwards outgoing messages to all other active device sessions registered under the sender's NX-ID ([hub.go:153-166](file:///H:/chat_app/backend/internal/connection/hub.go#L153-L166)).

### 4.4. Rate Limiting & Abuse Prevention
- **OTP Verification:** [otp_service.go](file:///H:/chat_app/backend/internal/auth/otp_service.go#L139-L148) limits requests to 5 per phone number per hour, 15 per IP per hour, and enforces a 60-second cooldown between dispatches. Max 3 verification attempts per OTP code before invalidation.
- **Proof-of-Work Verification:** In `cmd/handshake`, friend requests require 16 leading zero bits on `SHA-256(targetNxId : timestamp : nonce)` within a 10-minute validity window ([pow_verifier.go](file:///H:/chat_app/backend/internal/handshake/pow_verifier.go)).
- **General Endpoints:** No global or per-IP rate-limiting middleware protects general HTTP endpoints (`/api/v1/auth/google`, `/api/v1/media/...`, `/ws`).

### 4.5. Cloudflare R2 Mock Status
- [cmd/server/main.go](file:///H:/chat_app/backend/cmd/server/main.go#L213-L255) defines `/api/v1/media/upload-ticket`. It checks if `size_bytes > 100MB` and returns:
  ```go
  uploadUrl := fmt.Sprintf("https://r2.nexa.network/%s?ticket=%d&hash=%s", objectKey, now+900, req.CipherHash)
  ```
  This is a static string template. There is no AWS SDK, no Cloudflare R2 bucket connection, no HMAC SigV4 signature generation, and no S3 client.
- The `/api/v1/media/purge-ack` endpoint logs receipt and returns `retained_bytes: 0` without performing any cloud storage deletion.

### 4.6. Logging & Data Leakage
- Phone numbers are masked in logs using `maskPhone()` ([sms_provider.go:22-29](file:///H:/chat_app/backend/internal/auth/sms_provider.go#L22-L29)) to avoid recording complete MSISDNs.
- In development/test mode (`APP_ENV != "production"`), [MockSmsProvider](file:///H:/chat_app/backend/internal/auth/sms_provider.go#L207-L209) logs plaintext OTPs to `stdout`.

---

## 5. Security Inspection

### 5.1. Credential Exposure Risks
1. **Committed Release Keystore & Passwords:**
   - [local.properties](file:///H:/chat_app/local.properties#L10-L12) contains `RELEASE_STORE_PASSWORD=nexa_release_pass`, `RELEASE_KEY_PASSWORD=nexa_release_pass`, and `RELEASE_KEY_ALIAS=nexa_key` in plaintext.
   - The signing keystore binary [app/release.keystore](file:///H:/chat_app/app/release.keystore) is committed directly to the Git repository.
2. **Hardcoded Cryptographic Pepper in Documentation:**
   - In [RENDER_ENVIRONMENT_VARIABLES.md](file:///H:/chat_app/RENDER_ENVIRONMENT_VARIABLES.md#L15), the production OTP HMAC salt pepper `OTP_SERVER_PEPPER=a8b3c9d2e1f40582736451a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9` is committed in plaintext.
   - In [otp_service.go](file:///H:/chat_app/backend/internal/auth/otp_service.go#L94-L96), a fallback pepper `nexa_production_otp_hmac_secret_pepper_2026_secured` is hardcoded in the Go binary.

### 5.2. Network & Transport Security Vulnerabilities
1. **Cleartext Traffic Permitted:**
   - [AndroidManifest.xml:70](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L70) sets `android:usesCleartextTraffic="true"`.
   - [network_security_config.xml](file:///H:/chat_app/app/src/main/res/xml/network_security_config.xml#L3-L7) sets `<base-config cleartextTrafficPermitted="true">` and includes `<certificates src="user" />` as trusted anchors.
   - This allows unencrypted HTTP/WS traffic in release builds and permits Man-in-the-Middle (MITM) attacks if a user installs a custom CA certificate.
2. **Exported Receiver Without Permissions:**
   - [AndroidManifest.xml:101-107](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L101-L107) exposes `NexaPushWakeUpReceiver` with `android:exported="true"` and no custom permission requirement, allowing any third-party app installed on the device to send wake-up intents.

### 5.3. Application Hardening & Build Configuration
1. **Minification Disabled:**
   - In [app/build.gradle.kts:47](file:///H:/chat_app/app/build.gradle.kts#L47), `buildTypes.release` sets `isMinifyEnabled = false`.
   - ProGuard/R8 code shrinking and symbol obfuscation are disabled in release APK builds.
2. **Backup Data Extraction Exposure:**
   - In [AndroidManifest.xml:63](file:///H:/chat_app/app/src/main/AndroidManifest.xml#L63), `android:allowBackup="true"`.
   - Both [res/xml/backup_rules.xml](file:///H:/chat_app/app/src/main/res/xml/backup_rules.xml) and [res/xml/data_extraction_rules.xml](file:///H:/chat_app/app/src/main/res/xml/data_extraction_rules.xml) contain empty boilerplate with no `<exclude>` entries for SQLite databases or shared preferences, allowing Android system backup to extract application databases and preferences.

### 5.4. Database Access-Control & Cloud Storage Security
1. **Supabase / PostgreSQL Isolation:**
   - While migration files specify proper Row-Level Security, **the production backend does not connect to Supabase/PostgreSQL**. User data isolation currently relies entirely on Go in-memory maps and session token checks against `data/identity_store.json`.
2. **Media Storage Security:**
   - Media storage relies on client-side streaming AES-256-GCM ([BlindMediaVaultEngine.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/media/BlindMediaVaultEngine.kt)).
   - However, because Cloudflare R2 is not actually integrated, media uploads via cloud fallback fail.

---

## 6. Missing or Partially Implemented Requirements

### 6.1. Completely Missing (Not Implemented)
1. **Active Supabase Runtime:** No Supabase Kotlin SDK in Android; no database driver or connection in Go; no execution of PostgreSQL migrations against an active cloud database.
2. **Cloudflare R2 Integration:** No AWS S3 SDK, no access credentials, no SigV4 presigned URL generation, and no S3 deletion calls on the backend; no upload call executed from Android.
3. **Firebase Cloud Messaging in Android:** No Firebase SDK or dependencies in Gradle; no `google-services.json`; no `FirebaseMessagingService`.
4. **Backend FCM Token Refresh:** No Google OAuth2 service account token exchange in Go `FcmService`; live requests to the FCM HTTP v1 API will return HTTP 401 Unauthorized.
5. **CMake / NDK Compilation for LibYARA:** [yara_bridge.cpp](file:///H:/chat_app/app/src/main/cpp/yara_bridge.cpp) exists, but `app/build.gradle.kts` lacks an `externalNativeBuild` block. The native binary `libyara_bridge.so` is never compiled, forcing `YaraNativeBridge` to run in its Kotlin heuristic fallback mode.

### 6.2. Partially Implemented
1. **Backend Service Integration:** 6 satellite microservices (`handshake`, `qr-sync`, `security-rules`, `swarm`, `relay`, `signaling`) exist as separate Go binaries but are not mounted into the primary server binary (`cmd/server/main.go`) deployed to production.
2. **WebRTC P2P Circuit Breaker:** The 4.5s ICE timeout switch to cloud fallback is written in `MediaRoutingCoordinator.kt`, but is only invoked in unit tests.
3. **Session Token Refresh Lifecycle:** Custom session tokens are created on registration/login, but automated token refresh and expiration recovery workflows are absent.
4. **Rate Limiting:** OTP endpoints and contact lookup have rate limits, but the signaling WebSocket, messaging WebSocket, and general HTTP endpoints have no connection or request limits.

---

## 7. Architecture Deviations

1. **Monolithic Relay vs. Disjoint Microservices:**
   - *Specified Architecture:* The Android client expects a single unified backend endpoint supporting messaging, signaling, handshakes, QR sync, and security rules.
   - *Actual Implementation:* The Go backend is split into 7 separate binaries in `backend/cmd/`. The Dockerfile only runs `nexa-relay`, leaving the other 6 services unhosted.
2. **Cloud Database Architecture:**
   - *Specified Architecture:* PostgreSQL / Supabase as the central cloud authentication authority and user directory with strict Row-Level Security.
   - *Actual Implementation:* The Go backend manages accounts, devices, and sessions via an in-memory struct saved to a local flat JSON file (`data/identity_store.json`).
3. **Dependency Injection Framework:**
   - *Specified Architecture:* Modern Android application architecture utilizing an established DI framework (such as Hilt).
   - *Actual Implementation:* Manual Service Locator pattern inside `NexaApplication.kt`.
4. **Public Key Derivation:**
   - *Specified Architecture:* Ed25519 and X25519 public keys derived via standard elliptic curve scalar multiplication.
   - *Actual Implementation:* `CryptoKeystoreManager.kt` derives public keys via `SHA-256(seed)` with Curve25519 bit clamping, diverging from standard RFC 7748 / RFC 8032 derivation.

---

## 8. Verification Limitations

The following items could not be validated due to read-only constraints and lack of external production infrastructure access:

1. **Live Cellular SMS Delivery:** Could not verify real-world SMS delivery over MSG91 or Twilio due to the absence of production API keys in the environment.
2. **Live Supabase Database State:** Could not inspect running PostgreSQL tables, triggers, or active RLS policies because no live Supabase instance was reachable.
3. **Real Device-to-Device WebRTC Traversal:** P2P DataChannel connectivity across asymmetric carrier CGNAT could not be verified in the absence of paired physical devices.
4. **FCM Push Notification Delivery:** Live wakeup delivery over Google Play Services could not be verified because Firebase credentials and services are unconfigured.
5. **Render Production Host File System:** The internal runtime state of `nexa-relay.onrender.com` (e.g., whether `data/identity_store.json` persists across container restarts) could not be verified via static inspection.

---

## 9. Final Assessment

### Conclusion:
The existing NEXA codebase implements a substantial portion of the specified native Android architecture and zero-knowledge messaging model:
- The Android application is a **100% native Kotlin and Jetpack Compose** app with clean layer separation and reactive state management.
- The local-first messaging architecture correctly combines **Room 2.6.1**, **SQLCipher 4.6.1**, the Outbox pattern, and hardware Keystore protection.
- Core end-to-end encryption protocols (**Double Ratchet, X3DH, ChaCha20-Poly1305, Ed25519**) are implemented with hand-crafted Protobuf wire serialization, ensuring zero server-side plaintext access over WebSockets.

However, the system is **not fully compliant** with the complete end-to-end architectural specification due to major structural gaps:
1. **Supabase / PostgreSQL is not connected** in either the Android app or Go backend; the backend operates on a flat JSON file.
2. **Cloudflare R2 is entirely simulated** via mock URL strings without an underlying S3 client.
3. **Firebase Cloud Messaging is missing from the Android application**.
4. **Backend microservices are partitioned**, leaving handshake prekey discovery, QR sync, and swarm coordination unmounted in production.
5. **Security vulnerabilities** exist in release build settings (`isMinifyEnabled = false`, cleartext traffic permitted, unauthenticated `/ws/signaling`, and committed signing keys).

### Compliance Category Breakdown:

- **COMPLIANT:**
  - Native Kotlin application
  - Jetpack Compose UI
  - Android Credential Manager for Google Sign-In
  - Separation of UI, Domain, and Data layers
  - ViewModels & reactive UI state management
  - Kotlin Coroutines & Flow concurrency
  - Native OkHttp networking (Zero web/hybrid bloat)
  - Room + SQLCipher local database encryption at rest
  - Local-first Outbox messaging pattern
  - Signal-style Double Ratchet implementation
  - Extended Triple Diffie-Hellman (X3DH) protocol
  - Server-side zero-access relaying of encrypted Protobuf envelopes
  - Ephemeral Redis message buffering with 24-hour TTL
  - WebSocket Ed25519 challenge-response handshake authentication
  - Native WebRTC DataChannel integration (`stream-webrtc-android`)
  - Outgoing multi-device message fan-out

- **PARTIALLY COMPLIANT:**
  - Dependency Injection (Manual Service Locator instead of Hilt/Koin)
  - Android Keystore key protection (Compromised by silent unencrypted fallback)
  - Public Key Derivation (`CryptoKeystoreManager` SHA-256 clamping defect)
  - PostgreSQL Row-Level Security (DDL defined, but unapplied to live DB)
  - Rate Limiting (Implemented on OTP & contacts, absent on WebSockets & general HTTP)
  - WebRTC Circuit Breaker (Logic implemented, but only executed in unit tests)

- **NOT IMPLEMENTED:**
  - Supabase Auth & Cloud Database connectivity
  - Cloudflare R2 media storage & signed URL generation
  - Firebase Cloud Messaging in Android application
  - Google OAuth2 service account token refresh in backend FCM service
  - Native CMake/NDK build for LibYARA scanning

- **NON-COMPLIANT:**
  - Unauthenticated WebRTC signaling endpoint (`/ws/signaling`)
  - Release build ProGuard/R8 minification disabled (`isMinifyEnabled = false`)
  - Cleartext traffic permitted in `AndroidManifest.xml` and `network_security_config.xml`
  - Exported push receiver without permission protection (`NexaPushWakeUpReceiver`)
  - Plaintext release keystore credentials committed to `local.properties` and Git repository
  - OTP secret pepper committed to documentation markdown

- **NOT VERIFIED:**
  - Live cellular SMS gateway delivery (MSG91 / Twilio)
  - Carrier CGNAT WebRTC NAT traversal across real mobile networks
  - Live cloud database policy enforcement

---

## 10. Final Mandatory Confirmation

1. **This was an absolute read-only inspection.**
2. **No source code, configuration, scripts, or build files were modified, created, renamed, or deleted.**
3. **No database records, migrations, or external service configurations were altered.**
4. **No dependencies were installed, removed, upgraded, or downgraded.**
5. **No Git commits, pushes, checkouts, or branch modifications were performed.**
6. **No tasks, roadmaps, implementation trackers, or architectural decisions were modified.**
7. **No audit findings were automatically converted into implementation work or pending tickets.**
8. **The findings in this report are informational evidence only and do not authorize any future changes.**
9. **Zero project files or build caches were mutated during this audit.**