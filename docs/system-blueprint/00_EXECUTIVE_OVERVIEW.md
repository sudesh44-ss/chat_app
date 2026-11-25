# NEXA — Master System Architecture Blueprint
## 00. Executive Overview & System Design Philosophy

---

### 1. Document Control & Metadata

| Attribute | Specification |
|---|---|
| **System Name** | NEXA Communication Ecosystem |
| **Document Classification** | Confidential / Master Technical Blueprint |
| **System Version** | 1.0 Production Architecture |
| **Document Authority** | `1architecture.md` (Highest Authority), `PRD.md`, `rules.md`, `decisions.md` |
| **Target Repository** | `H:\chat_app` (Android Client, Go Backend, Database Migrations, Protocol Definitions) |
| **Document Status** | Final Architecture Reference |
| **Primary Platforms** | Android (Kotlin, Jetpack Compose, Min SDK 24, Target SDK 35), Backend (Go 1.25, Docker) |
| **Operating Cost Profile** | **₹0 Operating Cost Model** (Zero Server Disk Storage, P2P Transfers, Ephemeral In-Memory Relays) |

---

### 2. High-Level Executive Overview

**NEXA** is an ultra-private, local-first, zero-cloud-storage, off-grid capable communication platform engineered to provide secure, resilient, and sovereign communication. Unlike conventional consumer messengers (such as WhatsApp, Telegram, or Signal) that rely on persistent cloud databases, central file storage servers, or phone numbers as immutable identities, NEXA establishes a fundamentally distinct paradigm:

1. **Local-First Cryptographic Vault:** Every message, contact, encryption session state, draft, reaction, and file metadata is stored strictly on the user's local device inside a 256-bit AES SQLCipher-encrypted Room database (`nexa_encrypted_vault.db`). No cloud database ever stores user messages or plaintext.
2. **The "Blind Post Office" Server Paradigm:** The central Go relay server (`backend/cmd/server/main.go`) operates exclusively as an ephemeral routing switch and blind postman. It retains zero chat history, zero message bodies on disk, and zero persistent plaintext logs. In-transit packets are held in ephemeral Redis RAM buffers with a strict 24-hour time-to-live (TTL) and are permanently purged upon receipt acknowledgment (ACK).
3. **Decoupled Identity Model (NX-ID):** Identity in NEXA is decoupled from cellular phone numbers. Every account is assigned an immutable 11-character identifier (`NX-XXXXXXXX`) cryptographically bound to an Ed25519 signing keypair and an X25519 Diffie-Hellman keypair. Phone numbers, when verified via Truecaller, Firebase Phone OTP, MSG91 SMS OTP, or Zero-Permission Reverse SMS, are protected via HMAC-SHA256 and AES-256-GCM.
4. **Universal Multi-Transport Architecture:** NEXA provides continuous communication across three distinct, tiered transport layers:
   - **Primary (Internet WebSocket):** Real-time full-duplex WebSocket connection (`ws://host:8080/ws`) with Ed25519 cryptographic challenge-response authentication.
   - **Secondary (Encrypted SMS Fallback / NX1 Protocol):** Off-grid cellular SMS transport utilizing compact binary Base91 encoding, Keystore-backed symmetric session keys, and multi-part fragmentation/reassembly for zero-data environments.
   - **Tertiary (DTN Mesh & P2P Swarm):** Delay-Tolerant Networking (DTN) and Mobile Ad-hoc Networking (MANET) over BLE L2CAP Connection-Oriented Channels (CoC) and Wi-Fi Aware (Neighbor Awareness Networking), employing store-carry-forward data mule mechanisms.
5. **Zero-Cloud Direct P2P File Transfer:** Media, audio, video, and documents are never permanently hosted on cloud storage. Direct peer-to-peer WebRTC DataChannels stream 64 KB encrypted chunks directly between sender and recipient, authenticated via token-bucket queues, thermal throttling monitors, and client-side dual-gate malware scanning.
6. **Zero-Knowledge Encrypted Google Drive Backup:** Database backups are packed into encrypted vault bundles using BIP-39 mnemonic passphrase-derived AES-GCM keys and uploaded exclusively to the user's private, hidden Google Drive `appDataFolder`. The server never touches or inspects backup archives.

