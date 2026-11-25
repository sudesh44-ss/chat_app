# NEXA — Master System Architecture Blueprint
## 08. Complete System Data Flow & Sequence Diagrams

---

### 1. Architectural Diagram Index

This document provides complete, rigorous Mermaid technical diagrams modeling every primary data flow, state transition, and cryptographic exchange across the NEXA ecosystem:

1. **Diagram 1:** End-to-End User Registration & Cryptographic Onboarding Flow
2. **Diagram 2:** 1-to-1 Real-Time E2EE Messaging Flow (Online Recipient)
3. **Diagram 3:** 1-to-1 Real-Time E2EE Messaging Flow (Offline Recipient with Redis Buffer & FCM)
4. **Diagram 4:** Multi-Transport Fallback Routing Engine
5. **Diagram 5:** Module 3 Encrypted SMS Fallback (NX1 Protocol & Reassembly)
6. **Diagram 6:** Direct P2P File Transfer Signaling & WebRTC DataChannel Streaming
7. **Diagram 7:** Client-Side Dual-Gate Malware Scanning & Quarantine Pipeline
8. **Diagram 8:** Group Messaging Sender Key Exchange & Tree-KDF Distribution
9. **Diagram 9:** Ephemeral Status Updates Flow (Publishing & View Receipts)
10. **Diagram 10:** Encrypted Google Drive Backup & Exact-State Restoration Flow
11. **Diagram 11:** Existing User Login & Multi-Device Session Migration Flow
12. **Diagram 12:** WebSocket Ed25519 Challenge-Response Authentication Handshake
13. **Diagram 13:** Pairwise Rotating Routing Token Generation & Ingestion
14. **Diagram 14:** Complete Macro System-Wide Data Flow & Subsystem Interaction

---

### Diagram 1: End-to-End User Registration & Onboarding Flow

```mermaid
sequenceDiagram
    autonumber
    actor User as User (Android Client)
    participant Keystore as Android Keystore / TEE
    participant App as Nexa App Runtime
    participant Room as Room SQLCipher (v28)
    participant Relay as Go Relay (/api/v1/auth)
    participant PG as PostgreSQL (Supabase)

    User->>App: Launch App & Select Auth (Google / Phone)
    App->>Relay: POST /api/v1/auth/google/verify (Google ID Token)
    Relay->>Relay: Verify RSA/JWKS Signature & Extract Sub/Email
    Relay-->>App: Auth Ticket / Phone Verification Prompt
    User->>App: Complete Phone Verification (Truecaller / Firebase / SMS)
    App->>Relay: POST /api/v1/auth/otp/verify (Verification Code)
    Relay->>PG: Commit phone verification (HMAC hash)
    Relay-->>App: Phone Verified Successfully
    App->>Keystore: Generate AES-256 Master Key (`nexa_master_key`)
    App->>Keystore: Generate Ed25519 Identity Keypair
    App->>Keystore: Generate X25519 DH Keypair
    App->>App: Derive 12-Word BIP-39 Recovery Phrase
    App->>Relay: POST /api/v1/auth/register-profile (Public Keys, Push Token)
    Relay->>PG: Allocate Unique NX-ID (^NX-[A-Z0-9]{8}$) & Create Profile
    Relay-->>App: Profile Created (Assigned NX-ID: NX-7K9A2M4P)
    App->>App: Generate 100 Ephemeral OPKs + 1 Signed Prekey (SPK)
    App->>Relay: POST /api/v1/users/prekeys (Upload Prekey Bundle)
    Relay->>PG: Store SPK & One-Time Prekeys in public.prekeys
    App->>Room: Insert UserEntity into nexa_encrypted_vault.db
    App-->>User: Navigate to MainChatDashboardScreen
```

---

### Diagram 2: 1-to-1 Real-Time E2EE Messaging Flow (Online Recipient)

