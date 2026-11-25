package com.nexa.app.core.sms.nx1

import java.security.SecureRandom

/**
 * NX1 — NEXA encrypted SMS packet protocol (version 1).
 *
 * Wire form (ASCII only, therefore GSM-7 encodable):
 *
 *   message : NX1:1:<MESSAGE_ID>:<PART_INDEX>/<TOTAL_PARTS>:<BASE64URL_PAYLOAD>
 *   ack     : NX1:2:<MESSAGE_ID>:ACK:<BASE64URL(COUNTER || AUTH_TAG)>
 *
 * - MESSAGE_ID is 12 random bytes rendered as 16 base64url characters.
 * - BASE64URL is unpadded, strict RFC 4648 §5 alphabet (`A-Z a-z 0-9 - _`).
 * - Payload is opaque ciphertext; never plaintext, never a key.
 *
 * All limits are hard limits: a parser that exceeds them rejects the packet instead of
 * allocating memory. See Nx1Crypto for the payload layout.
 */
object Nx1 {
    const val PREFIX = "NX1"

    /** Packet versions. v1 = chat message, v2 = application delivery ACK. */
    const val VERSION_MESSAGE = 1
    const val VERSION_ACK = 2

    const val MESSAGE_ID_BYTES = 12
    const val MESSAGE_ID_CHARS = 16

    /** Maximum number of SMS parts a single NX1 message may occupy. */
    const val MAX_PARTS = 4

    /** Maximum ciphertext payload bytes carried by one part. */
    const val MAX_CIPHERTEXT_BYTES = 1024

    /** Maximum plaintext bytes accepted for encryption (pre-overhead). */
    const val MAX_PLAINTEXT_BYTES = 900

    /** Maximum characters of one NX1 token. */
    const val MAX_PART_TOKEN_CHARS = 512

    /** Maximum characters of an SMS body that will be scanned for NX1 tokens. */
    const val MAX_BODY_SCAN_CHARS = 4096

    /** Maximum NX1 tokens honoured from a single SMS body. */
    const val MAX_TOKENS_PER_BODY = 8

    const val ACK_PAYLOAD_BYTES = 8 + 16 // counter || tag
    const val ACK_PAYLOAD_CHARS = 32
}

/** Strict unpadded base64url codec (RFC 4648 §5). Rejects anything outside the alphabet. */
object Nx1Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val DECODE = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, c -> this[c.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 0x3F])
            out.append(ALPHABET[(n ushr 12) and 0x3F])
            out.append(ALPHABET[(n ushr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
                out.append(ALPHABET[(n ushr 6) and 0x3F])
            }
        }
        return out.toString()
    }

    /** @return decoded bytes, or null on any malformed input (padding, illegal char, impossible length). */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        if (text.length % 4 == 1) return null
        val out = ByteArray(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in text) {
            if (c.code >= 128) return null
            val v = DECODE[c.code]
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[index++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return if (index == out.size) out else out.copyOf(index)
    }
}

data class Nx1MessagePacket(
    val version: Int = Nx1.VERSION_MESSAGE,
    val messageId: String,
    val partIndex: Int,
    val totalParts: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Nx1MessagePacket) return false
        return version == other.version &&
            messageId == other.messageId &&
            partIndex == other.partIndex &&
            totalParts == other.totalParts &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + messageId.hashCode()
        result = 31 * result + partIndex
        result = 31 * result + totalParts
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class Nx1AckPacket(
    val messageId: String,
    val counter: Long,
    val authTag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Nx1AckPacket) return false
        return messageId == other.messageId &&
            counter == other.counter &&
            authTag.contentEquals(other.authTag)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + counter.hashCode()
        result = 31 * result + authTag.contentHashCode()
        return result
    }
}

sealed class Nx1ParseResult {
    data class Message(val packet: Nx1MessagePacket) : Nx1ParseResult()
    data class Ack(val packet: Nx1AckPacket) : Nx1ParseResult()
    data class Invalid(val reason: String) : Nx1ParseResult()
}

