package com.nexa.app.core.sms

/**
 * Constants governing Port-Bound Data SMS operations.
 * Port 8901 bypasses the Android Telephony Provider (content://sms/inbox)
 * ensuring messages are 100% invisible to default SMS apps (Google Messages, Samsung SMS, Truecaller).
 */
object SmsConstants {
    /** Port bound for binary WAP-push/PDU messaging */
    const val DATA_SMS_PORT: Short = 8901

    /** 2-byte Magic header identifying NEXA Protocol: 'N' (0x4E), 'X' (0x58) */
    val MAGIC_BYTES = byteArrayOf(0x4E.toByte(), 0x58.toByte())

    /**
     * Wire header size:
     * - Magic Bytes: 2 bytes
     * - Message ID: 8 bytes (Long)
     * - Chunk Index: 1 byte
     * - Total Chunks: 1 byte
     * Total = 12 bytes
     */
    const val HEADER_SIZE = 12

    /** Maximum chunk payload size per single SMS PDU to guarantee <= 133 bytes */
    const val MAX_CHUNK_PAYLOAD_SIZE = 121

    /** Absolute maximum packet size: 12 bytes header + 121 bytes payload = 133 bytes */
    const val MAX_PACKET_SIZE = HEADER_SIZE + MAX_CHUNK_PAYLOAD_SIZE // 133 bytes

    /** Alias for maximum chunk/packet size for single PDU delivery */
    const val MAX_PAYLOAD_CHUNK_SIZE = MAX_PACKET_SIZE
}