```mermaid
sequenceDiagram
    autonumber
    actor Alice as Alice (Sender)
    participant AliceVault as Alice Local Vault (Room)
    participant AliceEngine as Alice DoubleRatchetEngine
    participant Relay as Go Relay WebSocket Hub
    participant BobEngine as Bob DoubleRatchetEngine
    participant BobVault as Bob Local Vault (Room)
    actor Bob as Bob (Recipient)

    Alice->>AliceVault: Insert MessageEntity (status = PENDING)
    Alice->>AliceEngine: ratchetEncrypt(recipientNxId, plainText)
    AliceEngine->>AliceEngine: Advance KDF Chain (HKDF-SHA256)
    AliceEngine->>AliceEngine: Encrypt with ChaCha20-Poly1305 (AEAD)
    AliceEngine-->>Alice: EncryptedMessagePayload + DoubleRatchetHeader
    Alice->>Relay: WebSocket Send: PACKET_MESSAGE (NexaWirePacket Protobuf)
    Note over Relay: Blind Postman: Inspects recipient_nx_id only.<br/>Payload remains opaque ciphertext.
    Relay->>Bob: WebSocket Deliver: PACKET_MESSAGE (Direct RAM push)
    Bob->>BobEngine: ratchetDecrypt(senderNxId, payload)
    BobEngine->>BobEngine: Match DHR Ratchet Key & Advance Chain
    BobEngine->>BobEngine: Verify Poly1305 MAC & Decrypt ChaCha20
    BobEngine-->>Bob: Decrypted Plaintext
    Bob->>BobVault: Insert MessageEntity (status = DELIVERED)
    Bob-->>Bob: Show Incoming Notification / Render Bubble
    Bob->>Relay: WebSocket Send: PACKET_ACK (ACK_DEVICE_DELIVERED)
    Relay->>Alice: WebSocket Forward: PACKET_ACK
    Alice->>AliceVault: Update status = DELIVERED (Double Gray Tick)
    Alice-->>Alice: UI Updates Delivery Badge
```

---

### Diagram 3: 1-to-1 Real-Time E2EE Messaging Flow (Offline Recipient with Redis Buffer & FCM)

```mermaid
sequenceDiagram
    autonumber
    actor Alice as Alice (Sender)
    participant Relay as Go Relay Server
    participant Redis as Ephemeral Redis Buffer
    participant FCM as Firebase Cloud Messaging
    participant Bob as Bob (Offline Phone)
    participant BobVault as Bob Local Vault (Room)

    Alice->>Relay: WebSocket Send: PACKET_MESSAGE for Bob (Bob is Offline)
    Relay->>Relay: Check Active Sessions: Bob Not Connected
    Relay->>Redis: RPUSH nexa:queue:NX-BOB <serialized_protobuf>
    Relay->>Redis: EXPIRE nexa:queue:NX-BOB 86400 (24h TTL)
    Relay->>FCM: Send High-Priority Wake-Up Ping (zero message data)
    FCM->>Bob: Cellular Push Wake-Up Trigger
    Note over Bob: Background WorkManager wakes up.<br/>App establishes WSS connection to Relay.
    Bob->>Relay: Connect ws://host:8080/ws?nx_id=NX-BOB
    Relay->>Bob: PACKET_AUTH_CHALLENGE (32-byte nonce)
    Bob->>Relay: PACKET_AUTH_RESPONSE (Ed25519 signature)
    Relay->>Relay: Verify Signature -> Session Authenticated
    Relay->>Redis: LPOP nexa:queue:NX-BOB (Drain all buffered packets)
    Redis-->>Relay: Return Serialized Protobuf Packets
    Relay->>Bob: Forward Buffered PACKET_MESSAGE
    Bob->>BobVault: Decrypt & Insert MessageEntity
    Bob->>Relay: WebSocket Send: PACKET_ACK (ACK_DEVICE_DELIVERED)
    Note over Redis: Message was purged from RAM.<br/>Zero bytes retained on server.
```

---

### Diagram 4: Multi-Transport Fallback Routing Engine

