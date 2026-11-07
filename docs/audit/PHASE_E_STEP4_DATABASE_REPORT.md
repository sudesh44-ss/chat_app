# NEXA Phase E Step 4 — Database Schema & Migration Report

**Database:** Android SQLCipher Room Database (`NexaDatabase.kt`)  
**Previous Version:** 16  
**New Version:** 17  
**Migration Path:** `MIGRATION_16_17`  
**Test Status:** Verified & Passing

---

## 1. Schema Modifications

### Table: `ratchet_sessions`
Represents the persistent cryptographic state of a Double Ratchet session with a remote contact.

```sql
-- Migration changes executed in MIGRATION_16_17:
ALTER TABLE ratchet_sessions ADD COLUMN previousChainLength INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ratchet_sessions ADD COLUMN sessionId TEXT NOT NULL DEFAULT '';
ALTER TABLE ratchet_sessions ADD COLUMN remoteDeviceId TEXT NOT NULL DEFAULT '';
```

#### Field Specifications:
- `contactNxId` (TEXT, PK): Nexa ID of the remote participant.
- `rootKeyHex` (TEXT): 32-byte current Root Key ($RK$).
- `senderChainKeyHex` (TEXT): 32-byte current Sender Chain Key ($CK_s$).
- `receiverChainKeyHex` (TEXT): 32-byte current Receiver Chain Key ($CK_r$).
- `senderSeqNumber` (INTEGER): Current message index $N$ in sending chain.
- `receiverSeqNumber` (INTEGER): Expected message index $N$ in receiving chain.
- `localDhPrivateKeyHex` (TEXT): 32-byte current local X25519 ratchet private key.
- `localDhPublicKeyHex` (TEXT): 32-byte current local X25519 ratchet public key.
- `remoteDhPublicKeyHex` (TEXT): 32-byte current remote peer's X25519 ratchet public key.
- `previousChainLength` (INTEGER, **NEW**): Length of previous sending chain ($PN$), required for DH ratchet turns.
- `sessionId` (TEXT, **NEW**): UUID of the established session, bound into Associated Data ($AD$).
- `remoteDeviceId` (TEXT, **NEW**): Target registered device ID for multi-device routing.
- `lastUpdatedTimestamp` (INTEGER): Monotonic timestamp of last session activity.

---

### Table: `skipped_message_keys`
Caches derived message keys for out-of-order packet arrivals, enabling delayed recovery without stalling the conversation.

```sql
-- Migration changes executed in MIGRATION_16_17:
ALTER TABLE skipped_message_keys ADD COLUMN ratchetPublicKeyHex TEXT NOT NULL DEFAULT '';
ALTER TABLE skipped_message_keys ADD COLUMN messageIndex INTEGER NOT NULL DEFAULT 0;
```

#### Field Specifications:
- `id` (INTEGER, PK AUTOINCREMENT): Unique row identifier.
- `contactNxId` (TEXT): Nexa ID of the remote participant.
- `sequenceNumber` (INTEGER): Sequence number within receiving chain.
- `ratchetPublicKeyHex` (TEXT, **NEW**): Hex-encoded remote ratchet public key associated with this skipped key.
- `messageIndex` (INTEGER, **NEW**): Message index within the specific ratchet chain.
- `derivedKeyHex` (TEXT): 32-byte derived message key ($MK$).
- `createdAtTimestamp` (INTEGER): Timestamp when key was cached.

---

## 2. DAO Implementations & Atomic Queries (`RatchetDao.kt`)

```kotlin
@Query("SELECT * FROM ratchet_sessions WHERE sessionId = :sessionId LIMIT 1")
suspend fun getSessionById(sessionId: String): RatchetStateEntity?

@Query("DELETE FROM ratchet_sessions WHERE contactNxId = :contactNxId")
suspend fun deleteSession(contactNxId: String): Int

@Query("SELECT * FROM skipped_message_keys WHERE contactNxId = :contactNxId AND ratchetPublicKeyHex = :ratchetPubHex AND messageIndex = :msgIdx LIMIT 1")
suspend fun getSkippedKeyByHeader(contactNxId: String, ratchetPubHex: String, msgIdx: Int): SkippedKeyEntity?

@Query("DELETE FROM skipped_message_keys WHERE contactNxId = :contactNxId AND ratchetPublicKeyHex = :ratchetPubHex AND messageIndex = :msgIdx")
suspend fun deleteSkippedKeyByHeader(contactNxId: String, ratchetPubHex: String, msgIdx: Int): Int

@Query("SELECT COUNT(*) FROM skipped_message_keys WHERE contactNxId = :contactNxId")
suspend fun countSkippedKeys(contactNxId: String): Int

@Query("DELETE FROM skipped_message_keys WHERE createdAtTimestamp < :thresholdTimestamp")
suspend fun purgeOldSkippedKeys(thresholdTimestamp: Long): Int
```

---

## 3. Storage Security & TTL Policy
- All tables reside within the SQLCipher database, encrypted at rest using AES-256 with key material derived from Android Keystore.
- Skipped keys are strictly subject to a 14-day Time-To-Live (TTL). `purgeOldSkippedKeys` runs automatically during session initialization and periodic maintenance to enforce Forward Secrecy.
