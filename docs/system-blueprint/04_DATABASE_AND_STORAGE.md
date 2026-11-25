# NEXA — Master System Architecture Blueprint
## 04. Database Schemas, Storage Architecture & Data Isolation

---

### 1. Storage Architecture Overview

NEXA implements a strictly segregated, privacy-enforced **Tri-Tier Storage Model**:

```
+---------------------------------------------------------------------------------------------------+
|                                  NEXA TRI-TIER STORAGE ARCHITECTURE                              |
|                                                                                                   |
|  TIER 1: ON-DEVICE CRYPTOGRAPHIC VAULT (Local Storage)                                            |
|    - Engine: Room Database (v28) + Zetetic SQLCipher 4.6.1 (256-bit AES page encryption)          |
|    - File: `nexa_encrypted_vault.db`                                                              |
|    - Contains: All 27 local entities (Chats, Messages, Contacts, Ratchet Keys, Media Metadata)    |
|                                                                                                   |
|  TIER 2: EPHEMERAL CLOUD RELAY & ROUTING DIRECTORY (Central Infrastructure)                       |
|    - Engine: PostgreSQL 15+ (`pgxpool`) + Redis 7.x In-Memory Buffer                               |
|    - Scope: Identity registry, Prekey directory, Group membership metadata, 24-hour RAM buffer    |
|    - CRITICAL INVARIANT: ZERO PLAINTEXT CHATS, ATTACHMENTS, OR HISTORICAL MESSAGES ON SERVER DISK  |
|                                                                                                   |
|  TIER 3: ZERO-KNOWLEDGE ENCRYPTED BACKUP STORAGE (User Cloud Vault)                              |
|    - Engine: Google Drive REST API (`appDataFolder`)                                              |
|    - Scope: Complete encrypted SQLCipher database snapshots and user-selected media bundles       |
|    - Encryption: BIP-39 mnemonic passphrase-derived AES-256-GCM streaming envelope               |
+---------------------------------------------------------------------------------------------------+
```

---

### 2. On-Device SQLCipher Database (`nexa_encrypted_vault.db`)

- **Database Class:** `com.nexa.app.data.local.NexaDatabase`
- **Current Version:** **Version 28**
- **Underlying SQLite Engine:** Zetetic SQLCipher Android 4.6.1 (16 KB page-size compatible)
- **Key Derivation:** The database encryption passphrase is generated as a 32-byte cryptographically secure random byte array, encrypted via the Android Keystore Master Key (`CryptoKeystoreManager`), and stored inside `EncryptedSharedPreferences` (`SecureStorage`).

#### Exhaustive Inventory of All 27 Room Entities

