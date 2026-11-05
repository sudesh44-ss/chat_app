package com.nexa.app

import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.util.SafeMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Verification Suite for NEXA Master Security Standard:
 * "All User Input = Untrusted Data" Hardening.
 *
 * 1. Inbound Transports Threat Model (Wire Packet & Fixed-Size Parsing Bounds)
 * 2. Path Traversal & Scoped Storage Hardening (Content-Addressed Storage)
 * 3. Client-Side Smart Features (Regex DoS & Intent Injection Neutralization)
 * 4. Rich Text & Markdown Rendering (Zero HTML Execution)
 * 5. Memory-Level Buffer Safety (Zero-Allocation Input Bounds & Control Stripping)
 */
class SafeInputHardeningUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // 1. Inbound Transports Threat Model (Bounds Checking & Deserialization)
    // -------------------------------------------------------------------------

    @Test
    fun wirePacket_rejectsOversizedPacket_exceedingMaxBounds() {
        val oversizedBytes = ByteArray(NexaWirePacket.MAX_PACKET_SIZE + 10)
        try {
            NexaWirePacket.decode(oversizedBytes)
            fail("Expected IllegalArgumentException on oversized wire packet")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("exceeds maximum allowable bound") == true)
        }
    }

    @Test
    fun wirePacket_rejectsCorruptedLengthPrefix_preventingOomAllocation() {
        // Construct malformed length-delimited field with gigantic length (e.g. 100 MB)
        val out = ByteArrayOutputStream()
        // Tag for field 1 (packetId), wire type 2 (length-delimited): (1 shl 3) or 2 = 10 (0x0A)
        out.write(0x0A)
        // Varint for 100,000,000 bytes: (0x80 | 0x00)...
        var v = 100_000_000L
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                break
            } else {
                out.write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
        }
        val malformedBytes = out.toByteArray()

        try {
            NexaWirePacket.decode(malformedBytes)
            fail("Expected IllegalArgumentException on out-of-bounds field length")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("violates bounds") == true)
        }
    }

    // -------------------------------------------------------------------------
    // 2. Path Traversal & Scoped Storage (Strict Content-Addressed Storage)
    // -------------------------------------------------------------------------

    @Test
    fun pathTraversal_sanitizesUnsafeFilenames_strippingTraversalSequences() {
        val malicious1 = "../../../../etc/passwd"
        val sanitized1 = SafeMessageParser.sanitizeFileName(malicious1)
        assertEquals("passwd", sanitized1)
        assertFalse(sanitized1.contains("/"))
        assertFalse(sanitized1.contains("\\"))
        assertFalse(sanitized1.contains(".."))

        val malicious2 = "..\\..\\windows\\system32\\cmd.exe"
        val sanitized2 = SafeMessageParser.sanitizeFileName(malicious2)
        assertEquals("cmd.exe", sanitized2)

        val malicious3 = ".hidden_exploit.sh"
        val sanitized3 = SafeMessageParser.sanitizeFileName(malicious3)
        assertEquals("_.hidden_exploit.sh", sanitized3)
    }

    @Test
    fun contentAddressedStorage_locksFilesInsideVaultDirectory() {
        val vaultDir = tempFolder.newFolder("vault_safe")
        val sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val file = SafeMessageParser.getContentAddressedFile(vaultDir, sha256, "bin")
        assertEquals(File(vaultDir, "$sha256.bin").canonicalPath, file.canonicalPath)
        assertTrue(file.canonicalPath.startsWith(vaultDir.canonicalPath))

        // Malicious hash attempting directory traversal
        val traversalHash = "../../escaped_secret"
        val safeTraversedFile = SafeMessageParser.getContentAddressedFile(vaultDir, traversalHash, "bin")
        // Digits/letters are filtered, stripping slashes and dots
        assertEquals(File(vaultDir, "escapedsecret.bin").canonicalPath, safeTraversedFile.canonicalPath)
        assertTrue(safeTraversedFile.canonicalPath.startsWith(vaultDir.canonicalPath))
    }

    // -------------------------------------------------------------------------
    // 3. Client-Side Smart Features (Regex DoS & Intent Injection)
    // -------------------------------------------------------------------------

    @Test
    fun intentInjection_strictlyWhitelistsHttpAndHttps_rejectsUnsafeSchemes() {
        assertTrue(SafeMessageParser.isSafeWebUrl("https://nexa.network"))
        assertTrue(SafeMessageParser.isSafeWebUrl("http://localhost:8080/api"))
        assertTrue(SafeMessageParser.isSafeWebUrl("https://github.com/sudesh44-ss/chat_app"))

        // Unsafe / Malicious Schemes must be rejected
        assertFalse(SafeMessageParser.isSafeWebUrl("intent://settings#Intent;action=android.settings.SETTINGS;end"))
        assertFalse(SafeMessageParser.isSafeWebUrl("content://com.android.providers.telephony/siminfo"))
        assertFalse(SafeMessageParser.isSafeWebUrl("file:///data/data/com.nexa.app/databases/nexa.db"))
        assertFalse(SafeMessageParser.isSafeWebUrl("javascript:alert(document.cookie)"))
        assertFalse(SafeMessageParser.isSafeWebUrl("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="))
        assertFalse(SafeMessageParser.isSafeWebUrl("about:blank"))
        assertFalse(SafeMessageParser.isSafeWebUrl("tel:911"))
    }

    @Test(timeout = 2000)
    fun reDoS_resilience_linearTimeExecutionOnRepetitiveInput() {
        // Construct long attacker payload that would freeze catastrophic backtracking regexes
        val evilString = "*".repeat(5000) + "a".repeat(5000) + "_".repeat(5000)
        val startTime = System.currentTimeMillis()
        val result = SafeMessageParser.parseMarkdownToAnnotatedString(evilString)
        val elapsed = System.currentTimeMillis() - startTime

        assertNotNull(result)
        // Should complete in a few milliseconds (well under 500ms)
        assertTrue("Execution must be linear time, took ${elapsed}ms", elapsed < 500)
    }

    // -------------------------------------------------------------------------
    // 4. Rich Text & Markdown (Zero HTML Execution)
    // -------------------------------------------------------------------------

    @Test
    fun markdown_rendersFormattedSpans_andRendersHtmlTagsAsLiteralText() {
        val mixedMessage = "Hello *bold text* and _italic text_ and ~strikethrough~ and `code block`! Also check https://nexa.network."
        val annotated = SafeMessageParser.parseMarkdownToAnnotatedString(mixedMessage)

        assertNotNull(annotated)
        assertTrue(annotated.text.contains("bold text"))
        assertTrue(annotated.text.contains("italic text"))
        assertTrue(annotated.text.contains("strikethrough"))
        assertTrue(annotated.text.contains("code block"))
        assertTrue(annotated.text.contains("https://nexa.network"))

        // Malicious HTML tags must NOT execute or disappear: they must remain literal inert characters
        val xssPayload = "<script>alert('pwned');</script><iframe src=\"malware.com\"></iframe>"
        val safeHtmlAnnotated = SafeMessageParser.parseMarkdownToAnnotatedString(xssPayload)
        assertEquals(xssPayload, safeHtmlAnnotated.text)
    }

    // -------------------------------------------------------------------------
    // 5. Memory-Level Buffer Safety (Zero-Allocation Input Bounds)
    // -------------------------------------------------------------------------

    @Test
    fun bufferBounds_clampsExcessiveInput_toMax4096Chars() {
        val massiveInput = "A".repeat(100_000)
        val sanitized = SafeMessageParser.sanitizeInputText(massiveInput)
        assertEquals(SafeMessageParser.MAX_INPUT_CHARS, sanitized.length)
        assertEquals(4096, sanitized.length)
    }

    @Test
    fun bufferBounds_stripsDangerousControlCharacters_preventingBidiSpoofing() {
        // Null byte, backspace, escape, and bell
        val dirty = "Hello\u0000\u0007\u0008World\u001B!\nLine 2"
        val clean = SafeMessageParser.sanitizeInputText(dirty)
        // \n is preserved, but 0, 7, 8, 27 are stripped
        assertEquals("HelloWorld!\nLine 2", clean)
    }
}
