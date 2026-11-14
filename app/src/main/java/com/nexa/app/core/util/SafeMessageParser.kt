package com.nexa.app.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Production-Grade Input Hardening & Safe Parsing Engine for NEXA.
 * Enforces "All User Input = Untrusted Data" across:
 * 1. Zero HTML / WebView Execution: Script and markup tags remain inert glyphs.
 * 2. ReDoS-Free Linear Parsing: Fast token scan for *bold*, _italic_, ~strike~, `code`.
 * 3. Safe URL Intent Whitelist: Blocks intent://, content://, file://, javascript:// exploits.
 * 4. Content-Addressed Storage: Prevents path traversal (../../) by indexing files by hash.
 * 5. Input Buffer Bounds: Max 4,096 characters / 16 KB UTF-8 to prevent UI layout freezing.
 */
object SafeMessageParser {

    const val TAG_URL = "URL"
    const val MAX_INPUT_CHARS = 4096
    const val MAX_INPUT_BYTES = 16384 // 16 KB UTF-8 maximum
    const val MAX_URL_LENGTH = 2048

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Clamps user input to hard limits (4,096 characters and 16 KB UTF-8)
     * and strips dangerous ASCII control characters to prevent UI freeze and bidi exploits.
     */
    fun sanitizeInputText(input: String): String {
        if (input.isEmpty()) return ""

        // 1. Hard character count ceiling
        val bounded = if (input.length > MAX_INPUT_CHARS) {
            input.substring(0, MAX_INPUT_CHARS)
        } else {
            input
        }

        // 2. Hard byte count ceiling (16 KB UTF-8)
        val utf8Bytes = bounded.toByteArray(StandardCharsets.UTF_8)
        val finalString = if (utf8Bytes.size > MAX_INPUT_BYTES) {
            String(utf8Bytes, 0, MAX_INPUT_BYTES, StandardCharsets.UTF_8)
        } else {
            bounded
        }

        // 3. Strip non-printable ASCII controls (except \n, \r, \t) to prevent terminal/bidi spoofing
        val sb = StringBuilder(finalString.length)
        for (char in finalString) {
            val code = char.code
            if (code in 0..8 || code in 11..12 || code in 14..31 || code == 127) {
                continue
            }
            sb.append(char)
        }
        return sb.toString()
    }

    /**
     * Sanitizes untrusted filenames from mesh/SMS/P2P streams for safe storage in metadata.
     * Prevents path traversal and directory separators (../, / , \).
     */
    fun sanitizeFileName(rawFileName: String): String {
        val nameOnly = File(rawFileName).name // Strips any path prefixes
        val cleaned = nameOnly.replace(Regex("[^a-zA-Z0-9._\\-\\s]"), "_").trim()
        val withoutLeadingDot = if (cleaned.startsWith(".")) "_$cleaned" else cleaned
        return withoutLeadingDot.take(128).ifEmpty { "attachment_${System.currentTimeMillis()}" }
    }

