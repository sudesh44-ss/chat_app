package com.nexa.app.core.sms.nx1

import java.util.concurrent.ConcurrentHashMap

/** One inbound NX1 part as received from an SMS body. */
data class Nx1InboundPart(
    val messageId: String,
    val senderNxId: String,
    val partIndex: Int,
    val totalParts: Int,
    val payload: ByteArray,
    val receivedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Nx1InboundPart) return false
        return messageId == other.messageId &&
            senderNxId == other.senderNxId &&
            partIndex == other.partIndex &&
            totalParts == other.totalParts &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + senderNxId.hashCode()
        result = 31 * result + partIndex
        result = 31 * result + totalParts
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/** Persistence boundary for multipart reassembly (Room in production, in-memory in tests). */
interface SmsInboundPartStore {
    suspend fun insertIfAbsent(part: Nx1InboundPart): Boolean
    suspend fun partsFor(messageId: String, senderNxId: String): List<Nx1InboundPart>
    suspend fun deleteMessage(messageId: String, senderNxId: String)
    suspend fun purgeExpired(beforeTimestamp: Long): Int
    suspend fun sessionCountSince(sinceTimestamp: Long): Int
}

enum class SmsAssemblyStatus { INCOMPLETE, COMPLETE, DUPLICATE, REJECTED }

data class SmsAssemblyOutcome(
    val status: SmsAssemblyStatus,
    val assembled: ByteArray? = null,
    val totalParts: Int = 0,
    val receivedParts: Int = 0,
    val reason: String? = null
)

/**
 * Bounded, crash-tolerant multipart reassembly.
 *
 * Defences:
 *  - total parts bounded by Nx1.MAX_PARTS (4)
 *  - per part payload bounded by Nx1.MAX_CIPHERTEXT_BYTES (1024)
 *  - concurrent in-flight messages bounded (default 20)
 *  - total buffered bytes per message bounded (default 4096)
 *  - configurable TTL (default 24h) for incomplete messages
 *  - identical duplicate parts are idempotent; conflicting duplicates are rejected
 *  - the reassembled packet is returned intact; authentication happens after reassembly
 */
class SmsMultipartAssembler(
    private val store: SmsInboundPartStore,
    private val maxConcurrentMessages: Int = 20,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxTotalPayloadBytes: Int = 4096
) {

    suspend fun accept(part: Nx1InboundPart, now: Long = System.currentTimeMillis()): SmsAssemblyOutcome {
        if (part.totalParts !in 1..Nx1.MAX_PARTS) {
            return SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "INVALID_TOTAL_PARTS")
        }
        if (part.partIndex !in 0 until part.totalParts) {
            return SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "INVALID_PART_INDEX")
        }
        if (part.payload.isEmpty() || part.payload.size > Nx1.MAX_CIPHERTEXT_BYTES) {
            return SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "INVALID_PART_SIZE")
        }
        if (part.payload.size * part.totalParts > maxTotalPayloadBytes) {
            return SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "MESSAGE_TOO_LARGE")
        }

        store.purgeExpired(now - ttlMillis)

        val existing = store.partsFor(part.messageId, part.senderNxId)
        if (existing.isNotEmpty()) {
            if (existing.size >= maxConcurrentMessages) {
                return SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "BUFFER_BOUND_REACHED")
            }
            val previous = existing.firstOrNull { it.partIndex == part.partIndex }
            if (previous != null) {
                return if (previous.payload.contentEquals(part.payload)) {
                    SmsAssemblyOutcome(
                        SmsAssemblyStatus.DUPLICATE,
                        totalParts = part.totalParts,
                        receivedParts = existing.size
                    )
                } else {
                    // Same index, different bytes: corruption or an active attacker. Drop the message.
                    store.deleteMessage(part.messageId, part.senderNxId)
                    SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "CONFLICTING_PART")
                }
            }
            if (existing.any { it.totalParts != part.totalParts }) {
                store.deleteMessage(part.messageId, part.senderNxId)
                return SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "INCONSISTENT_TOTAL_PARTS")
            }
        } else {
            if (store.sessionCountSince(now - ttlMillis) >= maxConcurrentMessages) {
                return SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "TOO_MANY_CONCURRENT_MESSAGES")
            }
        }

        store.insertIfAbsent(part)
        val parts = store.partsFor(part.messageId, part.senderNxId)
        val bufferedBytes = parts.sumOf { it.payload.size }
        if (bufferedBytes > maxTotalPayloadBytes) {
            store.deleteMessage(part.messageId, part.senderNxId)
            return SmsAssemblyOutcome(SmsAssemblyStatus.REJECTED, reason = "MESSAGE_TOO_LARGE")
        }

        if (parts.size != part.totalParts) {
            return SmsAssemblyOutcome(
                SmsAssemblyStatus.INCOMPLETE,
                totalParts = part.totalParts,
                receivedParts = parts.size
            )
        }

        val ordered = parts.sortedBy { it.partIndex }
        for (i in 0 until part.totalParts) {
            if (ordered[i].partIndex != i) {
                return SmsAssemblyOutcome(
                    SmsAssemblyStatus.INCOMPLETE,
                    totalParts = part.totalParts,
                    receivedParts = parts.size
                )
            }
        }

        var total = 0
        for (p in ordered) total += p.payload.size
        val assembled = ByteArray(total)
        var offset = 0
        for (p in ordered) {
            System.arraycopy(p.payload, 0, assembled, offset, p.payload.size)
            offset += p.payload.size
        }
        return SmsAssemblyOutcome(
            status = SmsAssemblyStatus.COMPLETE,
            assembled = assembled,
            totalParts = part.totalParts,
            receivedParts = parts.size
        )
    }

    suspend fun discard(messageId: String, senderNxId: String) = store.deleteMessage(messageId, senderNxId)

    companion object {
        const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }
}

/** In-memory store used by unit tests and as a safety net when persistent storage is unavailable. */
class InMemorySmsInboundPartStore : SmsInboundPartStore {
    private val parts = ConcurrentHashMap<String, MutableList<Nx1InboundPart>>()

    private fun key(messageId: String, senderNxId: String) = "$senderNxId::$messageId"

    override suspend fun insertIfAbsent(part: Nx1InboundPart): Boolean {
        val list = parts.getOrPut(key(part.messageId, part.senderNxId)) { mutableListOf() }
        synchronized(list) {
            if (list.any { it.partIndex == part.partIndex }) return false
            list.add(part)
            return true
        }
    }

    override suspend fun partsFor(messageId: String, senderNxId: String): List<Nx1InboundPart> {
        val list = parts[key(messageId, senderNxId)] ?: return emptyList()
        synchronized(list) { return list.toList() }
    }

    override suspend fun deleteMessage(messageId: String, senderNxId: String) {
        parts.remove(key(messageId, senderNxId))
    }

    override suspend fun purgeExpired(beforeTimestamp: Long): Int {
        var removed = 0
        val iterator = parts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val oldest = entry.value.minOfOrNull { it.receivedAt } ?: continue
            if (oldest < beforeTimestamp) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    override suspend fun sessionCountSince(sinceTimestamp: Long): Int =
        parts.values.count { list -> list.any { it.receivedAt >= sinceTimestamp } }
}