| Entity / Table Name | Entity Class Location | Primary Key | Key Columns & Indexes | Functional Purpose in System |
|---|---|---|---|---|
| `users` | `entity/UserEntity.kt` | `nxId` (String) | `publicKeyHex`, `phoneNumber`, `fullName`, `isVerified` | Stores the active local user profile, cryptographic keys, and settings. |
| `messages` | `entity/MessageEntity.kt` | `packetId` (String) | `conversationId`, `timestamp`, `status`, `senderNxId`, `localSequence` | Stores all 1-to-1 and group messages, statuses (PENDING, SENT, DELIVERED, READ, FAILED), replies, reactions. |
| `chats` | `entity/ChatEntity.kt` | `conversationId` (String) | `participantNxId`, `lastMessageTimestamp`, `unreadCount`, `isPinned`, `isArchived` | Conversation summary list observed by Dashboard chat tabs. |
| `contacts` | `entity/ContactEntity.kt` | `nxId` (String) | `publicKeyHex`, `phoneNumber`, `customNickname`, `isBlocked`, `relationshipState` | Cryptographic address book storing verified public identity keys of peers. |
| `ratchet_states` | `entity/RatchetStateEntity.kt` | `conversationId` (String) | `rootKey`, `dhsPrivateKey`, `dhrPublicKey`, `ns`, `nr`, `pn` | Persists Double Ratchet session state (X25519 DH keys, chain counters). |
| `skipped_keys` | `entity/SkippedKeyEntity.kt` | `id` (Auto-inc Long) | `conversationId`, `ratchetKey`, `messageIndex`, `messageKey` | Stores skipped message keys for out-of-order Double Ratchet message recovery. |
| `file_metadata` | `entity/FileMetadataEntity.kt` | `fileId` (String) | `fileName`, `fileSizeBytes`, `mimeType`, `sha256Hash`, `localPath`, `isQuarantined` | Metadata for sent and received files, media attachments, and scans. |
| `transfers` | `entity/TransferEntity.kt` | `transferId` (Long) | `fileId`, `peerNxId`, `direction`, `bytesTransferred`, `transferStatus` | Active P2P and cloud transfer tracking. |
| `transfer_sessions`| `entity/TransferSessionEntity.kt`| `sessionId` (String) | `bitfield`, `chunksTotal`, `chunksReceived`, `peerNxId`, `priorityLane` | Tracks sparse bitfield chunk maps for resilient 64KB WebRTC P2P resumption. |
| `dtn_envelopes` | `entity/DtnEnvelopeEntity.kt` | `envelopeId` (String)| `blindToken`, `encryptedPayload`, `expiryTimestamp`, `custodyStatus` | Storage for Delay-Tolerant Networking (DTN) store-and-forward bundles. |
| `mesh_outbox` | `entity/MeshEnvelopeEntity.kt` | `packetId` (String) | `destinationNxId`, `retryCount`, `nextRetryTimestamp`, `payload` | Local outbox holding DTN packets awaiting contact with routing mules. |
| `quarantine_vault` | `entity/QuarantineEntity.kt` | `quarantineId` (String)| `fileName`, `sha256Hash`, `threatCategory`, `yaraRuleMatch`, `quarantinedAt` | Encrypted isolation vault for malware-flagged incoming files. |
| `device_sync` | `entity/DeviceSyncEntity.kt` | `syncId` (String) | `sourceDeviceId`, `sequenceNumber`, `syncStatus`, `timestamp` | Multi-device conversation synchronization ledger. |
| `message_dedup` | `entity/MessageDedupEntity.kt` | `packetId` (String) | `receivedAtTimestamp` | Bounded circular cache preventing duplicate packet processing across transports. |
| `message_transport`| `entity/MessageTransportEntity.kt`| `packetId` (String) | `transportMedium`, `deliveryLatencyMs`, `retryCount` | Telemetry ledger recording delivery routes and network metrics. |
| `peer_sync_cursors`| `entity/PeerSyncCursorEntity.kt` | `conversationId` (String)| `lastSyncedSequence`, `lastSyncedTimestamp` | High-water mark sequence tracker for delta peer synchronization. |
| `scheduled_messages`| `entity/ScheduledMessageEntity.kt`| `scheduleId` (String)| `conversationId`, `scheduledTimestamp`, `payloadText`, `status` | Local queue for future time-scheduled outgoing messages. |
| `chat_members` | `entity/ChatMemberEntity.kt` | `id` (Auto-inc Long) | `groupId`, `userNxId`, `role` (ADMIN, MEMBER), `joinedTimestamp` | Group membership rosters and role mappings. |
| `message_attachments`| `entity/MessageAttachmentEntity.kt`| `attachmentId` (String)| `packetId`, `fileId`, `attachmentType`, `thumbnailPath` | Direct joins between message bubbles and attached files/media. |
| `chat_drafts` | `entity/ChatDraftEntity.kt` | `conversationId` (String)| `draftText`, `replyToPacketId`, `updatedAt` | Auto-saved un-sent draft text per conversation. |
| `message_reactions`| `entity/MessageReactionEntity.kt`| `reactionId` (String) | `packetId`, `senderNxId`, `emojiCode`, `timestamp` | Emoji reactions attached to individual messages. |
| `status_updates` | `entity/StatusUpdateEntity.kt` | `statusId` (String) | `authorNxId`, `mediaPath`, `caption`, `expiresAtTimestamp` | 24-hour ephemeral status stories published or received. |
| `status_views` | `entity/StatusViewEntity.kt` | `id` (Auto-inc Long) | `statusId`, `viewerNxId`, `viewedAtTimestamp` | Read receipts and audience view tracking for published statuses. |
| `sms_sessions` | `entity/SmsEntities.kt` | `sessionId` (String) | `peerNxId`, `status`, `createdAt`, `updatedAt` | Active Module 3 encrypted SMS fallback sessions. |
| `sms_key_material` | `entity/SmsEntities.kt` | `keyId` (String) | `sessionId`, `encryptedKeyData`, `ivHex`, `keyRole` | Keystore-wrapped symmetric encryption keys dedicated to SMS channels. |
| `sms_packets` | `entity/SmsEntities.kt` | `packetId` (String) | `sessionId`, `direction`, `payloadBase91`, `status` | Outbound and inbound wire frames encoded for cellular SMS delivery. |
| `sms_inbound_parts`| `entity/SmsEntities.kt` | `partId` (String) | `messageId`, `partIndex`, `totalParts`, `rawPayload`, `receivedAt` | Temporary multi-part reassembly store for fragmented incoming SMS frames. |

