package com.nexa.app.core.sms

/**
 * Transport states surfaced to the user for the encrypted SMS fallback.
 * Strictly descriptive: no state implies carrier or Play approval.
 */
enum class SmsFallbackState {
    INTERNET_ONLINE,
    MESH_AVAILABLE,
    SMS_READY,
    SMS_SESSION_NOT_READY,
    SMS_PHONE_UNAVAILABLE,
    SMS_SIM_UNAVAILABLE,
    SMS_GROUP_DISABLED,
    SMS_SENDING,
    SMS_SENT_UNCONFIRMED,
    SMS_DELIVERED_APP_ACK,
    SMS_FAILED
}

enum class SmsBlockReason {
    NONE,
    GROUP_CHAT,
    CONTACT_NOT_ACCEPTED,
    CONTACT_BLOCKED,
    PHONE_NUMBER_UNAVAILABLE,
    SESSION_MISSING,
    SESSION_EXPIRED,
    SESSION_REVOKED,
    SIM_UNAVAILABLE,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_CONTENT
}

data class SmsEligibility(
    val eligible: Boolean,
    val state: SmsFallbackState,
    val reason: SmsBlockReason,
    val userMessage: String
)

data class SmsEligibilityInput(
    val isGroupConversation: Boolean,
    val contactStatusName: String?,
    val hasPeerPhoneNumber: Boolean,
    val phoneNumberVisibleToUser: Boolean,
    val sessionReady: Boolean,
    val sessionExpired: Boolean,
    val sessionRevoked: Boolean,
    val simAvailable: Boolean,
    val payloadWithinLimits: Boolean = true,
    val internetAvailable: Boolean = false,
    val meshAvailable: Boolean = false
)

/**
 * Single source of truth for "may this conversation use encrypted SMS right now?".
 * Called from the repository (never only from the UI), so group chats cannot be forced to SMS.
 */
object SmsEligibilityEvaluator {

    const val SESSION_NOT_READY_MESSAGE =
        "Encrypted SMS is not ready for this contact. Connect to the internet once to establish secure offline messaging."

    fun evaluate(input: SmsEligibilityInput): SmsEligibility {
        if (input.isGroupConversation) {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_GROUP_DISABLED,
                SmsBlockReason.GROUP_CHAT,
                "Encrypted SMS is available for 1-to-1 conversations only. Group messages are never sent by SMS."
            )
        }
        val status = input.contactStatusName?.uppercase()
        if (status == "BLOCKED") {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_PHONE_UNAVAILABLE,
                SmsBlockReason.CONTACT_BLOCKED,
                "This contact is blocked."
            )
        }
        if (status != "MUTUAL_FRIEND" && status != "CONTACT_VERIFIED") {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_SESSION_NOT_READY,
                SmsBlockReason.CONTACT_NOT_ACCEPTED,
                "Encrypted SMS requires an accepted NEXA contact."
            )
        }
        if (!input.hasPeerPhoneNumber || !input.phoneNumberVisibleToUser) {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_PHONE_UNAVAILABLE,
                if (input.hasPeerPhoneNumber) SmsBlockReason.PHONE_NUMBER_UNAVAILABLE else SmsBlockReason.PHONE_NUMBER_UNAVAILABLE,
                "This contact has no phone number available for SMS fallback."
            )
        }
        if (!input.payloadWithinLimits) {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_SESSION_NOT_READY,
                SmsBlockReason.PAYLOAD_TOO_LARGE,
                "Message is too long for encrypted SMS. Shorten it or use Internet."
            )
        }
        if (input.sessionRevoked) {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_SESSION_NOT_READY,
                SmsBlockReason.SESSION_REVOKED,
                SESSION_NOT_READY_MESSAGE
            )
        }
        if (input.sessionExpired) {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_SESSION_NOT_READY,
                SmsBlockReason.SESSION_EXPIRED,
                SESSION_NOT_READY_MESSAGE
            )
        }
        if (!input.sessionReady) {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_SESSION_NOT_READY,
                SmsBlockReason.SESSION_MISSING,
                SESSION_NOT_READY_MESSAGE
            )
        }
        if (!input.simAvailable) {
            return SmsEligibility(
                false,
                SmsFallbackState.SMS_SIM_UNAVAILABLE,
                SmsBlockReason.SIM_UNAVAILABLE,
                "No SIM card is available. Insert a SIM or use Internet."
            )
        }
        val state = when {
            input.internetAvailable -> SmsFallbackState.INTERNET_ONLINE
            input.meshAvailable -> SmsFallbackState.MESH_AVAILABLE
            else -> SmsFallbackState.SMS_READY
        }
        val message = when (state) {
            SmsFallbackState.INTERNET_ONLINE ->
                "Internet is available. Encrypted SMS is ready as a fallback."
            SmsFallbackState.MESH_AVAILABLE ->
                "Mesh is available. Encrypted SMS is ready as a fallback."
            else ->
                "Offline — encrypted SMS fallback is ready. Your SMS app will send it; NEXA cannot confirm the carrier result."
        }
        return SmsEligibility(true, state, SmsBlockReason.NONE, message)
    }
}
