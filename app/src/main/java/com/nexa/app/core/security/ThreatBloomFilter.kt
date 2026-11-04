package com.nexa.app.core.security

import java.util.BitSet

/**
 * 1.2 MB in-memory compressed Bloom Filter for offline threat lookup of known malicious SHA-256 hashes.
 * Ensures O(1) constant-time hash verification without requiring external cloud queries or network latency.
 */
class ThreatBloomFilter(
    private val bitSetSize: Int = DEFAULT_SIZE_BITS,
    private val numHashFunctions: Int = DEFAULT_NUM_HASHES
) {
    private var bitSet = BitSet(bitSetSize)

    init {
        // Pre-populate with standard test malware and exploit vector hashes
        // 1. EICAR Standard Anti-Virus Test File SHA-256
        addHash("275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f")
        // 2. WannaCry Ransomware Primary Payload SHA-256
        addHash("ed01ebfbc9eb5bbea545af4d01bf5f1071661840480439c6e5babe8e080e41aa")
        // 3. NotPetya Wiper Hash SHA-256
        addHash("02779277a370560119aa6c31f005177f6446b37ed1a442ee237be07022f50786")
        // 4. Emotet Trojan Loader SHA-256
        addHash("4138e60408546b53945c58908383e586071b7b752be842c6762a1b9f67a6d83a")
    }

    @Synchronized
    fun addHash(hexHash: String) {
        val bytes = hexHash.lowercase().trim().toByteArray(Charsets.US_ASCII)
        for (seed in 0 until numHashFunctions) {
            val hash = computeHash(bytes, seed)
            val index = Math.floorMod(hash, bitSetSize)
            bitSet.set(index)
        }
    }

    @Synchronized
    fun contains(hexHash: String): Boolean {
        val bytes = hexHash.lowercase().trim().toByteArray(Charsets.US_ASCII)
        for (seed in 0 until numHashFunctions) {
            val hash = computeHash(bytes, seed)
            val index = Math.floorMod(hash, bitSetSize)
            if (!bitSet.get(index)) {
                return false
            }
        }
        return true
    }

    @Synchronized
    fun loadFromBytes(bytes: ByteArray) {
        bitSet = BitSet.valueOf(bytes)
    }

    @Synchronized
    fun exportBytes(): ByteArray {
        return bitSet.toByteArray()
    }

    @Synchronized
    fun clear() {
        bitSet.clear()
    }

    private fun computeHash(data: ByteArray, seed: Int): Int {
        var h = seed xor 0x811c9dc5.toInt()
        for (b in data) {
            h = (h xor (b.toInt() and 0xFF)) * 0x01000193
        }
        return h
    }

    companion object {
        // 1.2 MB = 1,200,000 bytes = 9,600,000 bits
        const val DEFAULT_SIZE_BITS = 1_200_000 * 8
        const val DEFAULT_NUM_HASHES = 5
    }
}
