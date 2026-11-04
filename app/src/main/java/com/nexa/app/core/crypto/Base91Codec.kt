package com.nexa.app.core.crypto

import java.io.ByteArrayOutputStream

/**
 * Compact Base91 binary-to-text encoder and decoder.
 * Produces ~20% overhead compared to Base64's 33% overhead, ensuring maximum ciphertext
 * fits into strict telecom carrier SMS character envelopes when binary null bytes (0x00) are blocked.
 */
object Base91Codec {

    private val ENCODING_TABLE = byteArrayOf(
        'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte(), 'E'.code.toByte(),
        'F'.code.toByte(), 'G'.code.toByte(), 'H'.code.toByte(), 'I'.code.toByte(), 'J'.code.toByte(),
        'K'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte(), 'N'.code.toByte(), 'O'.code.toByte(),
        'P'.code.toByte(), 'Q'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
        'U'.code.toByte(), 'V'.code.toByte(), 'W'.code.toByte(), 'X'.code.toByte(), 'Y'.code.toByte(),
        'Z'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(),
        'e'.code.toByte(), 'f'.code.toByte(), 'g'.code.toByte(), 'h'.code.toByte(), 'i'.code.toByte(),
        'j'.code.toByte(), 'k'.code.toByte(), 'l'.code.toByte(), 'm'.code.toByte(), 'n'.code.toByte(),
        'o'.code.toByte(), 'p'.code.toByte(), 'q'.code.toByte(), 'r'.code.toByte(), 's'.code.toByte(),
        't'.code.toByte(), 'u'.code.toByte(), 'v'.code.toByte(), 'w'.code.toByte(), 'x'.code.toByte(),
        'y'.code.toByte(), 'z'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(),
        '3'.code.toByte(), '4'.code.toByte(), '5'.code.toByte(), '6'.code.toByte(), '7'.code.toByte(),
        '8'.code.toByte(), '9'.code.toByte(), '!'.code.toByte(), '#'.code.toByte(), '$'.code.toByte(),
        '%'.code.toByte(), '&'.code.toByte(), '('.code.toByte(), ')'.code.toByte(), '*'.code.toByte(),
        '+'.code.toByte(), ','.code.toByte(), '.'.code.toByte(), '/'.code.toByte(), ':'.code.toByte(),
        ';'.code.toByte(), '<'.code.toByte(), '='.code.toByte(), '>'.code.toByte(), '?'.code.toByte(),
        '@'.code.toByte(), '['.code.toByte(), ']'.code.toByte(), '^'.code.toByte(), '_'.code.toByte(),
        '`'.code.toByte(), '{'.code.toByte(), '|'.code.toByte(), '}'.code.toByte(), '~'.code.toByte(),
        '"'.code.toByte()
    )

    private val DECODING_TABLE = IntArray(256) { -1 }.apply {
        for (i in ENCODING_TABLE.indices) {
            this[ENCODING_TABLE[i].toInt() and 0xFF] = i
        }
    }

    fun encode(data: ByteArray): String {
        val out = ByteArrayOutputStream((data.size * 1.25).toInt() + 2)
        var ebq = 0
        var en = 0
        for (b in data) {
            ebq = ebq or ((b.toInt() and 0xFF) shl en)
            en += 8
            if (en > 13) {
                var ev = ebq and 8191
                if (ev > 88) {
                    ebq = ebq shr 13
                    en -= 13
                } else {
                    ev = ebq and 16383
                    ebq = ebq shr 14
                    en -= 14
                }
                out.write(ENCODING_TABLE[ev % 91].toInt())
                out.write(ENCODING_TABLE[ev / 91].toInt())
            }
        }
        if (en > 0) {
            out.write(ENCODING_TABLE[ebq % 91].toInt())
            if (en > 7 || ebq > 90) {
                out.write(ENCODING_TABLE[ebq / 91].toInt())
            }
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    fun decode(encoded: String): ByteArray {
        val bytes = encoded.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream(bytes.size)
        var dbq = 0
        var dn = 0
        var dv = -1

        for (b in bytes) {
            val c = DECODING_TABLE[b.toInt() and 0xFF]
            if (c == -1) continue
            if (dv == -1) {
                dv = c
            } else {
                dv += c * 91
                dbq = dbq or (dv shl dn)
                dn += if ((dv and 8191) > 88) 13 else 14
                do {
                    out.write(dbq and 0xFF)
                    dbq = dbq shr 8
                    dn -= 8
                } while (dn > 7)
                dv = -1
            }
        }
        if (dv != -1) {
            out.write((dbq or (dv shl dn)) and 0xFF)
        }
        return out.toByteArray()
    }
}
