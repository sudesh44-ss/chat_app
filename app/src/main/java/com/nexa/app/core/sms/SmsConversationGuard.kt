package com.nexa.app.core.sms

/**
 * Single authority for "is this conversation a group?" used by the SMS transport.
 *
 * SMS must be completely unavailable in group conversations, and that must be enforced in
 * business logic (repository + eligibility), not only by hiding UI. Keeping the rule in one
 * pure function makes it testable and prevents the UI and repository from disagreeing.
 */
object SmsConversationGuard {

    fun isGroupConversation(conversationId: String?, peerNxId: String?): Boolean {
        val conversation = conversationId.orEmpty()
        val peer = peerNxId.orEmpty()

        if (conversation.contains("group", ignoreCase = true)) return true
        if (peer.contains("group", ignoreCase = true)) return true
        if (peer.startsWith("GRP", ignoreCase = true)) return true

        // Direct conversation ids are exactly "<nxIdA>_<nxIdB>" (two segments, no underscores
        // inside NX-IDs). Anything with additional segments is multi-party and ineligible.
        return conversation.isNotBlank() && conversation.split('_').size > 2
    }
}