#### Database Migration Chain (v1 to v28)
`NexaDatabase.kt` defines a contiguous, unbroken migration chain from `MIGRATION_1_2` through `MIGRATION_27_28`:
- Migrations 1 through 10 establish core tables: users, messages, chats, ratchet states, contacts, DTN envelopes.
- Migrations 11 through 20 establish multi-device sync, deduplication tables, scheduled messaging, reactions, drafts, and status stories.
- Migrations 21 through 28 establish Module 3 SMS entities (`sms_sessions`, `sms_key_material`, `sms_packets`, `sms_inbound_parts`), conversation preferences, and custom nicknames.

---

### 3. Backend PostgreSQL Database Architecture

- **Driver & Pool:** `github.com/jackc/pgx/v5/pgxpool`
- **Configuration:** Managed via 18 sequential SQL migration scripts (`database/migrations/001_create_profiles.sql` through `018_create_group_metadata.sql`).
- **Enforcement:** Zero plaintext chats or attachments are stored in this schema. Only user identities, verified public keys, ephemeral routing metadata, and group rosters exist.

#### Exhaustive Inventory of PostgreSQL Tables

| Table Name | Migration File | Primary Key | Key Foreign Keys & Constraints | Purpose & Privacy Boundaries |
|---|---|---|---|---|
| `public.profiles` | `001_create_profiles.sql` | `id` (UUID) | FK $\rightarrow$ `auth.users(id)`, `nx_id` UNIQUE | Public user profile registry (NX-ID handle, bio, avatar URL, discoverability). |
| `public.devices` | `002_create_devices.sql` | `device_id` (VARCHAR) | FK $\rightarrow$ `profiles(id)`, `is_revoked`, `last_active_at` | Tracks authorized physical devices, FCM push tokens, and revocation status. |
| `public.nx_identities` | `003_create_nx_identity.sql` | `nx_id` (VARCHAR(11)) | FK $\rightarrow$ `profiles(id)`, `account_identity_hash` | Resolves public NX-IDs to cryptographic account hashes. |
| `public.phone_verifications`| `004_create_phone_verifications.sql`| `id` (BIGSERIAL) | `phone_number_hash`, `otp_code_hash`, `expires_at` | Ephemeral verification attempts, rate-limiting counters, and HMAC hashes. |
| `public.contacts` | `005_create_contacts.sql` | `id` (BIGSERIAL) | `owner_id`, `contact_nx_id`, `relationship_state` | Synchronized contact relationships (REQUESTED, ACCEPTED, BLOCKED). |
| `public.identity_keys` | `006_create_identity_keys.sql` | `nx_id` (VARCHAR(11)) | `identity_key_ed25519`, `identity_key_x25519` | Public identity verification keys used for out-of-band handshakes and auth. |
| `public.prekeys` | `007_create_prekeys.sql` | `id` (BIGSERIAL) | `user_id`, `prekey_id`, `key_type` (SIGNED, ONE_TIME) | Signal X3DH prekey store; stores signed prekeys and batches of OPKs. |
| `public.message_metadata` | `008_create_message_metadata.sql` | `packet_id` (VARCHAR(64))| `sender_nx_id`, `recipient_nx_id`, `expires_at` | Ephemeral routing metadata (24h TTL). NEVER stores plaintext content. |
| `public.device_sessions`| `009_create_device_sessions.sql`| `session_token` (VARCHAR)| `user_id`, `device_id`, `is_revoked`, `expires_at` | Multi-device active bearer session tokens with remote revocation flags. |
| `public.phone_visibility_grants`| `016_create_phone_visibility_grants.sql`| `id` (BIGSERIAL)| `granter_nx_id`, `grantee_nx_id` | Granular privacy grants permitting specific peers to discover user by phone. |
| `public.e2ee_prekey_claims`| `017_create_e2ee_prekey_claims.sql`| `id` (BIGSERIAL)| `claimed_by_nx_id`, `prekey_id`, `claimed_at` | Audit ledger ensuring one-time prekeys (OPKs) are claimed exactly once. |
| `public.groups` | `018_create_group_metadata.sql` | `group_id` (VARCHAR(64))| `created_by`, `membership_version`, `title` | Group chat metadata (titles, avatars, versions). ZERO plaintext chats stored. |
| `public.group_members` | `018_create_group_metadata.sql` | `id` (BIGSERIAL) | FK $\rightarrow$ `groups(group_id)`, `user_nx_id`, `role` | Group membership rosters and administrative permissions (ADMIN, MEMBER). |