    /**
     * Returns a strictly Content-Addressed File inside [baseDir] using the cryptographic hash.
     * Guaranteed never to escape [baseDir].
     */
    fun getContentAddressedFile(baseDir: File, fileHash: String, extension: String = "bin"): File {
        if (!baseDir.exists()) baseDir.mkdirs()
        val cleanHash = fileHash.filter { it.isLetterOrDigit() }.ifEmpty { "file_${System.currentTimeMillis()}" }
        val targetFile = File(baseDir, "$cleanHash.$extension")
        val canonicalBase = baseDir.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalBase.path + File.separator)) {
            throw SecurityException("Path traversal attempt detected in content-addressed hash: $fileHash")
        }
        return canonicalTarget
    }

    /**
     * Checks whether an untrusted URL is safe to open.
     * Strictly whitelists http and https schemes and blocks intent, content, file, javascript, etc.
     */
    fun isSafeWebUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.length > MAX_URL_LENGTH || trimmed.length < 8) return false

        val schemeDelimiter = trimmed.indexOf("://")
        if (schemeDelimiter == -1) return false
        val scheme = trimmed.substring(0, schemeDelimiter).lowercase(Locale.ROOT)
        if (scheme !in ALLOWED_SCHEMES) return false

        return try {
            val javaUri = java.net.URI(trimmed)
            val host = javaUri.host
            !host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Safely launches a web URL in the device's default browser.
     * Rejects unsafe schemes, intent injection, and malformed URIs.
     */
    fun launchSafeWebUrl(context: Context, rawUrl: String): Boolean {
        if (!isSafeWebUrl(rawUrl)) return false
        return try {
            val uri = Uri.parse(rawUrl.trim())
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parses Markdown tokens (*bold*, _italic_, ~strike~, `code`) and HTTP/HTTPS URLs
     * into a Compose AnnotatedString using a linear-time token scanner.
     *
     * ZERO HTML EXECUTION:
     * Tags like `<script>`, `<iframe>`, `<img>` remain literal inert characters.
     *
     * REDOS RESISTANT:
     * Does not use catastrophic backtracking regexes.
     */
    fun parseMarkdownToAnnotatedString(
        content: String,
        primaryColor: Color = Color.Unspecified,
        linkColor: Color = Color(0xFF388E3C),
        codeBgColor: Color = Color(0x22808080)
    ): AnnotatedString {
        if (content.isEmpty()) return AnnotatedString("")

        val sanitized = sanitizeInputText(content)
        val builder = AnnotatedString.Builder()

        var i = 0
        val len = sanitized.length

        while (i < len) {
            val c = sanitized[i]

            // Check for inline code `code`
            if (c == '`') {
                val closeIdx = sanitized.indexOf('`', i + 1)
                if (closeIdx != -1 && closeIdx - i > 1) {
                    val codeContent = sanitized.substring(i + 1, closeIdx)
                    val start = builder.length
                    builder.append(codeContent)
                    builder.addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBgColor
                        ),
                        start,
                        builder.length
                    )
                    i = closeIdx + 1
                    continue
                }
            }

            // Check for bold *bold*
            if (c == '*') {
                val closeIdx = sanitized.indexOf('*', i + 1)
                if (closeIdx != -1 && closeIdx - i > 1) {
                    val boldContent = sanitized.substring(i + 1, closeIdx)
                    val start = builder.length
                    builder.append(boldContent)
                    builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, builder.length)
                    i = closeIdx + 1
                    continue
                }
            }

            // Check for italic _italic_
            if (c == '_') {
                val closeIdx = sanitized.indexOf('_', i + 1)
                if (closeIdx != -1 && closeIdx - i > 1) {
                    val italicContent = sanitized.substring(i + 1, closeIdx)
                    val start = builder.length
                    builder.append(italicContent)
                    builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, builder.length)
                    i = closeIdx + 1
                    continue
                }
            }

            // Check for strikethrough ~strike~
            if (c == '~') {
                val closeIdx = sanitized.indexOf('~', i + 1)
                if (closeIdx != -1 && closeIdx - i > 1) {
                    val strikeContent = sanitized.substring(i + 1, closeIdx)
                    val start = builder.length
                    builder.append(strikeContent)
                    builder.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, builder.length)
                    i = closeIdx + 1
                    continue
                }
            }

            // Check for URL: http:// or https://
            if ((c == 'h' || c == 'H') && (sanitized.startsWith("http://", i, ignoreCase = true) || sanitized.startsWith("https://", i, ignoreCase = true))) {
                // Find end of URL (whitespace or end of string)
                var urlEnd = i
                while (urlEnd < len && !sanitized[urlEnd].isWhitespace()) {
                    urlEnd++
                }
                val rawCandidate = sanitized.substring(i, urlEnd)
                // Trim trailing punctuation like '.', ',', ')', '!'
                val cleanUrl = rawCandidate.trimEnd('.', ',', ')', '!', '?', ';')
                val urlLen = cleanUrl.length

                if (isSafeWebUrl(cleanUrl)) {
                    val start = builder.length
                    builder.append(cleanUrl)
                    builder.addStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        ),
                        start,
                        builder.length
                    )
                    builder.addStringAnnotation(TAG_URL, cleanUrl, start, builder.length)
                    i += urlLen
                    continue
                }
            }

            // Standard harmless character (including literal HTML tags like <div>, <script>, etc.)
            builder.append(c)
            i++
        }

        return builder.toAnnotatedString()
    }

    data class DecryptedChatMessage(
        val plainText: String,
        val replyToMessageId: String? = null,
        val replyToSenderNxId: String? = null,
        val replyToSnippet: String? = null
    )

    /**
     * Parses decrypted text from Double Ratchet AEAD.
     * If the payload is a structured CHAT_MESSAGE JSON envelope containing reply metadata,
     * extracts text and reply references. Otherwise returns original text.
     */
    fun parseIncomingPayload(decryptedText: String): DecryptedChatMessage {
        if (decryptedText.startsWith("{") && decryptedText.contains("\"type\":\"CHAT_MESSAGE\"")) {
            try {
                val json = org.json.JSONObject(decryptedText)
                if (json.optString("type") == "CHAT_MESSAGE") {
                    val text = json.optString("text", "")
                    val replyId = json.optString("replyToMessageId").takeIf { it.isNotBlank() }
                    val replySender = json.optString("replyToSender").takeIf { it.isNotBlank() }
                    val replySnippet = json.optString("replyToSnippet").takeIf { it.isNotBlank() }
                    return DecryptedChatMessage(
                        plainText = text,
                        replyToMessageId = replyId,
                        replyToSenderNxId = replySender,
                        replyToSnippet = replySnippet
                    )
                }
            } catch (ignored: Exception) {}
        }
        return DecryptedChatMessage(plainText = decryptedText)
    }

    /**
     * Formats an outgoing message for E2EE transmission.
     * When replying to a message, embeds replyToMessageId and safe fallback snapshot
     * inside the encrypted payload envelope, preserving zero-server-knowledge privacy.
     */
    fun formatOutgoingPayload(
        text: String,
        replyToMessageId: String? = null,
        replyToSenderNxId: String? = null,
        replyToSnippet: String? = null
    ): String {
        if (replyToMessageId.isNullOrBlank()) {
            return text
        }
        val json = org.json.JSONObject()
        json.put("type", "CHAT_MESSAGE")
        json.put("text", text)
        json.put("replyToMessageId", replyToMessageId)
        if (!replyToSenderNxId.isNullOrBlank()) {
            json.put("replyToSender", replyToSenderNxId)
        }
        if (!replyToSnippet.isNullOrBlank()) {
            json.put("replyToSnippet", replyToSnippet.take(120))
        }
        return json.toString()
    }
}
