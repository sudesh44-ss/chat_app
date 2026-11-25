# NEXA — Master System Architecture Blueprint
## 05. Core Feature Implementation & Execution Workflows

---

### 1. User Onboarding, Identity Creation & Verification Workflow

The onboarding workflow establishes the user's cryptographic identity, generates the immutable NX-ID, and verifies ownership of an associated phone number without compromising privacy:

```
Step 1: Credential Selection
  - User chooses between Google Sign-In (Credential Manager API), Truecaller instant verification,
    or manual phone verification.
  - If Google Sign-In: Android client acquires a signed Google ID Token (JWT) from Play Services.

Step 2: Backend Authentication & Token Verification
  - Client dispatches Google ID Token to POST /api/v1/auth/google/verify.
  - Backend verifies Google public keys (RSA/JWKS), extracts user email and subject ID.

Step 3: Phone Number Verification (Choice of 4 Pipelines)
  - Pipeline A (Truecaller): Client calls Truecaller SDK; backend verifies partner verification token.
  - Pipeline B (Firebase OTP): Firebase sends SMS; user enters 6-digit code; backend validates Firebase ID token.
  - Pipeline C (MSG91 SMS OTP): Backend generates secure 6-digit OTP, peppers state in Redis, dispatches SMS.
  - Pipeline D (Reverse SMS - Zero Permission): Client fetches challenge code from /api/v1/auth/initiate-reverse-sms,
    launches default SMS app via intent; carrier webhook verifies inbound SMS sender.

Step 4: Cryptographic Key Generation (On-Device)
  - CryptoKeystoreManager generates an Ed25519 identity keypair (for signing and authentication).
  - CryptoKeystoreManager generates an X25519 identity keypair (for Diffie-Hellman handshakes).
  - Both private keys are encrypted using the hardware-backed Android KeyStore AES-256 master key.
  - Client derives a 12-word BIP-39 recovery mnemonic passphrase for vault backup protection.

Step 5: Immutable NX-ID Allocation & Prekey Publishing
  - Backend generates an immutable 11-character identifier matching ^NX-[A-Z0-9]{8}$ (e.g., NX-7K9A2M4P).
  - Client generates 100 ephemeral X25519 One-Time Prekeys (OPKs) and one Ed25519-signed Prekey (SPK).
  - Client uploads prekey bundle to /api/v1/users/prekeys/signed and /api/v1/users/prekeys/one-time.
  - Profile is saved to local NexaDatabase (UserEntity) and PostgreSQL public.profiles.
```

---

### 2. 1-to-1 Real-Time Text Messaging Workflow (Internet WebSocket)

This workflow describes the complete lifecycle of an end-to-end encrypted message dispatched over the primary internet transport:

```
Step 1: Outbox Commit & Local Persistence (Outbox Pattern)
  - User types message in ChatScreen and taps Send.
  - ChatRepositoryImpl generates a unique packetId (UUIDv4) and timestamp.
  - Message is immediately inserted into local SQLCipher NexaDatabase (MessageEntity) with status = PENDING.
  - UI observes StateFlow and renders message bubble with a clock icon (pending indicator).

Step 2: Cryptographic Session Resolution & Double Ratchet Encryption
  - ChatRepositoryImpl inspects local ratchet_states table for an existing session with recipientNxId.
  - IF NO SESSION EXISTS (First Message):
      a. Client fetches recipient's prekey bundle from /api/v1/prekeys/claim (retrieves IK, SPK, OPK).
      b. X3dhHandshakeEngine executes the X3DH key exchange, deriving the initial Root Key (SK).
      c. DoubleRatchetEngine initializes the session, generating the first message key.
  - IF SESSION EXISTS:
      a. DoubleRatchetEngine advances the symmetric Sending Chain (HKDF-SHA256).
      b. Encrypts message plaintext with ChaCha20-Poly1305 using the derived message key and 12-byte AEAD nonce.
      c. Generates DoubleRatchetHeader containing sender's current ratchet public key (DHR), chain length (PN),
         and message index (N).

Step 3: Protobuf Wire Frame Assembly
  - Assembles NexaWirePacket with type = PACKET_MESSAGE, packet_id, sender_nx_id, recipient_nx_id,
    and EncryptedMessagePayload.
  - Encodes packet into binary Protobuf bytes.

Step 4: WebSocket Transmission & Ephemeral Routing
  - WebSocketManager transmits binary frame across ws://host:8080/ws.
  - Server reads packet header in connection.ClientSession.ReadPump().
  - IF RECIPIENT IS ONLINE:
      a. Server immediately writes frame to recipient's write channel.
      b. Recipient receives packet, NexaUniversalTransportIngest decrypts payload, verifies MAC tag.
      c. Recipient saves message to local SQLCipher vault and sends back PACKET_ACK (status = ACK_DEVICE_DELIVERED).
      d. Server forwards PACKET_ACK to sender; sender updates local message status to DELIVERED (double gray check).
  - IF RECIPIENT IS OFFLINE:
      a. Server executes RPUSH nexa:queue:NX-RECIPIENT <protobuf_bytes> in Redis (24h TTL).
      b. Server dispatches FCM background wake-up ping via fcm.FcmService.
      c. When recipient comes online, server flushes queue, and delivery ACK is returned to sender.
```

