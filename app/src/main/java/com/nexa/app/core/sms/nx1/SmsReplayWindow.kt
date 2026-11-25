package com.nexa.app.core.sms.nx1

/**
 * Replay protection for the SMS transport.
 *
 * Each direction (messages, ACKs) has its own counter space. A packet is accepted when its
 * counter is above the high-water mark, or inside the 64-slot window and not yet seen.
 * Anything older, or a repeat, is rejected. Out-of-order delivery is therefore supported
 * up to 64 positions; delayed packets beyond that window are treated as replays (safe default).
 */
class SmsReplayWindow(
    highestCounter: Long = -1L,
    bitmap: Long = 0L
) {
    private var highest: Long = highestCounter
    private var seen: Long = bitmap

    fun snapshotHighest(): Long = highest

    fun snapshotBitmap(): Long = seen

    fun serialize(): String = "$highest:$seen"

    /** @return true when the counter is fresh (and consumes it); false on replay or stale packet. */
    @Synchronized
    fun accept(counter: Long): Boolean {
        if (counter < 0L) return false

        if (counter > highest) {
            val shift = counter - highest
            seen = if (shift >= 64) 1L else (seen shl shift.toInt()) or 1L
            highest = counter
            return true
        }

        val offset = highest - counter
        if (offset >= 64) return false
        val mask = 1L shl offset.toInt()
        if (seen and mask != 0L) return false
        seen = seen or mask
        return true
    }

    companion object {
        fun parse(serialized: String?): SmsReplayWindow {
            if (serialized.isNullOrBlank()) return SmsReplayWindow()
            val parts = serialized.split(':')
            if (parts.size != 2) return SmsReplayWindow()
            val highest = parts[0].toLongOrNull() ?: -1L
            val bitmap = parts[1].toLongOrNull() ?: 0L
            return SmsReplayWindow(highest, bitmap)
        }
    }
}