```mermaid
flowchart TD
    Start(["User Taps Send Message"]) --> Outbox["Commit to Local Room Vault<br/>(status = PENDING)"]
    Outbox --> NetCheck{"NetworkMonitor:<br/>Internet Connected?"}

    NetCheck -- "YES" --> TryWS["Dispatch via Internet WebSocket<br/>(WSS Port 8080)"]
    TryWS --> WsAck{"Server Receipt ACK<br/>within 10s?"}
    WsAck -- "YES" --> Delivered["Mark Status = SENT / DELIVERED"]

    NetCheck -- "NO" --> PhoneCheck{"Contact Has Verified<br/>Phone Number?"}
    WsAck -- "NO (Timeout)" --> PhoneCheck

    PhoneCheck -- "YES" --> SmsCheck{"Module 3 Fallback:<br/>SMS Capable?"}
    SmsCheck -- "YES" --> TrySMS["Fragment into NX1 Base91 Packets<br/>Dispatch via Cellular SMS / Port 8901"]
    TrySMS --> SmsResult{"SMS Telephony<br/>Dispatched?"}
    SmsResult -- "YES" --> Delivered

    PhoneCheck -- "NO" --> TryMesh["Encapsulate into Authenticated<br/>DtnEnvelope"]
    SmsCheck -- "NO" --> TryMesh
    SmsResult -- "NO" --> TryMesh

    TryMesh --> MeshOutbox["Store in mesh_outbox Table<br/>Wait for BLE / Wi-Fi Aware Mules"]
    MeshOutbox --> MuleDetect{"Nearby NEXA Node<br/>Detected via BLE L2CAP?"}
    MuleDetect -- "YES" --> TransferMule["Custody Transfer to Mule Node<br/>(Store-Carry-Forward)"]
    MuleDetect -- "NO" --> Dwell["Exponential Backoff Sleep<br/>& Retry Upon Discovery"]
```

---

### Diagram 5: Module 3 Encrypted SMS Fallback (NX1 Protocol & Reassembly)

```mermaid
sequenceDiagram
    autonumber
    actor Alice as Alice (Sender)
    participant AssemblerA as Alice SmsMultipartAssembler
    participant SMS as Cellular Telephony Network
    participant AssemblerB as Bob SmsMultipartAssembler
    participant RoomB as Bob Room DB (sms_inbound_parts)
    actor Bob as Bob (Recipient)

    Alice->>Alice: Select Encrypted SMS Fallback Transport
    Alice->>Alice: Encrypt message via ChaCha20-Poly1305 using SMS Session Key
    Alice->>Alice: Prepend Magic Header (0x4E 0x58 0x31 -> "NX1")
    Alice->>Alice: Encode binary stream to Base91 string (Base91Codec)
    Alice->>AssemblerA: Fragment Base91 into 133-char SMS segments
    AssemblerA->>SMS: Send SMS Part 1/3: "NX1|A9X|1|3|..."
    AssemblerA->>SMS: Send SMS Part 2/3: "NX1|A9X|2|3|..."
    AssemblerA->>SMS: Send SMS Part 3/3: "NX1|A9X|3|3|..."
    SMS->>Bob: Inbound SMS Receiver triggers for each part
    Bob->>RoomB: Store each raw segment in sms_inbound_parts
    Bob->>AssemblerB: Query parts: check if count == totalParts
    AssemblerB->>RoomB: Fetch all 3 segments & reorder by part index
    AssemblerB->>AssemblerB: Concatenate segments into complete Base91 payload
    AssemblerB->>AssemblerB: Base91Codec.decode() -> Raw Encrypted Bytes
    AssemblerB->>AssemblerB: ChaCha20-Poly1305 Decrypt using SMS Session Key
    AssemblerB-->>Bob: Inject Decrypted Message into UniversalTransportIngest
    Bob->>Bob: Render Message Bubble in ChatScreen
```

---

### Diagram 6: Direct P2P File Transfer Signaling & WebRTC DataChannel Streaming

