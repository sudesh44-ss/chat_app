package com.nexa.app.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2 hashing for the local app-lock PIN.
 *
 * The PIN itself is never stored. Only a random salt and the derived hash are kept
 * inside [SecureStorage] (EncryptedSharedPreferences backed by the Android Keystore).
 * PBKDF2WithHmacSHA1 is used because it is available on all supported API levels (24+).
 */
object AppLockPinHasher {

    const val ITERATIONS = 150_000
    private const val KEY_LENGTH_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA1"

    fun newSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(pin: String, salt: ByteArray, expectedHash: ByteArray, iterations: Int = ITERATIONS): Boolean {
        val actual = hash(pin, salt, iterations)
        return MessageDigest.isEqual(actual, expectedHash)
    }

    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }
}
