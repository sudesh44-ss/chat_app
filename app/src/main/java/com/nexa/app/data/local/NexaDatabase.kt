package com.nexa.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.DeviceSyncDao
import com.nexa.app.data.local.dao.DtnEnvelopeDao
import com.nexa.app.data.local.dao.FileTransferDao
import com.nexa.app.data.local.dao.MeshOutboxDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.MessageDedupDao
import com.nexa.app.data.local.dao.MessageTransportDao
import com.nexa.app.data.local.dao.PeerSyncCursorDao
import com.nexa.app.data.local.dao.QuarantineDao
import com.nexa.app.data.local.dao.RatchetDao
import com.nexa.app.data.local.dao.SwarmCacheDao
import com.nexa.app.data.local.dao.TransferSessionDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.DeviceSyncEntity
import com.nexa.app.data.local.entity.DtnEnvelopeEntity
import com.nexa.app.data.local.entity.FileMetadataEntity
import com.nexa.app.data.local.entity.MeshEnvelopeEntity
import com.nexa.app.data.local.entity.MessageDedupEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.MessageTransportEntity
import com.nexa.app.data.local.entity.PeerSyncCursorEntity
import com.nexa.app.data.local.entity.QuarantineEntity
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.data.local.entity.TransferSessionEntity
import com.nexa.app.data.local.entity.UserEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        UserEntity::class,
        MessageEntity::class,
        ChatEntity::class,
        DtnEnvelopeEntity::class,
        TransferEntity::class,
        FileMetadataEntity::class,
        RatchetStateEntity::class,
        SkippedKeyEntity::class,
        ContactEntity::class,
        QuarantineEntity::class,
        TransferSessionEntity::class,
        MeshEnvelopeEntity::class,
        DeviceSyncEntity::class,
        MessageDedupEntity::class,
        MessageTransportEntity::class,
        PeerSyncCursorEntity::class
    ],
    version = 20,
    exportSchema = false
)
abstract class NexaDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun messageDedupDao(): MessageDedupDao
    abstract fun messageTransportDao(): MessageTransportDao
    abstract fun peerSyncCursorDao(): PeerSyncCursorDao
    abstract fun chatDao(): ChatDao
    abstract fun dtnEnvelopeDao(): DtnEnvelopeDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun swarmCacheDao(): SwarmCacheDao
    abstract fun ratchetDao(): RatchetDao
    abstract fun contactDao(): ContactDao
    abstract fun quarantineDao(): QuarantineDao
    abstract fun transferSessionDao(): TransferSessionDao
    abstract fun meshOutboxDao(): MeshOutboxDao
    abstract fun deviceSyncDao(): DeviceSyncDao

    /**
     * Exposes the underlying SupportSQLiteDatabase handle for zero-lock snapshots (VACUUM INTO).
     */
    fun getRawDatabase(): androidx.sqlite.db.SupportSQLiteDatabase {
        return openHelper.writableDatabase
    }

    companion object {
        private const val DATABASE_NAME = "nexa_encrypted_vault.db"

        @Volatile
        private var INSTANCE: NexaDatabase? = null

        // --- Sequential Explicit Migrations (v1 -> v11) ---

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chats` (
                        `conversationId` TEXT NOT NULL PRIMARY KEY,
                        `participantNxId` TEXT NOT NULL,
                        `participantName` TEXT NOT NULL,
                        `lastMessageText` TEXT NOT NULL,
                        `lastMessageTimestamp` INTEGER NOT NULL,
                        `unreadCount` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dtn_envelopes` (
                        `envelopeIdHex` TEXT NOT NULL PRIMARY KEY,
                        `blindTokenHex` TEXT NOT NULL,
                        `hopLimit` INTEGER NOT NULL,
                        `rawEnvelopeBytes` BLOB NOT NULL,
                        `expiresAtEpochSec` INTEGER NOT NULL,
                        `receivedAtTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dtn_envelopes_blindTokenHex` ON `dtn_envelopes` (`blindTokenHex`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dtn_envelopes_expiresAtEpochSec` ON `dtn_envelopes` (`expiresAtEpochSec`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dtn_envelopes_receivedAtTimestamp` ON `dtn_envelopes` (`receivedAtTimestamp`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `file_transfers` (
                        `transferId` INTEGER NOT NULL PRIMARY KEY,
                        `conversationId` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `localUri` TEXT NOT NULL,
                        `fileHash` TEXT NOT NULL,
                        `chunksCompleted` INTEGER NOT NULL,
                        `totalChunks` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `isIncoming` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_transfers_conversationId` ON `file_transfers` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_transfers_status` ON `file_transfers` (`status`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `file_envelopes` (
                        `fileHash` TEXT NOT NULL PRIMARY KEY,
                        `conversationId` TEXT NOT NULL,
                        `senderNxId` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `totalChunks` INTEGER NOT NULL,
                        `blurHash` TEXT,
                        `status` TEXT NOT NULL,
                        `localFilePath` TEXT,
                        `lastAccessedTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_file_envelopes_fileHash` ON `file_envelopes` (`fileHash`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ratchet_sessions` (
                        `contactNxId` TEXT NOT NULL PRIMARY KEY,
                        `rootKeyHex` TEXT NOT NULL,
                        `senderChainKeyHex` TEXT NOT NULL,
                        `receiverChainKeyHex` TEXT NOT NULL,
                        `senderSeqNumber` INTEGER NOT NULL,
                        `receiverSeqNumber` INTEGER NOT NULL,
                        `localDhPrivateKeyHex` TEXT NOT NULL,
                        `localDhPublicKeyHex` TEXT NOT NULL,
                        `remoteDhPublicKeyHex` TEXT NOT NULL,
                        `lastUpdatedTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skipped_message_keys` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `contactNxId` TEXT NOT NULL,
                        `sequenceNumber` INTEGER NOT NULL,
                        `derivedKeyHex` TEXT NOT NULL,
                        `createdAtTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_skipped_message_keys_contactNxId_sequenceNumber` ON `skipped_message_keys` (`contactNxId`, `sequenceNumber`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contacts` (
                        `nxId` TEXT NOT NULL PRIMARY KEY,
                        `displayName` TEXT NOT NULL,
                        `publicKeyHex` TEXT NOT NULL,
                        `introMessage` TEXT,
                        `status` TEXT NOT NULL,
                        `blePeerId` TEXT,
                        `createdAtTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contacts_nxId` ON `contacts` (`nxId`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `quarantine_logs` (
                        `fileHash` TEXT NOT NULL PRIMARY KEY,
                        `fileName` TEXT NOT NULL,
                        `threatReason` TEXT,
                        `status` TEXT NOT NULL,
                        `quarantinedFilePath` TEXT NOT NULL,
                        `detectedTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfer_sessions` (
                        `transferId` INTEGER NOT NULL PRIMARY KEY,
                        `conversationId` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `localFilePath` TEXT NOT NULL,
                        `fileHash` TEXT NOT NULL,
                        `chunksCompleted` INTEGER NOT NULL,
                        `totalChunks` INTEGER NOT NULL,
                        `bitfieldBytes` BLOB NOT NULL,
                        `status` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL DEFAULT 0,
                        `isTurnRelayed` INTEGER NOT NULL DEFAULT 0,
                        `lastActiveTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mesh_outbox` (
                        `envelopeId` TEXT NOT NULL PRIMARY KEY,
                        `destinationId` TEXT NOT NULL,
                        `payload` BLOB NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'OUTBOX_HOLD',
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_sync_log` (
                        `syncId` TEXT NOT NULL PRIMARY KEY,
                        `sourceDeviceId` TEXT NOT NULL,
                        `sequenceNumber` INTEGER NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `encryptedPayloadHex` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_device_sync_log_sourceDeviceId` ON `device_sync_log` (`sourceDeviceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_device_sync_log_isSynced` ON `device_sync_log` (`isSynced`)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `customNickname` TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `phoneNumber` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `isPhoneInLocalAddressBook` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `isPhoneSharedByPeer` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `connectionSource` TEXT NOT NULL DEFAULT 'ADDRESS_BOOK'")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `avatarLocalPath` TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `deviceContactName` TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `pinnedExpiresAt` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `pinnedAt` INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `deviceContactPhotoUri` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `nexaAvatarUrl` TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "ratchet_sessions", "previousChainLength", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "ratchet_sessions", "sessionId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "ratchet_sessions", "remoteDeviceId", "TEXT NOT NULL DEFAULT ''")

                addColumnIfNotExists(db, "skipped_message_keys", "ratchetPublicKeyHex", "TEXT NOT NULL DEFAULT ''")
                addColumnIfNotExists(db, "skipped_message_keys", "messageIndex", "INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_skipped_message_keys_contactNxId_ratchetPublicKeyHex_messageIndex` ON `skipped_message_keys` (`contactNxId`, `ratchetPublicKeyHex`, `messageIndex`)")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "user_profile", "email", "TEXT DEFAULT NULL")
                addColumnIfNotExists(db, "user_profile", "avatarUrl", "TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_dedup_index` (
                        `packetId` TEXT NOT NULL PRIMARY KEY,
                        `senderNxId` TEXT NOT NULL,
                        `recipientNxId` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `payloadHashHex` TEXT NOT NULL,
                        `firstObservedAt` INTEGER NOT NULL,
                        `firstTransport` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'PROCESSED'
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_dedup_index_conversationId` ON `message_dedup_index` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_dedup_index_senderNxId` ON `message_dedup_index` (`senderNxId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_dedup_index_firstObservedAt` ON `message_dedup_index` (`firstObservedAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_transport_observations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `packetId` TEXT NOT NULL,
                        `transportMedium` TEXT NOT NULL,
                        `observedAt` INTEGER NOT NULL,
                        `sourcePeerId` TEXT,
                        FOREIGN KEY (`packetId`) REFERENCES `message_dedup_index`(`packetId`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_message_transport_observations_packetId_transportMedium` ON `message_transport_observations` (`packetId`, `transportMedium`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_transport_observations_packetId` ON `message_transport_observations` (`packetId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_transport_observations_observedAt` ON `message_transport_observations` (`observedAt`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `peer_sync_cursors` (
                        `conversationId` TEXT NOT NULL PRIMARY KEY,
                        `peerNxId` TEXT NOT NULL,
                        `lastSyncTimestamp` INTEGER NOT NULL DEFAULT 0,
                        `lastSyncSequence` INTEGER NOT NULL DEFAULT 0,
                        `lastSyncedPacketId` TEXT DEFAULT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_peer_sync_cursors_peerNxId` ON `peer_sync_cursors` (`peerNxId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_peer_sync_cursors_lastSyncTimestamp` ON `peer_sync_cursors` (`lastSyncTimestamp`)")

                // Backfill from existing messages
                try {
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `message_dedup_index` (`packetId`, `senderNxId`, `recipientNxId`, `conversationId`, `payloadHashHex`, `firstObservedAt`, `firstTransport`, `status`)
                        SELECT `packetId`, `senderNxId`, `recipientNxId`, `conversationId`, '', `timestamp`, `transportType`, 'PROCESSED' FROM `messages`
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `message_transport_observations` (`packetId`, `transportMedium`, `observedAt`)
                        SELECT `packetId`, `transportType`, `timestamp` FROM `messages`
                        """.trimIndent()
                    )
                } catch (ignored: Exception) {}
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `dtn_envelopes` ADD COLUMN `custodyState` TEXT NOT NULL DEFAULT 'CUSTODY_ACCEPTED'")
                db.execSQL("ALTER TABLE `dtn_envelopes` ADD COLUMN `custodianNodeId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `dtn_envelopes` ADD COLUMN `nextHopNodeId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `dtn_envelopes` ADD COLUMN `transferredAtTimestamp` INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dtn_envelopes_custodyState` ON `dtn_envelopes` (`custodyState`)")
            }
        }

        private fun addColumnIfNotExists(db: SupportSQLiteDatabase, table: String, column: String, typeAndConstraints: String) {
            var exists = false
            try {
                val cursor = db.query("PRAGMA table_info(`$table`)")
                while (cursor.moveToNext()) {
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex != -1 && cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                        exists = true
                        break
                    }
                }
                cursor.close()
            } catch (e: Exception) {
                // If query fails, fall back to direct attempt
            }
            if (!exists) {
                try {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $typeAndConstraints")
                } catch (e: Exception) {
                    android.util.Log.w("NexaDatabase", "Column $column on $table already exists or alter failed: ${e.message}")
                }
            }
        }

        fun getInstance(context: Context, passphrase: ByteArray): NexaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext, passphrase).also {
                    INSTANCE = it
                }
            }
        }

        fun closeAndResetInstance() {
            synchronized(this) {
                try {
                    if (INSTANCE?.isOpen == true) {
                        try {
                            INSTANCE?.getRawDatabase()?.execSQL("PRAGMA wal_checkpoint(FULL)")
                        } catch (ignored: Exception) {}
                    }
                    INSTANCE?.close()
                } catch (ignored: Exception) {}
                INSTANCE = null
            }
        }

        private fun checkAndRecoverFromRollbackJournal(context: Context) {
            try {
                val dbFile = context.getDatabasePath(DATABASE_NAME)
                val parent = dbFile.parentFile ?: return
                val rollbackFile = java.io.File(parent, "$DATABASE_NAME.rollback_bak")
                val rollbackWal = java.io.File(parent, "$DATABASE_NAME-wal.rollback_bak")
                if (rollbackFile.exists()) {
                    // If live db is missing or smaller than SQLite header, recover from journal
                    if (!dbFile.exists() || dbFile.length() < 4096L) {
                        android.util.Log.w("NexaDatabase", "Interrupted restore detected! Automatically recovering live database from rollback journal...")
                        rollbackFile.copyTo(dbFile, overwrite = true)
                        if (rollbackWal.exists()) {
                            val walFile = java.io.File(dbFile.path + "-wal")
                            rollbackWal.copyTo(walFile, overwrite = true)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NexaDatabase", "Failed to check rollback journal during startup", e)
            }
        }

        private fun buildDatabase(context: Context, passphrase: ByteArray): NexaDatabase {
            checkAndRecoverFromRollbackJournal(context)
            try {
                System.loadLibrary("sqlcipher")
            } catch (ignored: Throwable) {}
            // Instantiate SQLCipher open helper factory with the Keystore-protected passphrase
            val supportFactory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context,
                NexaDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(supportFactory)
                .fallbackToDestructiveMigrationOnDowngrade()
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20
                )
                .build()
        }
    }
}