```mermaid
sequenceDiagram
    autonumber
    actor Sender as Sender Device
    participant ScanS as Sender DualScanEngine
    participant WSS as Go Signaling Hub (/ws/signaling)
    actor Receiver as Receiver Device
    participant ScanR as Receiver DualScanEngine
    participant VaultR as Receiver Local Storage

    Sender->>ScanS: Inspect File Headers (Magic Bytes) & Compute SHA-256
    ScanS-->>Sender: File Clean -> Initiate Transfer Offer
    Sender->>WSS: Send Signaling JSON: {"action": "offer", "file": "video.mp4", "size": 45000000}
    WSS->>Receiver: Forward Offer Event to Receiver
    Receiver-->>Receiver: Display IncomingTransferDialog
    Receiver->>WSS: Send Signaling JSON: {"action": "accept"}
    Sender->>WSS: Relay WebRTC SDP Offer + ICE Candidates
    WSS->>Receiver: Forward SDP Offer + ICE Candidates
    Receiver->>WSS: Relay WebRTC SDP Answer + ICE Candidates
    WSS->>Sender: Forward SDP Answer + ICE Candidates
    Note over Sender,Receiver: Direct P2P Connection Established (STUN/TURN Traversed)<br/>DTLS 1.2 Handshake Complete over UDP
    Sender->>Receiver: Open SCTP DataChannel: "nexa-p2p-transfer"
    loop For Every 64 KB Chunk
        Sender->>Sender: Lease 64 KB buffer from MemoryBufferPool
        Sender->>Sender: Encrypt chunk with ChaCha20-Poly1305 (ChunkCipherEngine)
        Sender->>Receiver: Stream Binary Encrypted Chunk across DataChannel
        Receiver->>Receiver: Validate chunk MAC & update BitfieldTracker
        Sender->>Sender: ThermalThrottlingMonitor check (apply delay if hot)
    end
    Receiver->>ScanR: Reassemble Complete File in App Sandbox
    ScanR->>ScanR: Gate 1 (Magic Bytes) + Gate 2 (Bloom Filter) + Gate 3 (Native YARA)
    ScanR-->>Receiver: File Declared Clean
    Receiver->>VaultR: Move File to User Downloads / Nexa Media Folder
    Receiver->>Sender: Send Transfer Complete ACK
```

---

### Diagram 7: Client-Side Dual-Gate Malware Scanning & Quarantine Pipeline

```mermaid
flowchart TD
    InFile["Incoming File Stream (P2P / Mesh / Cloud)"] --> Gate1["GATE 1: MagicByteInspector<br/>Read First 16 Bytes"]
    Gate1 --> MimeCheck{"Header Matches<br/>Declared MIME Extension?"}

    MimeCheck -- "NO (Spoofed Executable)" --> Quarantined["DIVERT TO QUARANTINE VAULT<br/>1. Encrypt with Keystore AES Key<br/>2. Store in quarantine_vault DB<br/>3. Alert User of Blocked Threat"]
    MimeCheck -- "YES" --> Gate2["GATE 2: ThreatBloomFilter<br/>Compute SHA-256 Hash"]

    Gate2 --> BloomCheck{"Hash Matches<br/>1.2 MB In-Memory Filter?"}
    BloomCheck -- "MATCH (Known Malware)" --> Quarantined
    BloomCheck -- "NO MATCH" --> Gate3["GATE 3: YaraNativeBridge (JNI)<br/>Evaluate Compiled Binary Rules"]

    Gate3 --> YaraCheck{"YARA Pattern<br/>Triggered?"}
    YaraCheck -- "YES (Malicious Signature)" --> Quarantined
    YaraCheck -- "NO (Clean)" --> Released["RELEASE FILE TO USER<br/>1. Save to Device Storage<br/>2. Record in file_metadata<br/>3. Render in ChatScreen"]
```

---

### Diagram 8: Group Messaging Sender Key Exchange & Message Distribution

