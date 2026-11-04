package com.nexa.app.core.dtn

import java.nio.charset.StandardCharsets
import java.util.BitSet

/**
 * High-performance 512-bit (64-byte) Bloom Filter.
 * Used during P2P connection handshakes over Google Nearby Connections (P2P_CLUSTER)
 * to compute set difference and suppress redundant payload transmission across mobile mules.
 */
class DtnBloomFilter(
    private val rawBits: ByteArray = ByteArray(DtnConstants.BLOOM_FILTER_BYTE_SIZE)
) {
    init {
        require(rawBits.size == DtnConstants.BLOOM_FILTER_BYTE_SIZE) {
            "Bloom filter byte array must be exactly ${DtnConstants.BLOOM_FILTER_BYTE_SIZE} bytes"
        }
    }

    /**
     * Adds an item key (e.g. envelopeIdHex) to the Bloom filter.
     */
    fun add(key: String) {
        add(key.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Adds raw byte key to the Bloom filter.
     */
    fun add(keyBytes: ByteArray) {
        val h1 = hashMurmur3(keyBytes, SEED_1) and BIT_MASK
        val h2 = hashMurmur3(keyBytes, SEED_2) and BIT_MASK
        val h3 = hashFnv1a(keyBytes) and BIT_MASK

        setBit(h1)
        setBit(h2)
        setBit(h3)
    }

    /**
     * Queries whether an item key might be present in the filter.
     * Guaranteed zero false negatives.
     */
    fun contains(key: String): Boolean {
        return contains(key.toByteArray(StandardCharsets.UTF_8))
    }

    fun contains(keyBytes: ByteArray): Boolean {
        val h1 = hashMurmur3(keyBytes, SEED_1) and BIT_MASK
        val h2 = hashMurmur3(keyBytes, SEED_2) and BIT_MASK
        val h3 = hashFnv1a(keyBytes) and BIT_MASK

        return isBitSet(h1) && isBitSet(h2) && isBitSet(h3)
    }

    /**
     * Returns a copy of the 64-byte raw bit array.
     */
    fun toByteArray(): ByteArray {
        return rawBits.clone()
    }

    private fun setBit(bitIndex: Int) {
        val byteIndex = bitIndex ushr 3 // bitIndex / 8
        val bitOffset = bitIndex and 0x07 // bitIndex % 8
        rawBits[byteIndex] = (rawBits[byteIndex].toInt() or (1 shl bitOffset)).toByte()
    }

    private fun isBitSet(bitIndex: Int): Boolean {
        val byteIndex = bitIndex ushr 3
        val bitOffset = bitIndex and 0x07
        return (rawBits[byteIndex].toInt() and (1 shl bitOffset)) != 0
    }

    companion object {
        private const val NUM_BITS = 512
        private const val BIT_MASK = NUM_BITS - 1 // 511

        private const val SEED_1 = 0x9747b28c.toInt()
        private const val SEED_2 = 0x5bd1e995.toInt()

        fun fromByteArray(bytes: ByteArray): DtnBloomFilter {
            require(bytes.size == DtnConstants.BLOOM_FILTER_BYTE_SIZE) {
                "Incoming Bloom filter payload must be exactly ${DtnConstants.BLOOM_FILTER_BYTE_SIZE} bytes"
            }
            return DtnBloomFilter(bytes.clone())
        }

        /**
         * 32-bit Murmur3 hash implementation.
         */
        private fun hashMurmur3(data: ByteArray, seed: Int): Int {
            var h = seed
            val len = data.size
            val nblocks = len / 4

            for (i in 0 until nblocks) {
                val index = i * 4
                var k = (data[index].toInt() and 0xFF) or
                        ((data[index + 1].toInt() and 0xFF) shl 8) or
                        ((data[index + 2].toInt() and 0xFF) shl 16) or
                        ((data[index + 3].toInt() and 0xFF) shl 24)

                k = k * 0xcc9e2d51.toInt()
                k = (k shl 15) or (k ushr 17)
                k = k * 0x1b873593.toInt()

                h = h xor k
                h = (h shl 13) or (h ushr 19)
                h = h * 5 + 0xe6546b64.toInt()
            }

            var k1 = 0
            val tailIndex = nblocks * 4
            val remainder = len and 3
            if (remainder == 3) {
                k1 = k1 xor ((data[tailIndex + 2].toInt() and 0xFF) shl 16)
            }
            if (remainder >= 2) {
                k1 = k1 xor ((data[tailIndex + 1].toInt() and 0xFF) shl 8)
            }
            if (remainder >= 1) {
                k1 = k1 xor (data[tailIndex].toInt() and 0xFF)
                k1 = k1 * 0xcc9e2d51.toInt()
                k1 = (k1 shl 15) or (k1 ushr 17)
                k1 = k1 * 0x1b873593.toInt()
                h = h xor k1
            }

            h = h xor len
            h = h xor (h ushr 16)
            h = h * 0x85ebca6b.toInt()
            h = h xor (h ushr 13)
            h = h * 0xc2b2ae35.toInt()
            h = h xor (h ushr 16)

            return h and 0x7FFFFFFF // Positive integer
        }

        /**
         * 32-bit FNV-1a hash implementation.
         */
        private fun hashFnv1a(data: ByteArray): Int {
            var hash = 0x811c9dc5.toInt()
            for (b in data) {
                hash = hash xor (b.toInt() and 0xFF)
                hash = hash * 0x01000193
            }
            return hash and 0x7FFFFFFF
        }
    }
}