/**
 * Strict NX1 codec. Encoding throws on programmer error (oversized payload, bad part math);
 * decoding never throws and never accepts a partial or ambiguous packet.
 */
object Nx1Codec {

    fun newMessageId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(Nx1.MESSAGE_ID_BYTES)
        random.nextBytes(bytes)
        return Nx1Base64Url.encode(bytes)
    }

    fun encodeMessage(packet: Nx1MessagePacket): String {
        require(packet.version == Nx1.VERSION_MESSAGE) { "Unsupported NX1 message version" }
        require(isValidMessageId(packet.messageId)) { "Invalid NX1 message id" }
        require(packet.totalParts in 1..Nx1.MAX_PARTS) { "Invalid NX1 total parts" }
        require(packet.partIndex in 0 until packet.totalParts) { "Invalid NX1 part index" }
        require(packet.payload.isNotEmpty() && packet.payload.size <= Nx1.MAX_CIPHERTEXT_BYTES) {
            "Invalid NX1 payload length"
        }
        val token = buildString {
            append(Nx1.PREFIX).append(':')
            append(Nx1.VERSION_MESSAGE).append(':')
            append(packet.messageId).append(':')
            append(packet.partIndex + 1).append('/').append(packet.totalParts).append(':')
            append(Nx1Base64Url.encode(packet.payload))
        }
        require(token.length <= Nx1.MAX_PART_TOKEN_CHARS) { "NX1 token exceeds maximum length" }
        return token
    }

    fun encodeAck(packet: Nx1AckPacket): String {
        require(isValidMessageId(packet.messageId)) { "Invalid NX1 message id" }
        require(packet.authTag.size == 16) { "NX1 ACK tag must be 16 bytes" }
        val payload = ByteArray(Nx1.ACK_PAYLOAD_BYTES)
        for (i in 0 until 8) {
            payload[i] = ((packet.counter ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        System.arraycopy(packet.authTag, 0, payload, 8, 16)
        val token = "${Nx1.PREFIX}:${Nx1.VERSION_ACK}:${packet.messageId}:ACK:${Nx1Base64Url.encode(payload)}"
        require(token.length <= Nx1.MAX_PART_TOKEN_CHARS) { "NX1 ACK token exceeds maximum length" }
        return token
    }

    fun parse(token: String): Nx1ParseResult {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return Nx1ParseResult.Invalid("EMPTY")
        if (trimmed.length > Nx1.MAX_PART_TOKEN_CHARS) return Nx1ParseResult.Invalid("OVERSIZED_TOKEN")
        if (!trimmed.startsWith("${Nx1.PREFIX}:")) return Nx1ParseResult.Invalid("NOT_NX1")

        val fields = trimmed.split(':')
        if (fields.size != 5) return Nx1ParseResult.Invalid("MALFORMED_FIELD_COUNT")
        if (fields[0] != Nx1.PREFIX) return Nx1ParseResult.Invalid("NOT_NX1")

        val version = fields[1].toIntOrNull() ?: return Nx1ParseResult.Invalid("MALFORMED_VERSION")
        val messageId = fields[2]
        if (!isValidMessageId(messageId)) return Nx1ParseResult.Invalid("MALFORMED_MESSAGE_ID")

        return when (version) {
            Nx1.VERSION_MESSAGE -> parseMessage(messageId, fields[3], fields[4])
            Nx1.VERSION_ACK -> parseAck(messageId, fields[3], fields[4])
            else -> Nx1ParseResult.Invalid("UNSUPPORTED_VERSION")
        }
    }

    private fun parseMessage(messageId: String, partInfo: String, payloadText: String): Nx1ParseResult {
        val partFields = partInfo.split('/')
        if (partFields.size != 2) return Nx1ParseResult.Invalid("MALFORMED_PART_INFO")
        val humanIndex = partFields[0].toIntOrNull() ?: return Nx1ParseResult.Invalid("MALFORMED_PART_INDEX")
        val totalParts = partFields[1].toIntOrNull() ?: return Nx1ParseResult.Invalid("MALFORMED_PART_TOTAL")
        if (totalParts !in 1..Nx1.MAX_PARTS) return Nx1ParseResult.Invalid("INVALID_TOTAL_PARTS")
        if (humanIndex !in 1..totalParts) return Nx1ParseResult.Invalid("INVALID_PART_INDEX")
        val payload = Nx1Base64Url.decode(payloadText) ?: return Nx1ParseResult.Invalid("MALFORMED_ENCODING")
        if (payload.isEmpty()) return Nx1ParseResult.Invalid("EMPTY_PAYLOAD")
        if (payload.size > Nx1.MAX_CIPHERTEXT_BYTES) return Nx1ParseResult.Invalid("OVERSIZED_PAYLOAD")
        return Nx1ParseResult.Message(
            Nx1MessagePacket(
                version = Nx1.VERSION_MESSAGE,
                messageId = messageId,
                partIndex = humanIndex - 1,
                totalParts = totalParts,
                payload = payload
            )
        )
    }

    private fun parseAck(messageId: String, partInfo: String, payloadText: String): Nx1ParseResult {
        if (partInfo != "ACK") return Nx1ParseResult.Invalid("MALFORMED_PART_INFO")
        if (payloadText.length != Nx1.ACK_PAYLOAD_CHARS) return Nx1ParseResult.Invalid("MALFORMED_ACK_LENGTH")
        val payload = Nx1Base64Url.decode(payloadText) ?: return Nx1ParseResult.Invalid("MALFORMED_ENCODING")
        if (payload.size != Nx1.ACK_PAYLOAD_BYTES) return Nx1ParseResult.Invalid("MALFORMED_ACK_LENGTH")
        var counter = 0L
        for (i in 0 until 8) {
            counter = (counter shl 8) or (payload[i].toLong() and 0xFF)
        }
        if (counter < 0) return Nx1ParseResult.Invalid("INVALID_ACK_COUNTER")
        return Nx1ParseResult.Ack(
            Nx1AckPacket(
                messageId = messageId,
                counter = counter,
                authTag = payload.copyOfRange(8, Nx1.ACK_PAYLOAD_BYTES)
            )
        )
    }

    /**
     * Extracts every NX1-looking token from an SMS body. Splitting is whitespace/comma based,
     * because concatenated multipart SMS arrive space separated. Non-NX1 text is ignored.
     */
    fun extractTokens(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val scan = if (body.length > Nx1.MAX_BODY_SCAN_CHARS) body.substring(0, Nx1.MAX_BODY_SCAN_CHARS) else body
        val tokens = ArrayList<String>(2)
        var index = 0
        while (index < scan.length && tokens.size < Nx1.MAX_TOKENS_PER_BODY) {
            val start = scan.indexOf("${Nx1.PREFIX}:", index)
            if (start < 0) break
            var end = start
            while (end < scan.length && !scan[end].isWhitespace() && scan[end] != ',' && scan[end] != ';') end++
            val token = scan.substring(start, end)
            if (token.length in 8..Nx1.MAX_PART_TOKEN_CHARS) tokens.add(token)
            index = if (end > start) end else start + 1
        }
        return tokens
    }

    fun parseBody(body: String): List<Nx1ParseResult> = extractTokens(body).map { parse(it) }

    /** Message ids are exactly 16 base64url characters (96 bits of entropy). */
    fun isValidMessageId(messageId: String): Boolean {
        if (messageId.length != Nx1.MESSAGE_ID_CHARS) return false
        val decoded = Nx1Base64Url.decode(messageId) ?: return false
        return decoded.size == Nx1.MESSAGE_ID_BYTES
    }
}
