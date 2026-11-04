package com.nexa.app.core.dtn

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Ephemeral Blind Token Generator.
 * Implements: BlindToken = Truncate_16(Blake3(RecipientPublicKey || EpochDaySalt))
 * Ensures zero-metadata tracking: Intermediate nodes cannot deduce which user an envelope is destined for.
 */
object BlindTokenGenerator {

    private const val SALT_PREFIX = "NEXA_BLIND_DAY_SALT_V1_"

    /**
     * Generates a 16-byte Blind Token from a recipient's public key bytes and the current epoch day.
     */
    fun generateToken(recipientPublicKeyBytes: ByteArray, epochDay: Long = currentEpochDay()): ByteArray {
        val saltBytes = "$SALT_PREFIX$epochDay".toByteArray(StandardCharsets.UTF_8)
        val input = ByteArray(recipientPublicKeyBytes.size + saltBytes.size)
        System.arraycopy(recipientPublicKeyBytes, 0, input, 0, recipientPublicKeyBytes.size)
        System.arraycopy(saltBytes, 0, input, recipientPublicKeyBytes.size, saltBytes.size)

        val fullHash = Blake3Hasher.hash(input)
        val token = ByteArray(16)
        System.arraycopy(fullHash, 0, token, 0, 16)
        return token
    }

    /**
     * Generates 16-byte Blind Token from a recipient's hex-encoded public key.
     */
    fun generateTokenFromHex(recipientPublicKeyHex: String, epochDay: Long = currentEpochDay()): ByteArray {
        val keyBytes = recipientPublicKeyHex.hexToByteArray()
        return generateToken(keyBytes, epochDay)
    }

    fun currentEpochDay(): Long {
        return System.currentTimeMillis() / (1000L * 86400L)
    }

    /**
     * Specification-compliant multi-block Blake3 cryptographic chunk hasher.
     */
    object Blake3Hasher {
        private val IV = intArrayOf(
            0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
            0x510E527F.toInt(), 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19.toInt()
        )

        private val MSG_PERMUTATION = intArrayOf(
            2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8
        )

        private const val CHUNK_START = 1
        private const val CHUNK_END = 2
        private const val ROOT = 8

        fun hash(input: ByteArray): ByteArray {
            val totalBytes = input.size
            val numBlocks = if (totalBytes == 0) 1 else ((totalBytes + 63) / 64)

            var cv = IV.clone()

            for (blockIdx in 0 until numBlocks) {
                val blockStart = blockIdx * 64
                val blockEnd = minOf(totalBytes, blockStart + 64)
                val blockLen = if (totalBytes == 0) 0 else (blockEnd - blockStart)

                val blockWords = IntArray(16)
                for (w in 0 until 16) {
                    val bytePos = blockStart + w * 4
                    var word = 0
                    for (b in 0 until 4) {
                        val idx = bytePos + b
                        if (idx < blockEnd) {
                            word = word or ((input[idx].toInt() and 0xFF) shl (b * 8))
                        }
                    }
                    blockWords[w] = word
                }

                val isFirstBlock = (blockIdx == 0)
                val isLastBlock = (blockIdx == numBlocks - 1)

                var flags = 0
                if (isFirstBlock) flags = flags or CHUNK_START
                if (isLastBlock) flags = flags or CHUNK_END or ROOT

                val state = IntArray(16)
                System.arraycopy(cv, 0, state, 0, 8)
                System.arraycopy(IV, 0, state, 8, 4)
                state[12] = 0 // t0
                state[13] = 0 // t1
                state[14] = blockLen
                state[15] = flags

                var m = blockWords.clone()
                for (round in 0 until 7) {
                    round(state, m)
                    m = permute(m)
                }

                if (!isLastBlock) {
                    // Update CV for the next block in the chunk
                    for (i in 0 until 8) {
                        cv[i] = state[i] xor state[i + 8]
                    }
                } else {
                    // Root output: 32 bytes
                    val out = ByteArray(32)
                    val outBuf = ByteBuffer.wrap(out)
                    for (w in 0 until 8) {
                        val word = state[w] xor state[w + 8]
                        outBuf.put((word and 0xFF).toByte())
                        outBuf.put(((word ushr 8) and 0xFF).toByte())
                        outBuf.put(((word ushr 16) and 0xFF).toByte())
                        outBuf.put(((word ushr 24) and 0xFF).toByte())
                    }
                    return out
                }
            }

            return ByteArray(32)
        }

        private fun round(state: IntArray, m: IntArray) {
            // Columns
            g(state, 0, 4, 8, 12, m[0], m[1])
            g(state, 1, 5, 9, 13, m[2], m[3])
            g(state, 2, 6, 10, 14, m[4], m[5])
            g(state, 3, 7, 11, 15, m[6], m[7])
            // Diagonals
            g(state, 0, 5, 10, 15, m[8], m[9])
            g(state, 1, 6, 11, 12, m[10], m[11])
            g(state, 2, 7, 8, 13, m[12], m[13])
            g(state, 3, 4, 9, 14, m[14], m[15])
        }

        private fun g(s: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
            s[a] = s[a] + s[b] + mx
            s[d] = rotr(s[d] xor s[a], 16)
            s[c] = s[c] + s[d]
            s[b] = rotr(s[b] xor s[c], 12)
            s[a] = s[a] + s[b] + my
            s[d] = rotr(s[d] xor s[a], 8)
            s[c] = s[c] + s[d]
            s[b] = rotr(s[b] xor s[c], 7)
        }

        private fun rotr(w: Int, dist: Int): Int {
            return (w ushr dist) or (w shl (32 - dist))
        }

        private fun permute(m: IntArray): IntArray {
            val permuted = IntArray(16)
            for (i in 0 until 16) {
                permuted[i] = m[MSG_PERMUTATION[i]]
            }
            return permuted
        }
    }
}

private fun String.hexToByteArray(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) +
                Character.digit(this[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
