package com.nexa.app.core.backup

import java.nio.ByteBuffer

/**
 * 71-byte rigid binary header prepended to all encrypted backup vault files.
 *
 * Wire Layout:
 * ┌──────────────┬──────────────┬──────────────┬──────────────┬────────────────────────┬─────────────────────────┐
 * │ Magic Header │ Version ID   │  Salt Bytes  │ Nonce / IV   │ Payload SHA-256 Hash   │ Encrypted + Compressed  │
 * │  "NXVAULT"   │  (4 Bytes)   │  (16 Bytes)  │  (12 Bytes)  │ (32 Bytes)             │ SQLite Database Payload │
 * │  (7 Bytes)   │  (Int: 1)    │              │              │                        │ (Variable Length)       │
 * └──────────────┴──────────────┴──────────────┴──────────────┴────────────────────────┴─────────────────────────┘
 */
data class BackupVaultHeader(
    val version: Int = CURRENT_VERSION,
    val salt: ByteArray,           // 16 bytes for PBKDF2-HMAC-SHA256
    val iv: ByteArray,             // 12 bytes AES-256-GCM Nonce
    val payloadSha256: ByteArray   // 32 bytes SHA-256 checksum of the encrypted ciphertext
) {
    init {
        require(salt.size == SALT_SIZE) { "Salt must be exactly $SALT_SIZE bytes, got ${salt.size}" }
        require(iv.size == IV_SIZE) { "IV must be exactly $IV_SIZE bytes, got ${iv.size}" }
        require(payloadSha256.size == HASH_SIZE) { "Payload SHA-256 must be exactly $HASH_SIZE bytes, got ${payloadSha256.size}" }
    }

    fun toByteArray(): ByteArray {
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        val buffer = ByteBuffer.allocate(HEADER_SIZE)
        buffer.put(magic)
        buffer.putInt(version)
        buffer.put(salt)
        buffer.put(iv)
        buffer.put(payloadSha256)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BackupVaultHeader
        if (version != other.version) return false
        if (!salt.contentEquals(other.salt)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (!payloadSha256.contentEquals(other.payloadSha256)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + payloadSha256.contentHashCode()
        return result
    }

    companion object {
        const val MAGIC = "NXVAULT"
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val CURRENT_VERSION = VERSION_2
        const val MAGIC_SIZE = 7
        const val VERSION_SIZE = 4
        const val SALT_SIZE = 16
        const val IV_SIZE = 12
        const val HASH_SIZE = 32
        const val HEADER_SIZE = MAGIC_SIZE + VERSION_SIZE + SALT_SIZE + IV_SIZE + HASH_SIZE // 71 Bytes

        fun isSupportedVersion(v: Int): Boolean = v in listOf(VERSION_1, VERSION_2)

        fun fromByteArray(bytes: ByteArray): BackupVaultHeader? {
            if (bytes.size < HEADER_SIZE) return null

            val buffer = ByteBuffer.wrap(bytes)
            val magicBytes = ByteArray(MAGIC_SIZE)
            buffer.get(magicBytes)
            val magicStr = String(magicBytes, Charsets.US_ASCII)
            if (magicStr != MAGIC) return null

            val version = buffer.int
            if (!isSupportedVersion(version)) return null

            val salt = ByteArray(SALT_SIZE).also { buffer.get(it) }
            val iv = ByteArray(IV_SIZE).also { buffer.get(it) }
            val hash = ByteArray(HASH_SIZE).also { buffer.get(it) }

            return BackupVaultHeader(
                version = version,
                salt = salt,
                iv = iv,
                payloadSha256 = hash
            )
        }
    }
}
