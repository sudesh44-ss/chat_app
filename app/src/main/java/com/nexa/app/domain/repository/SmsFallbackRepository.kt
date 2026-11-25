package com.nexa.app.domain.repository

import com.nexa.app.core.sms.SmsEligibility
import com.nexa.app.core.sms.SmsImportOutcome
import com.nexa.app.core.sms.SmsPrepareOutcome
import com.nexa.app.core.sms.SmsSessionStatus

/**
 * Module 3 — encrypted SMS fallback contract.
 * Implemented entirely on-device; performs no backend calls.
 */
interface SmsFallbackRepository {

    suspend fun eligibilityFor(
        peerNxId: String,
        conversationId: String,
        destinationPhone: String? = null
    ): SmsEligibility

    /**
     * Persists the readable message locally first, encrypts with the independent SMS session,
     * encodes NX1 parts and returns a composer plan. Never sends anything itself.
     */
    suspend fun prepareOutbound(
        peerNxId: String,
        text: String,
        destinationPhone: String? = null,
        /** The real chat id. Used for group detection and message mapping; derived only if null. */
        requestedConversationId: String? = null
    ): SmsPrepareOutcome

    suspend fun markHandedOffToSmsApp(packetId: String)
    suspend fun markSentUnconfirmed(packetId: String)
    suspend fun markFailed(packetId: String, failureKind: String, userMessage: String)
    suspend fun cancelQueued(packetId: String)

    /**
     * Imports an SMS body (pasted or shared) addressed from [senderAddress].
     * Only cryptographically verified NX1 packets from known contacts are stored.
     */
    suspend fun importBody(senderAddress: String, body: String): List<SmsImportOutcome>

    suspend fun sessionStatus(peerNxId: String): SmsSessionStatus
    suspend fun registerPeerProvisioningKey(peerNxId: String, peerPublicKeyHex: String)
    suspend fun localProvisioningPublicKey(): String
    suspend fun revokeSession(peerNxId: String)
}
