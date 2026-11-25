package com.nexa.app.core.sms.nx1

/**
 * Real SMS segment accounting.
 *
 * Never hardcodes 160 as a universal limit:
 *  - GSM-7 (default alphabet, no escapes): 160 chars single, 153 per part concatenated.
 *  - GSM-7 with extension characters (^{}\[~]|€): those cost 2 septets.
 *  - UCS-2 (any character outside GSM-7, e.g. emoji, Devanagari, CJK): 70 chars single, 67 concatenated.
 *
 * NX1 packets are pure ASCII from the base64url alphabet, therefore GSM-7 in practice.
 * When Android's SmsManager is available the caller should prefer `SmsManager.divideMessage`,
 * which is authoritative for the device; this calculator is the deterministic fallback and the
 * implementation used by unit tests.
 */
object SmsSegmentCalculator {

    const val GSM7_SINGLE = 160
    const val GSM7_MULTI = 153
    const val UCS2_SINGLE = 70
    const val UCS2_MULTI = 67

    /** Default user-visible safety limit: never silently exceed this many network segments. */
    const val DEFAULT_SEGMENT_SAFETY_LIMIT = 4

    /** GSM-7 default alphabet (3GPP TS 23.038). `$` and `¥` are listed literally. */
    private val GSM7_BASIC: String =
        "@£" + "\u0024" + "¥èéùìòÇ\nØø\rÅåΔ_" +
            "ΦΓΛΩΠΨΣΘΞÆæßÉ " +
            "!\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿" +
            "abcdefghijklmnopqrstuvwxyzäöñüà"

    /** GSM-7 extension characters; each costs two septets (escape + character). */
    private val GSM7_EXTENSION_CHARS: Set<Char> = setOf(
        '\u000C', '^', '{', '}', '\\', '[', '~', ']', '|', '\u20AC'
    )

    fun isGsm7(text: String): Boolean =
        text.all { GSM7_BASIC.indexOf(it) >= 0 || GSM7_EXTENSION_CHARS.contains(it) }

    /** GSM-7 septet cost: extension characters occupy two septets (escape + character). */
    fun gsm7Septets(text: String): Int {
        var count = 0
        for (c in text) {
            if (GSM7_EXTENSION_CHARS.contains(c)) count += 2 else count += 1
        }
        return count
    }

    fun segmentCount(text: String): Int {
        if (text.isEmpty()) return 0
        return if (isGsm7(text)) {
            val septets = gsm7Septets(text)
            when {
                septets <= GSM7_SINGLE -> 1
                else -> (septets + GSM7_MULTI - 1) / GSM7_MULTI
            }
        } else {
            val units = utf16Units(text)
            when {
                units <= UCS2_SINGLE -> 1
                else -> (units + UCS2_MULTI - 1) / UCS2_MULTI
            }
        }
    }

    /** Maximum characters that still fit within [segments] network SMS. */
    fun maxCharsForSegments(segments: Int, gsm7: Boolean = true): Int {
        if (segments <= 0) return 0
        if (segments == 1) return if (gsm7) GSM7_SINGLE else UCS2_SINGLE
        return if (gsm7) segments * GSM7_MULTI else segments * UCS2_MULTI
    }

    /** Number of UTF-16 code units (surrogate pairs count as 2, matching UCS-2 accounting). */
    fun utf16Units(text: String): Int = text.length
}
