Jitne bhi features humne discuss kiye hain (**Local-first encrypted database, Data SMS injection, BLE/Wi-Fi Aware offline mesh, WebRTC P2P direct streaming, on-device virus scanning, hardware HEVC compression, hidden Google Drive backup, aur NX ID cryptographic identity**), unke hisab se pura production-grade tech stack aur development environment ka complete map yeh raha:

---

### 1. Programming Languages (Kaunsi Language Kahan Use Hogi?) 💻

| Language | Kahan Use Hogi? (Component) | Kyun Chuna Gaya? (Reason) |
| --- | --- | --- |
| **Kotlin** | **Android Mobile App (Frontend + Local Logic)** | Android ka modern official language hai. Coroutines background tasks aur network streams ko bina phone hang kiye smooth chalate hain. |
| **Go (Golang)** | **Backend Server (Signaling Relay + Auth)** | Go memory-safe hai aur iske Goroutines ek chote ₹400/month ke VPS par 50k–100k persistent WebSockets handle kar lete hain with near-zero RAM. |
| **C / C++** | **Native Security & Scanning Engine (Android NDK)** | YARA signature scanning aur low-level cryptographic operations ko direct CPU instruction speed par execute karne ke liye (JNI ke through Kotlin se bind hoga). |
| **SQL (SQLite)** | **Local Device Storage Engine** | Room / SQLCipher ke zariye on-device encrypted database queries run karne ke liye. |

---

### 2. Mobile App (Android Client) — Tools & Tech Stack 📱

* **UI Framework:** **Jetpack Compose** (Declarative modern UI framework, zero XML bloat).
* **Local Encrypted Database:** **Room Database + SQLCipher** (AES-256 encrypted local database).
* **Cryptography & Security:**
* **Tink / libsodium-android:** X25519 (Key Exchange), Ed25519 (Digital Signatures), aur ChaCha20-Poly1305 (Symmetric Encryption).
* **Android Keystore (StrongBox / TEE):** Hardware-backed key protection.


* **Networking & Transport:**
* **Ktor Client / OkHttp:** WebSocket client persistent connection ke liye.
* **Google WebRTC Android SDK:** Peer-to-Peer direct file streaming aur NAT traversal (ICE/STUN).
* **Google Nearby Connections API + Wi-Fi Aware (NAN):** Offline BLE/Wi-Fi mesh routing ke liye.
* **Android Telephony `SmsManager`:** Port-bound Data SMS handling.


* **Media & File Processing:**
* **AndroidX Media3 Transformer:** Hardware-accelerated H.265/HEVC video compression.
* **Apache Tika Core / Custom Header Parser:** Magic bytes file inspection (anti-spoofing).
* **LibYara (via JNI):** On-device virus aur malicious script pattern scanning.


* **Authentication & Sync:**
* **Google Credential Manager API:** Modern Google Sign-In.
* **Google Drive REST API v3:** `appDataFolder` me hidden vault backup.
* **Android WorkManager:** Background offline sync aur interrupted file transfer recovery.



---

### 3. Backend & Infrastructure (Zero Cloud Storage, Minimal Cost) ☁️

* **Relay Language:** **Go (Golang)** using standard `net/http` + `gorilla/websocket` (ya `nhooyr.io/websocket`).
* **Fast In-Memory Broker:** **Redis** (Sirf active socket session tracking aur undelivered message queues ke liye with TTL; server disk par koi chat ya file save nahi hoti).
* **NAT Traversal (P2P Traversal):** **Coturn** (Open-source self-hosted STUN/TURN server).
* **Push Notifications:** **Firebase Cloud Messaging (FCM)** (Free-tier high priority data messages to wake up background devices).
* **Server OS & Hosting:** Ubuntu Linux on a budget VPS (Hetzner, DigitalOcean, ya Linode — $4 to $10/month).
* **Containerization:** **Docker + Docker Compose** (Pure backend, Redis aur Coturn ko ek command me deploy karne ke liye).

---

### 4. Development Tools (Softwares & IDEs) 🛠️

* **Android Studio (Ladybug / Koala ya latest version):** Mobile app code, Jetpack Compose preview, aur Android NDK C++ debugging ke liye.
* **VS Code / GoLand:** Go backend aur Docker scripts likhne ke liye.
* **Postman / Bruno:** WebSocket aur REST API endpoints testing ke liye.
* **Git & GitHub:** Version control aur CI/CD workflows ke liye.

---

### Development Phases (Kahan Se Shuru Karein?) 🗺️

1. **Phase 1 (Core Identity & Chat):** Kotlin + Jetpack Compose me UI + Room SQLCipher DB + Go WebSocket backend (Text messages with Internet).
2. **Phase 2 (Alternative Mediums):** Data SMS listener + BLE/Nearby Connections mesh integration.
3. **Phase 3 (P2P Engine):** WebRTC DataChannels + STUN/TURN integration + Chunk streaming & resume engine.
4. **Phase 4 (Security & Optimization):** Media3 video compression + LibYara scanner + Google Drive hidden backup.