---

### 3. Supreme Architectural Authority (`1architecture.md`)

According to the highest authority document of the project (`1architecture.md`), the core non-negotiable architectural mandates of NEXA are:

```
+---------------------------------------------------------------------------------------------------+
|                                     THE 1ARCHITECTURE MANDATE                                     |
|                                                                                                   |
|  1. SERVER MESSAGE DATABASE NAHI BANEGA:                                                          |
|     The server is strictly communication and delivery infrastructure. It is NOT a message DB.    |
|                                                                                                   |
|  2. ACTUAL CHATS/MESSAGES LOCALLY STORE HONGE:                                                    |
|     Every conversation and message history is stored exclusively on the user's physical device.   |
|                                                                                                   |
|  3. OFFLINE MESSAGE QUEUE PRIMARILY LOCAL:                                                        |
|     If recipient is offline, the message remains queued on sender's device or in short-lived RAM. |
|     Server never maintains permanent offline archives.                                            |
|                                                                                                   |
|  4. FILE SHARING WITHOUT PERMANENT SERVER STORAGE:                                                |
|     Files transfer directly P2P or via temporary ephemeral relay with immediate ACK purge.        |
|                                                                                                   |
|  5. GOOGLE DRIVE = BACKUP STORAGE ONLY:                                                           |
|     Google Drive is NOT a live messaging store; it is strictly an encrypted snapshot archive.     |
+---------------------------------------------------------------------------------------------------+
```

#### The Tri-Storage Model
NEXA categorizes data into three strictly segregated storage boundaries:

| Data Category | Storage Location | Cryptographic Protection | Retention / Lifetime |
|---|---|---|---|
| **Chats & Message History** | Local Device Only (`NexaDatabase`) | SQLCipher AES-256 (Keystore Passphrase) | Permanent until user deletion |
| **Pending Outgoing Queue** | Local Device Outbox | SQLCipher AES-256 | Retried until delivered or canceled |
| **In-Transit Packets** | Redis RAM Buffer (`queue.RedisBuffer`) | Double Ratchet ChaCha20-Poly1305 E2EE | Ephemeral (Max 24h TTL; ACK drop) |
| **Media / Large Files** | Local App Sandbox / Cache | ChaCha20-Poly1305 Chunk Cipher | Retained locally; P2P transferred |
| **Ephemeral Relay Media** | Cloudflare R2 Ephemeral Mailbox | AES-256-GCM Envelope | Ephemeral (7-day auto-purge; ACK drop) |
| **System Metadata / Routing** | PostgreSQL 15+ (`pgxpool`) | Row-Level Security (RLS) + bcrypt/HMAC | Managed lifecycle (Zero Plaintext) |
| **Backup Snapshots** | Google Drive `appDataFolder` | BIP-39 + Argon2id / AES-GCM | User-initiated or scheduled |

---

### 4. High-Level Macro System Architecture Diagram