---

### 3. Multi-Transport Message Routing & Automatic Fallback Workflow

When internet connectivity fluctuates, `TransportRouter` coordinates automatic failover between available physical transports:

```
                      +------------------------------------------+
                      | User Sends Message (ChatRepositoryImpl)  |
                      +------------------------------------------+
                                           |
                                           v
                             [NetworkMonitor.isOnline?]
                               /                      \
                             YES                       NO
                             /                          \
                            v                            v
               +-----------------------+     [Contact Has Verified Phone?]
               | Route via INTERNET_WS |      /                         \
               | (WebSocket Port 8080) |    YES                          NO
               +-----------------------+    /                             \
                          |                v                               v
                     Success?    +-------------------+           +-------------------+
                      /     \    | Route via DATA_SMS|           | Route via DTN_MESH|
                    YES      NO  | (Module 3 - NX1)  |           | (BLE L2CAP / NAN) |
                    /         \  +-------------------+           +-------------------+
                   v           \           |                               |
              [Message          +--------> v                               v
              DELIVERED]              Success?                       [Store in Mesh
                                       /      \                      Outbox / Wait
                                     YES       NO                    for Peer Mule]
                                     /          \                          |
                                    v            +------------------------>+
                               [DELIVERED]
```

1. **Phase 1 (Primary — Internet WebSocket):**
   - Packets route over `INTERNET_WS`. If the TCP socket drops or times out after 10 seconds without server receipt ACK, the transport engine activates fallback evaluation.
2. **Phase 2 (Secondary — Encrypted SMS Fallback / NX1 Protocol):**
   - If the recipient's phone number is verified and accessible, `SmsFallbackRepositoryImpl` fragments the encrypted packet into Base91 SMS segments.
   - Dispatches via cellular SMS without requiring data connectivity.
3. **Phase 3 (Tertiary — Delay-Tolerant Mesh / DTN):**
   - If cellular service is absent, the packet is encapsulated into an authenticated `DtnEnvelope` and inserted into the `mesh_outbox` table.
   - When the device encounters nearby NEXA nodes via BLE or Wi-Fi Aware, the packet is opportunistically transferred to intermediate routing mules.

---

### 4. Module 3: Encrypted SMS Fallback & Smart SMS Engine Workflow

Module 3 enables zero-data communication by encapsulating Double Ratchet encrypted text into cellular SMS:

```
Step 1: Session Key Provisioning (Out-of-Band Control Channel)
  - During prior internet connectivity, peers negotiate an independent 256-bit symmetric SMS session key.
  - Key material is encrypted with the Keystore master key and stored in sms_key_material table.

Step 2: Compression & Base91 Wire Frame Packaging
  - Plaintext is encrypted with ChaCha20-Poly1305 using the dedicated SMS key.
  - Output binary is prefixed with the NX1 protocol magic header: 0x4E 0x58 0x31 (ASCII "NX1").
  - The binary stream is encoded into text using Base91Codec.kt (1.23x expansion factor).

Step 3: Multi-Part Fragmentation & SMS Transmission
  - If the Base91 string exceeds the 133-character single-SMS payload ceiling, SmsMultipartAssembler
    splits the string into indexed chunks (e.g., [1/3], [2/3], [3/3]).
  - Uses SmsComposerLauncher to hand off segments to the cellular telephony subsystem or Data SMS (port 8901).

Step 4: Recipient Reassembly & Decryption
  - Inbound SMS broadcast receiver catches parts matching the NX1 signature.
  - Parts are cached in the sms_inbound_parts Room table.
  - Once partCount == totalParts, SmsMultipartAssembler reassembles the complete Base91 payload.
  - Base91Codec decodes the bytes; ChaCha20-Poly1305 decrypts the payload; message is injected
    into NexaUniversalTransportIngest and rendered in chat.
```

---

### 5. Direct P2P File Transfer Workflow (WebRTC DataChannels)

To enforce the Zero-Cloud Storage invariant ($0 operating cost), files are transferred directly between peer devices:

