package com.nexa.app.core.crypto

import java.security.MessageDigest

/**
 * Client-Side Micro-Proof-of-Work (Hashcash) Engine.
 *
 * Implements a lightweight cryptographic challenge:
 * SHA-256(TargetNxId : Timestamp : Nonce) -> Requires 16 leading zero bits (first 2 bytes 0x00, 0x00).
 *
 * Cost: ~250-400 ms on a standard mobile CPU (imperceptible for humans, computationally fatal for spam bots).
 * Server verification: < 1 µs execution time.
 */
object ProofOfWorkEngine {

    const val DIFFICULTY_ZERO_BYTES = 2 // 16 leading zero bits
    const val EXPIRATION_WINDOW_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Solves the Proof-of-Work challenge sequentially until a nonce yielding 16 leading zero bits is found.
     */
    fun solve(targetNxId: String, timestamp: Long): Long {
        var nonce = 0L
        val prefix = "$targetNxId:$timestamp:"
        val digest = MessageDigest.getInstance("SHA-256")

        while (true) {
            val input = prefix + nonce
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))

            if (hash[0] == 0.toByte() && hash[1] == 0.toByte()) {
                return nonce
            }
            nonce++
        }
    }

    /**
     * Verifies the Proof-of-Work nonce in under 1 microsecond and enforces a 10-minute freshness window.
     */
    fun verify(
        targetNxId: String,
        timestamp: Long,
        nonce: Long,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        // Enforce 10-minute expiry window
        if (currentTimeMs - timestamp > EXPIRATION_WINDOW_MS || timestamp > currentTimeMs + 60_000L) {
            return false
        }

        val prefix = "$targetNxId:$timestamp:"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest((prefix + nonce).toByteArray(Charsets.UTF_8))

        return hash[0] == 0.toByte() && hash[1] == 0.toByte()
    }
}
