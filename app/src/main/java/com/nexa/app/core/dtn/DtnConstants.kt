package com.nexa.app.core.dtn

/**
 * Constants governing Delay-Tolerant Networking (DTN) and Data Mule Operations.
 */
object DtnConstants {
    /** Maximum number of store-carry-forward hops before envelope is dropped */
    const val DEFAULT_HOP_LIMIT: Byte = 7

    /** Time-To-Live for cached envelopes: 48 hours in seconds (172,800 sec) */
    const val DEFAULT_TTL_SECONDS: Long = 48L * 3600L

    /** Strict FIFO storage quota: Maximum number of third-party envelopes held per node */
    const val MAX_CACHE_ENVELOPE_COUNT: Int = 500

    /** Storage quota in bytes: 50 Megabytes */
    const val MAX_STORAGE_BYTES: Long = 50L * 1024L * 1024L

    /** Number of oldest envelopes pruned when the FIFO threshold is reached */
    const val PRUNE_BATCH_SIZE: Int = 50

    /** Size of Bloom filter exchanged during connection handshake: 64 bytes = 512 bits */
    const val BLOOM_FILTER_BYTE_SIZE: Int = 64

    /** Service identifier for Google Nearby Connections (P2P_CLUSTER) */
    const val SERVICE_ID: String = "com.nexa.mesh.dtn"

    /** Envelope header size: UUID (16) + BlindToken (16) + HopLimit (1) + Expiry (8) + PayloadLength (4) */
    const val ENVELOPE_HEADER_SIZE: Int = 16 + 16 + 1 + 8 + 4 // 45 bytes
}
