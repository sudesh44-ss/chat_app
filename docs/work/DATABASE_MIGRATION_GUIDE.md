# NEXA Database Migration Guide: Explicit Sequential Zero-Loss Migrations

---

## 1. Vulnerability Analysis & Remediation

### The Vulnerability: Destructive Migration Flag
Prior to Phase 2, `NexaDatabase.kt` was configured with:
```kotlin
Room.databaseBuilder(...)
    .openHelperFactory(supportFactory)
    .fallbackToDestructiveMigration() // DANGER: Wipes all tables on version bump!
    .build()
```
And zero `Migration` instances were defined. Upgrading the schema from version 10 to version 11 would have permanently deleted:
- All plaintext chat histories (`messages`)
- Bilateral mutual contact trust states (`contacts`)
- Double Ratchet DH keys and skipped message keys (`ratchet_sessions`, `skipped_message_keys`)
- Offline custody payloads and DTN envelopes (`dtn_envelopes`, `mesh_outbox`)
- Resilient file transfer checkpoint bitfields (`transfer_sessions`)

### The Solution: Explicit Sequential Migrations
1. Completely removed `.fallbackToDestructiveMigration()`.
2. Increased database version to `version = 11`.
3. Added sequential `Migration(startVersion, endVersion)` instances covering versions 1 through 11.
4. Registered all migrations with `.addMigrations(...)` in the `Room.databaseBuilder()`.

---

## 2. Sequential Migration Matrix

| Migration | Source | Target | Tables Added / Modified |
|---|---|---|---|
| `MIGRATION_1_2` | 1 | 2 | Added `chats` table |
| `MIGRATION_2_3` | 2 | 3 | Added `dtn_envelopes` table + 3 indexes |
| `MIGRATION_3_4` | 3 | 4 | Added `file_transfers` table + 2 indexes |
| `MIGRATION_4_5` | 4 | 5 | Added `file_envelopes` table + 1 unique index |
| `MIGRATION_5_6` | 5 | 6 | Added `ratchet_sessions` + `skipped_message_keys` tables + index |
| `MIGRATION_6_7` | 6 | 7 | Added `contacts` table + unique index |
| `MIGRATION_7_8` | 7 | 8 | Added `quarantine_logs` table |
| `MIGRATION_8_9` | 8 | 9 | Added `transfer_sessions` table |
| `MIGRATION_9_10` | 9 | 10 | Added `mesh_outbox` table |
| `MIGRATION_10_11` | 10 | 11 | Added `device_sync_log` table + 2 indexes |

---

## 3. SQL DDL Reference for Version 11 Tables

```sql
-- Version 11 New Table: device_sync_log
CREATE TABLE IF NOT EXISTS `device_sync_log` (
    `syncId` TEXT NOT NULL PRIMARY KEY,
    `sourceDeviceId` TEXT NOT NULL,
    `sequenceNumber` INTEGER NOT NULL,
    `entityType` TEXT NOT NULL,
    `encryptedPayloadHex` TEXT NOT NULL,
    `timestamp` INTEGER NOT NULL,
    `isSynced` INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS `index_device_sync_log_sourceDeviceId` 
    ON `device_sync_log` (`sourceDeviceId`);

CREATE INDEX IF NOT EXISTS `index_device_sync_log_isSynced` 
    ON `device_sync_log` (`isSynced`);
```

---

## 4. Verification and Testing

Automated migration integrity tests in `Phase2AuthAndIdentityTests.kt` verify:
1. Every version step is strictly continuous (`MIGRATION_x_y.startVersion == x && MIGRATION_x_y.endVersion == y`).
2. There are no gaps or missing migration steps between v1 and v11.
3. Database open operations complete successfully without destructive table drops.