```mermaid
sequenceDiagram
    autonumber
    actor Alice as Alice (Group Creator)
    participant GroupEngine as Alice GroupSenderKeyEngine
    participant AliceVault as Alice Local Vault
    participant WSS as Go Relay Server
    actor Bob as Bob (Group Member)
    actor Charlie as Charlie (Group Member)

    Alice->>AliceVault: Create Group "Engineering Core" (members: Bob, Charlie)
    Alice->>GroupEngine: Generate Random 32-byte Sender Key Seed & Signing Key
    Note over Alice: Pairwise Distribution Phase:<br/>Distribute Sender Key via 1-to-1 Double Ratchet
    Alice->>WSS: Send 1-to-1 E2EE Packet to Bob (Encapsulated Sender Key)
    Alice->>WSS: Send 1-to-1 E2EE Packet to Charlie (Encapsulated Sender Key)
    WSS->>Bob: Forward Sender Key Packet to Bob
    WSS->>Charlie: Forward Sender Key Packet to Charlie
    Bob->>Bob: Store Alice's Sender Key Chain in Local Vault
    Charlie->>Charlie: Store Alice's Sender Key Chain in Local Vault
    Note over Alice: Group Broadcast Phase:<br/>Tree-KDF Symmetric Advance
    Alice->>GroupEngine: Advance Sender Key Chain (HKDF-SHA256) -> Message Key
    Alice->>GroupEngine: Encrypt message text via ChaCha20-Poly1305
    Alice->>WSS: Broadcast PACKET_MESSAGE with group_id = "GRP-ENG-01"
    WSS->>Bob: Deliver Group Message Packet
    WSS->>Charlie: Deliver Group Message Packet
    Bob->>Bob: Advance Alice's stored Chain Key & Decrypt Message
    Charlie->>Charlie: Advance Alice's stored Chain Key & Decrypt Message
```

---

### Diagram 9: Ephemeral Status Updates Flow (Publishing & View Receipts)

```mermaid
sequenceDiagram
    autonumber
    actor Author as Status Author
    participant LocalDB as Author Local Vault
    participant Relay as Go Relay Hub
    actor Viewer as Status Viewer
    participant ViewerDB as Viewer Local Vault

    Author->>Author: Record Video / Capture Photo in StatusScreen
    Author->>Author: Sanitize EXIF & Transcode via Media3 Transformer
    Author->>LocalDB: Insert StatusUpdateEntity (expiresAt = now + 24h)
    Author->>Relay: Broadcast Ephemeral Status Metadata Packet to Contacts
    Relay->>Viewer: Push Status Notification Packet
    Viewer->>Viewer: Open NexaSequentialStoryViewer
    Viewer->>Author: Direct P2P Stream / Download Ephemeral Status Media
    Viewer->>ViewerDB: Cache Story Media & Record Local View
    Viewer->>Relay: Send Authenticated View Receipt Packet
    Relay->>Author: Deliver View Receipt Packet
    Author->>LocalDB: Insert Record into status_views Table
    Author->>Author: Author views Viewer list in StatusViewersSheet
    Note over Author,ViewerDB: 24 Hours Elapses:<br/>Background WorkManager executes cleanup:<br/>DELETE FROM status_updates WHERE expiresAt < now;<br/>Media files securely scrubbed from storage.
```

---

### Diagram 10: Encrypted Google Drive Backup & Exact-State Restoration Flow

```mermaid
sequenceDiagram
    autonumber
    actor User as User Device
    participant DB as Room Database (NexaDatabase)
    participant Crypto as VaultStreamEncryptor
    participant Drive as Google Drive (appDataFolder)

    User->>User: Initiate Encrypted Backup
    User->>User: Derive 256-bit Key from 12-Word BIP-39 Seed (PBKDF2/Argon2id)
    User->>DB: Execute VACUUM INTO 'snapshot_temp.db' (Atomic Point-in-Time)
    DB-->>User: Consistent Uncorrupted SQLite Snapshot
    User->>Crypto: Bundle Snapshot DB + Media Attachments
    Crypto->>Crypto: Compress Bundle via DEFLATE (BackupCompressor)
    Crypto->>Crypto: Streaming AES-256-GCM Encryption (64 KB Chunks)
    Crypto->>Crypto: Prepend 32-byte Vault Header (Version, Salt, IV)
    User->>Drive: DriveResumableUploader: Resumable Upload in 2 MB Chunks
    Drive-->>User: Upload Complete: `nexa_backup_v1_1730000000.vault`
    Note over User,Drive: RESTORATION PHASE (New Device / Fresh Install)
    User->>User: Input 12-Word BIP-39 Recovery Mnemonic
    User->>Drive: Query appDataFolder for latest .vault file
    Drive-->>User: Stream Down Encrypted Vault Archive
    User->>Crypto: Verify Header Salt, IV & AES-GCM MAC Tags
    Crypto->>User: Stream Decrypted Database to live storage
    User->>DB: checkAndRecoverFromRollbackJournal() (Integrity Verification)
    DB-->>User: Open NexaDatabase -> Exact Historical State Fully Restored
```

