package com.nexa.app.core.sms

/**
 * Canonical DDL for the Module 3 SMS tables (Room v28).
 *
 * The migration executes exactly these statements, and a JVM schema-consistency test derives
 * the expected columns from the Room entities and compares them with this DDL. That closes the
 * usual gap where a migration is written by hand and silently drifts from the entities.
 *
 * All statements are additive and idempotent: no existing table or column is altered.
 */
object SmsSchemaDdl {

    const val TABLE_SESSIONS = "sms_sessions"
    const val TABLE_KEY_MATERIAL = "sms_key_material"
    const val TABLE_PACKETS = "sms_packets"
    const val TABLE_INBOUND_PARTS = "sms_inbound_parts"

    val statements: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `sms_sessions` (
            `peerNxId` TEXT NOT NULL,
            `peerProvisioningPublicKeyHex` TEXT NOT NULL,
            `localProvisioningPublicKeyHex` TEXT NOT NULL,
            `keyVersion` INTEGER NOT NULL,
            `establishedAt` INTEGER NOT NULL,
            `expiresAt` INTEGER NOT NULL,
            `isRevoked` INTEGER NOT NULL DEFAULT 0,
            `sendCounter` INTEGER NOT NULL DEFAULT 0,
            `ackSendCounter` INTEGER NOT NULL DEFAULT 0,
            `recvHighestCounter` INTEGER NOT NULL DEFAULT -1,
            `recvBitmap` INTEGER NOT NULL DEFAULT 0,
            `ackRecvHighestCounter` INTEGER NOT NULL DEFAULT -1,
            `ackRecvBitmap` INTEGER NOT NULL DEFAULT 0,
            `lastUsedAt` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`peerNxId`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_sms_sessions_expiresAt` ON `sms_sessions` (`expiresAt`)",

        """
        CREATE TABLE IF NOT EXISTS `sms_key_material` (
            `keyId` TEXT NOT NULL,
            `publicKeyHex` TEXT NOT NULL,
            `encryptedPrivateKeyHex` TEXT NOT NULL,
            `ivHex` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`keyId`)
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS `sms_packets` (
            `messageId` TEXT NOT NULL,
            `packetId` TEXT NOT NULL,
            `peerNxId` TEXT NOT NULL,
            `direction` TEXT NOT NULL,
            `keyVersion` INTEGER NOT NULL,
            `sendCounter` INTEGER NOT NULL,
            `totalParts` INTEGER NOT NULL,
            `segmentCount` INTEGER NOT NULL,
            `handoffState` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `failureKind` TEXT,
            `deliveryChannel` TEXT NOT NULL DEFAULT 'EXTERNAL_SMS_COMPOSER',
            PRIMARY KEY(`messageId`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_sms_packets_packetId` ON `sms_packets` (`packetId`)",
        "CREATE INDEX IF NOT EXISTS `index_sms_packets_peerNxId` ON `sms_packets` (`peerNxId`)",
        "CREATE INDEX IF NOT EXISTS `index_sms_packets_handoffState` ON `sms_packets` (`handoffState`)",
        "CREATE INDEX IF NOT EXISTS `index_sms_packets_direction` ON `sms_packets` (`direction`)",

        """
        CREATE TABLE IF NOT EXISTS `sms_inbound_parts` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `messageId` TEXT NOT NULL,
            `senderNxId` TEXT NOT NULL,
            `partIndex` INTEGER NOT NULL,
            `totalParts` INTEGER NOT NULL,
            `payloadBase64` TEXT NOT NULL,
            `receivedAt` INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_inbound_parts_messageId_senderNxId_partIndex` " +
            "ON `sms_inbound_parts` (`messageId`, `senderNxId`, `partIndex`)",
        "CREATE INDEX IF NOT EXISTS `index_sms_inbound_parts_receivedAt` ON `sms_inbound_parts` (`receivedAt`)"
    )
}
