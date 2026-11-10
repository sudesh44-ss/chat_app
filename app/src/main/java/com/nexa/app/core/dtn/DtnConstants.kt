package com.nexa.app.core.dtn

/**
 * Constants governing Delay-Tolerant Networking (DTN) and Data Mule Operations.
 * Includes both modern NXDT Envelope Protocol (v1) and legacy compatibility constants.
 */
object DtnConstants {
    // --- NXDT Protocol Header Magic & Identification ---
    /** 4-byte Magic Identifier: ASCII 'NXDT' (0x4E, 0x58, 0x44, 0x54) */
    val MAGIC_BYTES: ByteArray = byteArrayOf(0x4E, 0x58, 0x44, 0x54)
    const val MAGIC_STRING: String = "NXDT"

    /** Active NXDT Protocol Version */
    const val PROTOCOL_VERSION_1: Byte = 1
    const val PROTOCOL_VERSION_LEGACY: Byte = 0

    // --- Envelope Types ---
    const val TYPE_STANDARD_DATA: Byte = 1
    const val TYPE_ACK_PACKET: Byte = 2
    const val TYPE_CONTROL_PACKET: Byte = 3

    // --- Cryptographic Algorithm Suites ---
    /** Suite 0x01: Ed25519 (RFC 8032) signature over SHA-256 bound payload */
    const val SUITE_ED25519_SHA256: Byte = 1

    // --- Binary Layout Dimensions ---
    /** Fixed Header + Signature Size for NXDT v1: 200 bytes */
    const val NXDT_HEADER_SIZE: Int = 200

    /** Size of Canonical Signing Data fed into Ed25519 signer: 124 bytes */
    const val CANONICAL_SIGNING_DATA_SIZE: Int = 124

    /** Mutable Relay Metadata Size: 12 bytes (HopCount, HopLimit, RelayTimestamp, RelayFlags) */
    const val MUTABLE_RELAY_METADATA_SIZE: Int = 12

    /** Legacy v0 Unsigned Envelope Header: 45 bytes */
    const val LEGACY_ENVELOPE_HEADER_SIZE: Int = 16 + 16 + 1 + 8 + 4 // 45 bytes
    const val ENVELOPE_HEADER_SIZE: Int = LEGACY_ENVELOPE_HEADER_SIZE // Alias for legacy code compatibility

    // --- Hop Limits & Storage Bounds ---
    /** Maximum allowable store-carry-forward hops (anti-amplification ceiling) */
    const val MAX_HOP_LIMIT: Byte = 15

    /** Default initial hop limit assigned to newly created envelopes */
    const val DEFAULT_HOP_LIMIT: Byte = 7

    /** Time-To-Live for cached envelopes: 48 hours in seconds (172,800 sec) */
    const val DEFAULT_TTL_SECONDS: Long = 48L * 3600L

    /** Maximum permitted TTL from creation: 7 days in seconds */
    const val MAX_TTL_SECONDS: Long = 7L * 86400L

    /** Maximum allowed clock drift into future for createdAt: 300 seconds (5 min) */
    const val MAX_FUTURE_SKEW_SECONDS: Long = 300L

    /** Maximum single packet size (Header + Payload): 64 KB (65,536 bytes) */
    const val MAX_PACKET_SIZE: Int = 64 * 1024

    /** Maximum payload size for DTN mesh */
    const val MAX_PAYLOAD_SIZE: Int = MAX_PACKET_SIZE - NXDT_HEADER_SIZE

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
}