---

### Diagram 11: Existing User Login & Multi-Device Session Migration Flow

```mermaid
sequenceDiagram
    autonumber
    actor User as Returning User (New Device)
    participant NewApp as New Device App Runtime
    participant Relay as Go Relay Server
    participant PG as PostgreSQL (Supabase)
    participant OldDevice as Old Device (Still Active)

    User->>NewApp: Launch App & Select "Returning User Login"
    User->>NewApp: Authenticate via Google Sign-In or Phone OTP
    NewApp->>Relay: POST /api/v1/auth/returning-user/challenge (Phone Hash / Email)
    Relay->>PG: Query Profile: Find existing NX-ID (NX-8M2K4P1X)
    Relay-->>NewApp: Issue Cryptographic Challenge + Device Migration Required
    User->>NewApp: Enter 12-Word BIP-39 Master Recovery Seed
    NewApp->>NewApp: Derive Ed25519 Identity Private Key from Seed
    NewApp->>NewApp: Sign Server Challenge Nonce
    NewApp->>Relay: POST /api/v1/auth/returning-user/verify (Signature + New Device ID)
    Relay->>Relay: Verify Ed25519 Signature against registered Identity Key
    Relay->>PG: Mark old device sessions as is_revoked = TRUE
    Relay->>PG: Register New Device ID in public.devices
    Relay->>OldDevice: Push Session Revocation Signal (Forced Logout)
    OldDevice->>OldDevice: Scrub cached session tokens & return to WelcomeScreen
    Relay-->>NewApp: Issue New Active Session Token + Return Historical Profile
    NewApp->>NewApp: Restore Profile & Initiate Drive Backup Restoration
```

---

### Diagram 12: WebSocket Ed25519 Challenge-Response Authentication Handshake

```mermaid
sequenceDiagram
    autonumber
    participant Client as Android Client (WebSocketManager)
    participant Keystore as Android Keystore / TEE
    participant Server as Go Relay (ClientSession)

    Client->>Server: HTTP GET /ws?nx_id=NX-7K9A2M4P&device_id=DEV-01 (Upgrade Request)
    Server-->>Client: HTTP 101 Switching Protocols (Connection Upgraded)
    Server->>Server: Generate 32-byte Cryptographically Secure Nonce
    Server->>Server: Record Session Challenge in Redis (TTL = 60s)
    Server->>Client: Send PACKET_AUTH_CHALLENGE (Nonce, Timestamp, ExpiresAt)
    Client->>Keystore: Sign 32-byte Nonce using Hardware Ed25519 Private Key
    Keystore-->>Client: 64-byte Ed25519 Digital Signature
    Client->>Server: Send PACKET_AUTH_RESPONSE (Signature, 32-byte Public Key)
    Server->>Server: crypto/ed25519.Verify(publicKey, nonce, signature)
    alt Signature Valid & Timestamp within 60s
        Server->>Server: Bind Session: Authenticated = TRUE
        Server->>Client: Send PACKET_AUTH_SUCCESS
        Note over Client,Server: WebSocket is now Authenticated.<br/>Message Routing Pump Activated.
    else Signature Invalid or Expired
        Server->>Client: Send PACKET_AUTH_FAILURE
        Server->>Client: Close WebSocket (Code 4001: Unauthorized)
    end
```

---

### Diagram 13: Pairwise Rotating Routing Token Generation & Ingestion