#### Row-Level Security (RLS) Policies (`012_create_rls_policies.sql`)
1. **Profiles Table:** `SELECT` permitted to all authenticated users. `UPDATE` strictly restricted to `auth.uid() = id`.
2. **Devices Table:** Users can only view and mutate devices where `auth.uid() = user_id`.
3. **Contacts Table:** Full read/write isolation where `auth.uid() = owner_id`.
4. **Prekeys Table:** Public read access for handshakes; write access restricted to key owner (`auth.uid() = user_id`).
5. **System Tables:** `phone_verifications`, `device_sessions`, and `message_metadata` enforce a default DENY for public roles, accessible exclusively by the backend `service_role`.

---

### 4. Ephemeral Redis In-Memory Store

- **Client:** `github.com/redis/go-redis/v9` (`internal/queue/redis_buffer.go`)
- **Eviction & Storage Policy:** Strictly In-Memory RAM. Packets and session state are assigned explicit TTLs:

| Key Pattern | Redis Data Structure | TTL | Purpose |
|---|---|---|---|
| `nexa:queue:{recipient_nx_id}` | List (`RPUSH` / `LPOP`) | 24 Hours (`86400s`) | Stores serialized Protobuf wire packets awaiting offline recipient delivery. Purged on ACK. |
| `nexa:challenge:{session_id}` | String (`SETEX`) | 60 Seconds | Holds 32-byte Ed25519 authentication nonces for active WebSocket handshakes. |
| `nexa:otp:{phone_hash}` | Hash (`HSET`) | 10 Minutes | Holds pending OTP verification state (hashed OTP, attempt count, cooldown timestamp). |
| `nexa:ratelimit:{client_ip}` | Sorted Set / Counter | 60 Seconds | Implements distributed sliding-window rate limiting for auth endpoints. |
| `nexa:routing_tokens:{nx_id}` | Set (`SADD`) | 48 Hours | Stores active pairwise blind routing tokens registered by authenticated clients. |
| `nexa:cluster:broadcast` | Pub/Sub Channel | Instantaneous | Broadcasts cross-node delivery packets across horizontally scaled relay instances. |

---

### 5. Encrypted Backup Architecture (Google Drive `appDataFolder`)

- **Service:** `com.nexa.app.data.drive.DriveAppDataService`
- **Location:** Google Drive Private Hidden Application Data Folder (`appDataFolder`). This folder is invisible to the user in the Google Drive Web UI, preventing accidental tampering or deletion.
- **Zero-Lock Database Snapshotting (`VACUUM INTO`):**
  To avoid database locks during live chat usage, `NexaDatabase.getRawDatabase().execSQL("VACUUM INTO 'snapshot.db'")` executes an atomic point-in-time snapshot.
- **Streaming AES-256-GCM Envelope Encryption (`core/backup/crypto/`):**
  1. The user's 12-word BIP-39 recovery phrase is stretched into a 256-bit AES vault key using PBKDF2-HMAC-SHA512 (100,000 iterations) or Argon2id.
  2. The snapshot and media attachments are compressed using DEFLATE (`BackupCompressor.kt`).
  3. `VaultStreamEncryptor.kt` encrypts the bundle in 64 KB chunks using AES-256-GCM with a randomized 12-byte initialization vector per chunk.
  4. The encrypted vault bundle (`nexa_backup_v1_{timestamp}.vault`) is uploaded via resumable multi-part upload (`DriveResumableUploader.kt`).
