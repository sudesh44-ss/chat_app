package com.nexa.app.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 hasher for verifying local backup password credentials without plaintext persistence.
 */
object BackupPasswordHasher {

    const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun newSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun hash(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(password: String, salt: ByteArray, expectedHash: ByteArray, iterations: Int = ITERATIONS): Boolean {
        val actual = hash(password, salt, iterations)
        return MessageDigest.isEqual(actual, expectedHash)
    }
}