```
Step 1: File Selection & Pre-Share Processing
  - User selects file/video in ChatScreen.
  - ExifStripper.kt removes GPS coordinates and camera metadata from image attachments.
  - VideoCompressionEngine.kt compresses video via Media3 Transformer to HEVC 720p 30fps.
  - DualScanEngine inspects file headers (Magic Byte Inspector) and calculates SHA-256 hash.

Step 2: Transfer Invitation & WebRTC Signaling
  - Sender generates a transfer session and sends an offer payload across the WebSocket signaling channel (/ws/signaling).
  - Recipient receives IncomingTransferDialog displaying file name, size, and sender thumbnail.
  - Recipient accepts transfer.
  - SignalingHub relays WebRTC SDP Offer, SDP Answer, and ICE Candidates between peers.

Step 3: Direct SCTP DataChannel Streaming
  - WebRtcClient establishes an end-to-end encrypted direct P2P DataChannel (SCTP over DTLS/UDP).
  - ChunkCipherEngine splits file into 64 KB binary chunks, encrypting each with ChaCha20-Poly1305.
  - Sender leases byte buffers from MemoryBufferPool to prevent memory allocation churn.
  - Chunks stream across the DataChannel, tracked on both sides via TransferSessionEntity sparse BitSets.

Step 4: Thermal Monitoring & Rate Throttling
  - ThermalThrottlingMonitor actively reads device thermal status.
  - If device temperature triggers THERMAL_STATUS_MODERATE, the sender introduces a 15ms inter-chunk delay,
    reducing CPU load and preventing hardware thermal shutdown.

Step 5: Client-Side Dual-Gate Security Scan
  - Recipient receives all chunks and reassembles the binary file in the app sandbox.
  - DualScanEngine executes Gate 1 (Magic Bytes), Gate 2 (1.2 MB Bloom Filter), and native YARA rules.
  - IF CLEAN: File is released to user storage and displayed in chat.
  - IF THREAT DETECTED: File is diverted into QuarantineSandboxSink, encrypted, and locked in quarantine_vault.
```

---

### 6. Ephemeral Status Updates (Stories) Workflow

NEXA provides 24-hour ephemeral multimedia status stories:

```
Step 1: Status Publishing
  - User captures or selects photo/video in StatusScreen.
  - MediaSanitizer strips metadata; media is compressed locally.
  - Status record is committed to local status_updates table with expiresAtTimestamp = now + 24 hours.

Step 2: Peer Distribution
  - Status metadata is broadcast to accepted contacts via WebSocket or DTN mesh as an ephemeral payload.
  - Contacts download the media directly via P2P DataChannel or temporary ephemeral Cloudflare R2 link.

Step 3: Viewing & Read Receipt Tracking
  - When a peer views the story in NexaSequentialStoryViewer, an authenticated view receipt is generated.
  - View receipt routes back to the author, updating the local status_views table.
  - Author views audience list in StatusViewersSheet.

Step 4: 24-Hour Automated Expiration
  - Background WorkManager job runs periodic cleanup queries:
    DELETE FROM status_updates WHERE expiresAtTimestamp < strftime('%s', 'now') * 1000;
  - Associated media files in the local cache are permanently deleted from device storage.
```

---

### 7. Encrypted Google Drive Backup & Exact-State Restoration Workflow

Provides zero-risk, zero-knowledge backup and exact-state restoration:

```
Step 1: User Initiation & Passphrase Derivation
  - User initiates backup from Settings -> Chats -> Backup.
  - System derives a 256-bit AES master backup key from the user's 12-word BIP-39 mnemonic seed
    using Argon2id / PBKDF2-HMAC-SHA512 (100,000 iterations).

Step 2: Atomic Zero-Lock Database Snapshotting
  - NexaDatabase.getRawDatabase() executes VACUUM INTO 'snapshot_temp.db'.
  - Creates a consistent, uncorrupted SQLite snapshot without taking locks or interrupting live messaging.

Step 3: Streaming Encryption & Bundle Packaging
  - VaultBundlePackager bundles the snapshot DB and selected media directories.
  - BackupCompressor compresses bundle using DEFLATE.
  - VaultStreamEncryptor encrypts the bundle in 64 KB streams using AES-256-GCM.
  - Prepends BackupVaultHeader containing format version, salt, and IV.

Step 4: Resumable Upload to Google Drive appDataFolder
  - GoogleDriveAuthManager acquires OAuth 2.0 token with https://www.googleapis.com/auth/drive.appdata scope.
  - DriveResumableUploader uploads the encrypted file to the hidden appDataFolder in 2 MB chunks.

Step 5: Exact-State Restoration & Crash Recovery
  - On reinstall or device migration, user inputs 12-word BIP-39 mnemonic phrase.
  - App queries Drive appDataFolder, downloads latest .vault bundle, verifies header and MAC tags.
  - Streams decrypted database directly into place.
  - NexaDatabase.checkAndRecoverFromRollbackJournal() verifies WAL journal integrity before opening vault.
```
