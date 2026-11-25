package com.nexa.app.core.sms

/** Result of preparing an encrypted SMS fallback message. */
data class SmsOutboundPlan(
    val packetId: String,
    val messageId: String,
    val peerNxId: String,
    val conversationId: String,
    val destinationNumber: String,
    val maskedDestination: String,
    val bodyText: String,
    val parts: List<String>,
    val partCount: Int,
    val segmentCount: Int,
    val keyVersion: Int,
    val requiresConfirmation: Boolean
)

sealed class SmsPrepareOutcome {
    data class Ready(val plan: SmsOutboundPlan) : SmsPrepareOutcome()
    data class Blocked(val eligibility: SmsEligibility) : SmsPrepareOutcome()
    data class Failed(val userMessage: String, val failure: SmsFailure) : SmsPrepareOutcome()
}

/** Result of importing an SMS body (or a pasted NX1 token block) into NEXA. */
sealed class SmsImportOutcome {
    data object NotNx1 : SmsImportOutcome()
    data class Rejected(val reason: String, val userMessage: String) : SmsImportOutcome()
    data class Incomplete(val messageId: String, val receivedParts: Int, val totalParts: Int) : SmsImportOutcome()
    data class Delivered(
        val packetId: String,
        val peerNxId: String,
        val plainText: String,
        val ackToken: String?,
        val duplicate: Boolean
    ) : SmsImportOutcome()
    data class AckAccepted(val packetId: String, val messageId: String, val duplicate: Boolean) : SmsImportOutcome()
    data class AckRejected(val reason: String) : SmsImportOutcome()
}

data class SmsSessionStatus(
    val exists: Boolean,
    val ready: Boolean,
    val expired: Boolean,
    val revoked: Boolean,
    val keyVersion: Int,
    val establishedAt: Long,
    val expiresAt: Long,
    val peerProvisioningPublicKeyHex: String?,
    /** True when the stored session was derived from a local key that no longer exists (key loss). */
    val localKeyMismatch: Boolean = false
)

object SmsHandoffState {
    const val QUEUED = "QUEUED"
    const val HANDED_OFF = "HANDED_OFF"
    const val SENT_UNCONFIRMED = "SENT_UNCONFIRMED"
    const val DELIVERED_APP_ACK = "DELIVERED_APP_ACK"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

/** Control-packet type used to provision the independent SMS session over the E2EE Internet channel. */
object SmsProvisioningProtocol {
    const val CONTROL_TYPE = "SMS_KEY_PROVISION"
    const val VERSION = 1
}

data class SmsComposerRequest(
    val destinationNumber: String,
    val bodyText: String,
    val segmentCount: Int
)

/** Masks a phone number for UI display: keeps only the last two digits. */
fun maskPhoneNumber(number: String?): String {
    if (number.isNullOrBlank()) return ""
    val digits = number.filter { it.isDigit() }
    if (digits.length <= 2) return "•".repeat(digits.length)
    return "•".repeat(digits.length - 2) + digits.takeLast(2)
}