```mermaid
sequenceDiagram
    autonumber
    actor Alice as Alice (Client)
    participant ContactDB as Alice Contact Dao
    participant Crypto as PairwiseRoutingTokenGenerator
    participant Relay as Go Relay WebSocket Hub
    actor Bob as Bob (Peer)

    Note over Alice: At every epoch window (e.g. 24 hours):
    Alice->>ContactDB: Query all verified contacts (Bob, Charlie)
    loop For Each Contact
        Alice->>Crypto: Derive pairwise DH Secret: X25519(Priv_Alice, Pub_Bob)
        Crypto->>Crypto: Derive RoutingKey: HKDF(secret, "NexaRoutingToken")
        Crypto->>Crypto: Generate Inbound Token: HMAC-SHA256(RoutingKey, Epoch)
    end
    Alice->>Relay: Send PACKET_REGISTER_ROUTING_TOKENS (List of Token Hashes)
    Relay->>Relay: Store Tokens in Redis Set: SADD nexa:tokens <tokens>
    Note over Bob: When Bob sends an asynchronous DTN envelope to Alice:
    Bob->>Crypto: Derive identical Inbound Token for Alice using Alice's Public Key
    Bob->>Bob: Embed Token into DtnEnvelope header (Blind Token)
    Bob->>Relay: Opportunistic dump of DtnEnvelope to Relay
    Relay->>Relay: Match Envelope Blind Token against active Token Set
    Relay-->>Alice: Forward Envelope to Alice without knowing Alice's Identity
```

---

### Diagram 14: Complete System-Wide Macro Data Flow & Subsystem Interaction

```mermaid
flowchart TD
    subgraph ClientA ["Alice (Android Client A)"]
        UI_A["Presentation UI<br/>(Jetpack Compose)"]
        VM_A["ViewModels & StateFlow"]
        Repo_A["ChatRepositoryImpl & TransportRouter"]
        Crypto_A["DoubleRatchetEngine & Keystore"]
        DB_A[("Room SQLCipher<br/>nexa_encrypted_vault.db")]
        WebRTC_A["WebRtcClient & P2P Streamer"]
        SMS_A["Module 3 SmsFallbackRepo"]
    end

    subgraph Cloud ["NEXA Cloud & Relay Infrastructure"]
        RelayCore["Go Relay Master Server (Port 8080)"]
        WSS_Hub["WebSocket Hub & Session Map"]
        Auth_API["REST Auth & Identity Gateways"]
        Prekey_Dir["X3DH Prekey Directory"]
        Redis_RAM[("Redis Ephemeral Buffer<br/>24h RAM Queue & Pub/Sub")]
        PG_DB[("PostgreSQL 15+ DB<br/>RLS Metadata & Identity")]
        FCM_Svc["Firebase Cloud Messaging (FCM)"]
    end

    subgraph External ["External Infrastructure"]
        Telco["Cellular Carrier SMS Network"]
        GDrive[("Google Drive appDataFolder<br/>Zero-Knowledge Backups")]
        R2_Store[("Cloudflare R2<br/>Ephemeral Media Mailbox")]
    end

    subgraph ClientB ["Bob (Android Client B)"]
        RelayB["NexaUniversalTransportIngest"]
        Crypto_B["DoubleRatchetEngine & Keystore"]
        DB_B[("Room SQLCipher<br/>nexa_encrypted_vault.db")]
        UI_B["Presentation UI<br/>(Jetpack Compose)"]
    end

    %% Client A Internal Connections
    UI_A <--> VM_A
    VM_A <--> Repo_A
    Repo_A <--> Crypto_A
    Repo_A <--> DB_A
    Repo_A <--> WebRTC_A
    Repo_A <--> SMS_A

    %% Network Dispatches from A
    Repo_A -- "WSS Protobuf" --> WSS_Hub
    VM_A -- "REST Auth" --> Auth_API
    Crypto_A -- "Claim OPK" --> Prekey_Dir
    SMS_A -- "NX1 Base91 SMS" --> Telco
    DB_A -. "Encrypted Snapshot" .-> GDrive
    WebRTC_A -. "Direct SCTP Chunks" .-> ClientB
    WebRTC_A -- "Media Fallback" --> R2_Store

    %% Backend Internal Interactions
    WSS_Hub <--> Redis_RAM
    WSS_Hub <--> PG_DB
    Auth_API <--> PG_DB
    Prekey_Dir <--> PG_DB
    WSS_Hub -- "Offline Wakeup" --> FCM_Svc

    %% Deliveries to Client B
    WSS_Hub -- "WSS Deliver" --> RelayB
    FCM_Svc -. "Background Ping" .-> ClientB
    Telco -- "Inbound SMS" --> RelayB
    RelayB <--> Crypto_B
    RelayB <--> DB_B
    DB_B <--> UI_B
```
