# NEXA — Technology Stack & Dependencies Inventory

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Classification:** Actually Used vs Configured-but-Unused vs Stubs.

---

## 1. Programming Languages

| Language | Version / Standard | Active / Unused | Usage Location | Purpose |
|---|:---:|:---:|---|---|
| **Kotlin** | `2.2.10` | **ACTIVE** | `app/src/main/java/**` | Android application logic, Jetpack Compose UI, Coroutines, Room DAOs |
| **Go (Golang)** | `1.22` | **ACTIVE** | `backend/**` | Ephemeral WebSocket relays, WebRTC signaling, P2P swarm, security rules service |
| **C / C++** | `C++17` | **ACTIVE** | `app/src/main/cpp/**` | Native YARA bridge JNI engine (`yara_bridge.cpp`, `yara_bridge.h`) |
| **Protocol Buffers** | `Proto3` | **ACTIVE** | `proto/nexa_wire.proto` | Wire frame binary serialization schema across client and Go server |
| **SQL (SQLite)** | SQLite 3 | **ACTIVE** | Room SQLCipher DB | Encrypted on-device persistence queries |

---

## 2. Android & UI Stack

| Technology | Artifact ID / Component | Version | Status | Purpose |
|---|---|:---:|:---:|---|
| **Jetpack Compose** | `androidx.compose.bom` | `2026.02.01` | **ACTIVE** | Declarative Material 3 user interface components |
| **Compose Material 3** | `androidx.compose.material3:material3` | Latest BOM | **ACTIVE** | Material Design 3 cards, dialogs, progress bars, top bars |
| **Navigation Compose** | `androidx.navigation:navigation-compose` | `2.8.8` | **ACTIVE** | Deep-link capable declarative screen navigation (`AuthNavGraph.kt`) |
| **AndroidX Core KTX** | `androidx.core:core-ktx` | `1.19.1` | **ACTIVE** | Kotlin extensions for Android OS APIs |
| **Lifecycle Compose** | `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.11.0` | **ACTIVE** | ViewModel lifecycle bindings and `StateFlow` collection |
| **Android Telephony** | `android.telephony.SmsManager` | Native OS API | **ACTIVE** | Port 8901 binary Data SMS transmission without default app interference |

---

## 3. Cryptography & Security Libraries

| Technology | Artifact ID | Version | Status | Purpose |
|---|---|:---:|:---:|---|
| **Google Tink** | `com.google.crypto.tink:tink-android` | `1.15.0` | **ACTIVE** | X25519 Elliptic Curve Diffie-Hellman and AEAD primitives |
| **Bouncy Castle** | `org.bouncycastle:bcprov-jdk18on` | `1.80` | **ACTIVE** | ChaCha20-Poly1305 AEAD, HKDF-SHA256, Ed25519 digital signatures |
| **Android KeyStore** | Native Android Security Framework | Native | **ACTIVE** | Hardware-backed Master Key protection (StrongBox / TEE) |
| **Security Crypto** | `androidx.security:security-crypto` | `1.1.0-alpha06` | **ACTIVE** | `EncryptedSharedPreferences` for passphrases and session tokens |
| **SQLCipher** | `net.zetetic:android-database-sqlcipher` | `4.5.4` | **ACTIVE** | AES-256 database-level page encryption for Room DB |

---

## 4. Networking, WebRTC & P2P

| Technology | Artifact ID / Package | Version | Status | Purpose |
|---|---|:---:|:---:|---|
| **Stream WebRTC** | `io.getstream:stream-webrtc-android` | `1.3.10` | **ACTIVE** | Direct P2P SCTP DataChannels, ICE candidate negotiation, STUN/TURN |
| **OkHttp** | `com.squareup.okhttp3:okhttp` | `4.12.0` | **ACTIVE** | Persistent WebSocket client connection and HTTP REST communication |
| **Gorilla WebSocket** | `github.com/gorilla/websocket` | `v1.5.3` | **ACTIVE** | Go backend high-concurrency WebSocket connection upgrader |
| **Android NsdManager** | `android.net.nsd.NsdManager` | Native OS API | **ACTIVE** | LAN mDNS peer discovery for 30–50 MB/s direct Wi-Fi socket transfers |
| **Google Nearby** | `com.google.android.gms:play-services-nearby` | `19.3.0` | **ACTIVE** | Offline BLE and Wi-Fi Aware proximity mesh discovery |

---

## 5. Media Processing & Hardware Transcoding

| Technology | Artifact ID | Version | Status | Purpose |
|---|---|:---:|:---:|---|
| **Media3 Transformer** | `androidx.media3:media3-transformer` | `1.3.1` | **ACTIVE** | Hardware-accelerated H.265/HEVC video transcoding and compression |
| **Media3 Effects** | `androidx.media3:media3-effect` | `1.3.1` | **ACTIVE** | Video resizing to 720p and frame rate clamping to 30 fps |
| **ZXing Core** | `com.google.zxing:core` | `3.5.3` | **ACTIVE** | High-density QR code matrix generation for Day-0 key exchange |
| **Google Code Scanner** | `com.google.android.gms:play-services-code-scanner`| `16.1.0` | **ACTIVE** | Google Play Services zero-permission ML barcode scanner |
| **CameraX** | `androidx.camera:camera-camera2` | `1.4.1` | **ACTIVE** | Camera fallback scanner for de-Googled AOSP ROMs |

---

## 6. Background Processing & Infrastructure

| Technology | Artifact / Image | Version | Status | Purpose |
|---|---|:---:|:---:|---|
| **Android WorkManager**| `androidx.work:work-runtime-ktx` | `2.10.0` | **ACTIVE** | DTN Opportunistic dumps, 7-day orphan cleanups, OTA rule sync |
| **Redis** | `redis:7-alpine` | `7.0` | **ACTIVE** | Ephemeral in-memory queue buffer (disk persistence disabled) |
| **Docker Compose** | `docker-compose.yml` (v3.8) | `3.8` | **ACTIVE** | 7-microservice multi-container orchestration |
| **Firebase FCM** | `com.google.firebase:firebase-messaging` | N/A | **STUB ONLY** | Backend logs FCM pings; Android SDK not integrated |
| **Supabase** | `io.github.jan-tennert.supabase` | N/A | **ABSENT** | Not used in project by design |

---

## 7. Testing & Build System

| Technology | Version / Name | Purpose |
|---|:---:|---|
| **Gradle** | `9.6.0` | Primary Android build automation tool |
| **Android Gradle Plugin** | `9.4.1` | Compilation, dexing, and packaging of Android artifacts |
| **Kotlin Symbol Processing (KSP)**| `2.2.10-2.0.2` | Compile-time Room DAO code generation |
| **JUnit 4** | `4.13.2` | Core unit test runner for cryptographic, P2P, and mesh suites |
| **Coroutines Test** | `1.10.1` | Coroutine testing with `runTest` and `TestDispatcher` |
