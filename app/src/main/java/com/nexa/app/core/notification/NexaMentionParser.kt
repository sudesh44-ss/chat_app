package com.nexa.app.core.notification

/**
 * Structured parser and matcher for @mentions in NEXA group conversations.
 * Ensures zero false-positive alerts from email addresses or substring matches.
 */
object NexaMentionParser {

    // Regex matching @mention token preceded by start-of-line, whitespace, or open parenthesis/punctuation
    // Avoids matching email addresses like user@domain.com
    private val MENTION_REGEX = Regex("""(?<=\s|^|[(\[{<,.:;!?'"]|^)@([a-zA-Z0-9_\-]+)""")

    /**
     * Extracts all @mention handles from [messageText].
     * Example: "Hey @alice and @NX-987 check this out" -> ["alice", "NX-987"]
     */
    fun extractMentions(messageText: String): List<String> {
        if (messageText.isBlank()) return emptyList()
        return MENTION_REGEX.findAll(messageText).map { it.groupValues[1] }.toList()
    }

    /**
     * Determines whether [messageText] contains a direct mention directed at the current user.
     *
     * @param messageText Inbound plain text message.
     * @param myNxId Current user's unique cryptographic ID (e.g., "NX-9A8B7C").
     * @param myDisplayName Current user's display name or username (e.g., "Alice Smith").
     * @return True if message contains @all, @everyone, or mentions user's ID or handle.
     */
    fun isDirectMention(
        messageText: String,
        myNxId: String? = null,
        myDisplayName: String? = null
    ): Boolean {
        if (messageText.isBlank()) return false

        val mentions = extractMentions(messageText)
        if (mentions.isEmpty()) return false

        val normalizedNxId = myNxId?.trim()?.uppercase()
        val shortNxId = normalizedNxId?.removePrefix("NX-")
        val normalizedDisplayName = myDisplayName?.trim()?.lowercase()
        val condensedDisplayName = normalizedDisplayName?.replace(" ", "")
        val displayNameWords = normalizedDisplayName?.split(Regex("""\s+"""))?.filter { it.length >= 2 } ?: emptyList()

        for (mention in mentions) {
            val m = mention.lowercase()
            val mUpper = mention.uppercase()

            // 1. Group Broadcast Mentions
            if (m == "all" || m == "everyone" || m == "channel") {
                return true
            }

            // 2. Exact NX-ID match
            if (normalizedNxId != null && (mUpper == normalizedNxId || mUpper == shortNxId)) {
                return true
            }

            // 3. Display Name match (case-insensitive: full, condensed, or individual name parts)
            if (normalizedDisplayName != null && (
                m == normalizedDisplayName ||
                m == condensedDisplayName ||
                displayNameWords.contains(m)
            )) {
                return true
            }
        }

        return false
    }
}
