package com.nexa.app.core.sms

/**
 * Classification of carrier/telephony send results.
 *
 * Only reachable when NEXA itself sends (default SMS handler mode), which is BLOCKED in the
 * current policy position. It is implemented and unit-tested so that Mode B can be enabled
 * without inventing a mapping later, and so that failure copy never over-claims a cause.
 */
enum class SmsFailureKind(val retryable: Boolean) {
    RADIO_UNAVAILABLE(true),
    AIRPLANE_MODE(true),
    NO_SERVICE(true),
    SIM_ABSENT(false),
    INVALID_DESTINATION(false),
    NETWORK_REJECTED(true),
    RATE_LIMITED(true),
    MODEM_ERROR(true),
    SYSTEM_ERROR(true),
    MODE_A_UNCONFIRMED(true),
    UNKNOWN_CARRIER(true)
}

data class SmsFailure(
    val kind: SmsFailureKind,
    val userMessage: String,
    val canRetrySms: Boolean,
    val canQueueForInternet: Boolean = true
)

object SmsFailureClassifier {

    // android.telephony.SmsManager result codes (stable public constants).
    const val RESULT_ERROR_GENERIC_FAILURE = 1
    const val RESULT_ERROR_RADIO_OFF = 2
    const val RESULT_ERROR_NULL_PDU = 3
    const val RESULT_ERROR_NO_SERVICE = 4
    const val RESULT_ERROR_LIMIT_EXCEEDED = 5
    const val RESULT_ERROR_FDN_CHECK_FAILURE = 6
    const val RESULT_ERROR_SHORT_CODE_NOT_ALLOWED = 7
    const val RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED = 8
    const val RESULT_RATE_LIMITED = 9
    const val RESULT_ERROR_INVALID_ARGUMENT = 10
    const val RESULT_ERROR_INVALID_SMSC_ADDRESS = 11
    const val RESULT_ERROR_MODEM_ERROR = 12
    const val RESULT_ERROR_NETWORK_ERROR = 13
    const val RESULT_ERROR_INTERNAL_ERROR = 14
    const val RESULT_ERROR_INVALID_UDH = 15

    private const val NEUTRAL_MESSAGE =
        "SMS could not be sent. Check your network, SIM, SMS plan, or carrier restrictions."

    fun classify(resultCode: Int): SmsFailure = when (resultCode) {
        RESULT_ERROR_RADIO_OFF -> SmsFailure(
            SmsFailureKind.RADIO_UNAVAILABLE,
            "SMS could not be sent because the radio is off. Turn off airplane mode and try again.",
            canRetrySms = true
        )
        RESULT_ERROR_NO_SERVICE -> SmsFailure(
            SmsFailureKind.NO_SERVICE,
            "SMS could not be sent because there is no cellular service right now.",
            canRetrySms = true
        )
        RESULT_ERROR_NULL_PDU, RESULT_ERROR_INVALID_UDH -> SmsFailure(
            SmsFailureKind.SYSTEM_ERROR,
            "SMS could not be sent because the message could not be encoded on this device.",
            canRetrySms = false
        )
        RESULT_ERROR_FDN_CHECK_FAILURE -> SmsFailure(
            SmsFailureKind.NETWORK_REJECTED,
            "SMS was rejected by a fixed-dialing restriction on this SIM.",
            canRetrySms = false
        )
        RESULT_ERROR_LIMIT_EXCEEDED, RESULT_RATE_LIMITED -> SmsFailure(
            SmsFailureKind.RATE_LIMITED,
            "SMS sending is temporarily rate limited by the device or carrier. Try again later.",
            canRetrySms = true
        )
        RESULT_ERROR_INVALID_ARGUMENT, RESULT_ERROR_INVALID_SMSC_ADDRESS -> SmsFailure(
            SmsFailureKind.INVALID_DESTINATION,
            "SMS could not be sent because the destination number or SMS centre is not accepted.",
            canRetrySms = false
        )
        RESULT_ERROR_MODEM_ERROR, RESULT_ERROR_INTERNAL_ERROR -> SmsFailure(
            SmsFailureKind.MODEM_ERROR,
            NEUTRAL_MESSAGE,
            canRetrySms = true
        )
        RESULT_ERROR_NETWORK_ERROR, RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
            SmsFailure(SmsFailureKind.NETWORK_REJECTED, NEUTRAL_MESSAGE, canRetrySms = true)
        RESULT_ERROR_GENERIC_FAILURE -> SmsFailure(
            SmsFailureKind.UNKNOWN_CARRIER,
            NEUTRAL_MESSAGE,
            canRetrySms = true
        )
        else -> SmsFailure(SmsFailureKind.UNKNOWN_CARRIER, NEUTRAL_MESSAGE, canRetrySms = true)
    }

    /**
     * Mode A has no send callback at all: the user's SMS app owns the send. This is the only
     * honest state NEXA may report, and it is never a delivery confirmation.
     */
    fun modeAUnconfirmed(): SmsFailure = SmsFailure(
        kind = SmsFailureKind.MODE_A_UNCONFIRMED,
        userMessage = "SMS sent — delivery confirmation unavailable.",
        canRetrySms = false,
        canQueueForInternet = true
    )
}