```
+---------------------------------------------------------------------------------------------------+
|                                      NEXA ANDROID CLIENT (Kotlin)                                  |
|                                                                                                   |
|  [ Presentation Layer: Jetpack Compose + Material 3 + Liquid Glass ]                             |
|    - DashboardScreen, ChatScreen, ContactListScreen, StatusScreen, SettingsScreen, AppLockGate    |
|    - ViewModels with Unidirectional StateFlow (MVI/MVVM), Coroutines, LiveTransferBar             |
|                                                                                                   |
|  [ Core Engines & Security Domain ]                                                               |
|    - CryptoKeystoreManager (Android Keystore AES-256 Master Key Provider)                         |
|    - DoubleRatchetEngine (Signal-Style X25519 DH + HKDF-SHA256 + ChaCha20-Poly1305)               |
|    - GroupSenderKeyEngine (Multi-Party Tree-KDF Sender Keys)                                      |
|    - X3dhHandshakeEngine (Ed25519 Signing + One-Time Prekey Claim Verification)                   |
|    - DualScanEngine (Magic Byte Header Parser + 1.2 MB Bloom Filter + YaraNativeBridge JNI)       |
|    - VideoCompressionEngine (AndroidX Media3 Transformer Hardware HEVC / 720p 30fps)              |
|    - HardwareKeystoreVault & Bip39PassphraseHelper (12-Word Seed Derivation)                      |
|                                                                                                   |
|  [ Multi-Transport Abstraction Layer: TransportRouter & Universal Ingestion ]                   |
|    +-----------------------+-------------------------+-----------------------------------------+  |
|    | Primary: INTERNET     | Secondary: DATA_SMS     | Tertiary: DTN_MESH / P2P                |  |
|    | OkHttp WebSocket      | SmsManager Port 8901 /  | BLE L2CAP CoC (<=64KB)                  |  |
|    | Protobuf Wire Frame   | NX1 Base91 Encrypted    | Wi-Fi Aware / WebRTC P2P (>64KB)        |  |
|    +-----------------------+-------------------------+-----------------------------------------+  |
|                                                                                                   |
|  [ Local Encrypted Storage: NexaDatabase (Room v28 + Zetetic SQLCipher 4.6.1) ]                   |
|    - 27 Encrypted Entities: users, messages, chats, contacts, ratchet_states, skipped_keys,     |
|      transfers, dtn_envelopes, sms_sessions, sms_packets, status_updates, quarantine_vault     |
+---------------------------------------------------------------------------------------------------+
       |                                   |                                   |
       | Internet WebSocket (TLS 1.3)     | Cellular Port 8901 / SMS          | Google Drive REST API
       v                                   v                                   v
+-----------------------------+ +-----------------------------+ +-----------------------------+
|    GO BLIND-POSTMAN RELAY   | |     CELLULAR CARRIER SMS    | |   GOOGLE DRIVE CLOUD API    |
|       (Go 1.25 / Docker)    | |   (Port 8901 / SMS Intent;  | |   (Hidden appDataFolder;    |
|                             | |    Zero-permission NX1)     | |    Encrypted Vault Bundles) |
|  [ Port 8080: Core Hub ]    | +-----------------------------+ +-----------------------------+
|    - WebSocket Routing Hub  |                |
|    - Auth Handlers (Google, |                |
|      Firebase, MSG91, SMS)  |                |
|    - Prekey Directory & OPK |                |
|    - Connections & Blocks   |                |
|    - Ephemeral Media Relay  |                |
|                             |                |
|  [ Ephemeral Redis Queue ]  |                |
|    - 24h RAM Buffer         |                |
|    - Pub/Sub Multi-Node     |                |
|                             |                |
|  [ PostgreSQL Database ]    |                |
|    - User Identity & Devices|                |
|    - Prekeys & Metadata     |                |
|    - Row-Level Security     |                |
|    - ZERO PLAINTEXT CHATS   |                |
+-----------------------------+                |
       |                                       |
       +-------------------+-------------------+
                           |
                           v
        +-------------------------------------+
        |        RECIPIENT NEXA DEVICE        |
        +-------------------------------------+
```

---

### 5. Architectural Invariants & Non-Goals

To maintain rigorous security, privacy, and performance boundaries, NEXA enforces the following architectural invariants:

1. **Zero Plaintext Invariant:** No plaintext message body, voice note, image, video, or attachment may ever cross a network socket unencrypted. All messaging content is encrypted using end-to-end ChaCha20-Poly1305 with Double Ratchet forward secrecy.
2. **Zero Server Disk Invariant:** The backend relay services (`nexa-relay`, `nexa-swarm`, `nexa-signaling`) must never allocate persistent disk storage for chat content. If Redis is unavailable in production, the server fails closed rather than writing messages to disk.
3. **Decoupled Identity Invariant:** An NX-ID is an independent cryptographic identifier. It is never computed as a hash of the user's phone number or email, preventing rainbow-table deanonymization attacks.
4. **Non-Goals (What NEXA Is NOT):**
   - NEXA is **not** a public social network: there are no public follower counts, global feeds, or public profiles.
   - NEXA is **not** a cloud storage drive: files shared over NEXA are transferred peer-to-peer and reside only on sender and receiver hardware.
   - NEXA is **not** an open unauthenticated proxy: all WebSocket connections, signaling sessions, and prekey downloads require cryptographic authentication.
