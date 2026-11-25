package com.nexa.app.core.sms

/**
 * Dual-SIM policy for the encrypted SMS fallback.
 *
 * Android facts this encodes:
 *  - A slot index is NOT a permanent SIM identity; the only stable handle is the subscription id.
 *  - Enumerating subscriptions requires READ_PHONE_STATE (or carrier privileges). NEXA does not
 *    declare it, and will not, because SMS fallback must stay zero-permission.
 *  - Therefore in Mode A the system SMS app performs SIM selection. NEXA must say so instead of
 *    guessing, and must never silently claim which SIM was used.
 *
 * The policy below is pure logic so it can be unit-tested and reused unchanged if Mode B is ever
 * approved (where subscriptions are enumerable).
 */
data class SmsSubscriptionSlot(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String,
    val isDefaultSmsSubscription: Boolean
)

enum class SmsSimSelectionMode {
    /** Subscription info not readable: the user's SMS app chooses the SIM. */
    DELEGATED_TO_SYSTEM_SMS_APP,
    /** A specific subscription is registered and usable. */
    REGISTERED_SUBSCRIPTION,
    /** The registered subscription is gone (SIM removed/replaced) and the user must decide. */
    REQUIRES_USER_CHOICE,
    /** No usable SIM at all. */
    NO_SIM
}

data class SmsSimSelection(
    val mode: SmsSimSelectionMode,
    val selected: SmsSubscriptionSlot?,
    val available: List<SmsSubscriptionSlot>,
    val mustAskUserBeforeSending: Boolean,
    val userMessage: String
)

object SmsSubscriptionPolicy {

    fun resolve(
        availableSlots: List<SmsSubscriptionSlot>?,
        preferredSubscriptionId: Int?,
        lastUsedSubscriptionId: Int?
    ): SmsSimSelection {
        if (availableSlots == null) {
            return SmsSimSelection(
                mode = SmsSimSelectionMode.DELEGATED_TO_SYSTEM_SMS_APP,
                selected = null,
                available = emptyList(),
                mustAskUserBeforeSending = false,
                userMessage = "SIM selection is handled by your default SMS app."
            )
        }
        if (availableSlots.isEmpty()) {
            return SmsSimSelection(
                mode = SmsSimSelectionMode.NO_SIM,
                selected = null,
                available = emptyList(),
                mustAskUserBeforeSending = false,
                userMessage = "No SIM card is available. Insert a SIM or use Internet."
            )
        }

        val preferred = preferredSubscriptionId
            ?.let { id -> availableSlots.firstOrNull { it.subscriptionId == id } }
        val lastUsed = lastUsedSubscriptionId
            ?.let { id -> availableSlots.firstOrNull { it.subscriptionId == id } }
        val systemDefault = availableSlots.firstOrNull { it.isDefaultSmsSubscription }

        val chosen = preferred ?: lastUsed ?: systemDefault
        if (chosen != null) {
            return SmsSimSelection(
                mode = SmsSimSelectionMode.REGISTERED_SUBSCRIPTION,
                selected = chosen,
                available = availableSlots,
                mustAskUserBeforeSending = false,
                userMessage = "Sending with ${chosen.carrierName.ifBlank { "SIM " + (chosen.slotIndex + 1) }}."
            )
        }

        // The previously used subscription disappeared (SIM removed or replaced).
        return SmsSimSelection(
            mode = SmsSimSelectionMode.REQUIRES_USER_CHOICE,
            selected = null,
            available = availableSlots,
            mustAskUserBeforeSending = true,
            userMessage = "The SIM used previously is no longer available. Choose which SIM to use."
        )
    }
}
