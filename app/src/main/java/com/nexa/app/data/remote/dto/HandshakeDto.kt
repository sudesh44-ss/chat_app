package com.nexa.app.data.remote.dto

import org.json.JSONObject

data class PrekeyBundleResponse(
    val nxId: String,
    val deviceId: String = "",
    val identityKeyHex: String,
    val signingKeyHex: String,
    val signedPrekeyHex: String,
    val signedPrekeyId: Int = 1,
    val signedPrekeySignatureHex: String,
    val oneTimePrekeyHex: String? = null,
    val oneTimePrekeyId: String? = null
) {
    companion object {
        fun fromJson(jsonStr: String): PrekeyBundleResponse {
            val json = JSONObject(jsonStr)
            val nxId = json.optString("nx_id", json.optString("account_id", ""))
            val identityKey = json.optString("identity_key", "")
            return PrekeyBundleResponse(
                nxId = nxId,
                deviceId = json.optString("device_id", ""),
                identityKeyHex = identityKey,
                signingKeyHex = json.optString("signing_key", identityKey),
                signedPrekeyHex = json.optString("signed_prekey", ""),
                signedPrekeyId = json.optInt("signed_prekey_id", 1),
                signedPrekeySignatureHex = json.optString("signed_prekey_sig", ""),
                oneTimePrekeyHex = json.optString("one_time_prekey").takeIf { it.isNotBlank() },
                oneTimePrekeyId = json.optString("one_time_prekey_id").takeIf { it.isNotBlank() }
            )
        }
    }
}

data class ClaimResponseDto(
    val claimId: String,
    val initiatorNxId: String,
    val initiatorDeviceId: String,
    val targetNxId: String,
    val targetDeviceId: String,
    val identityKeyHex: String,
    val signingKeyHex: String,
    val signedPrekeyHex: String,
    val signedPrekeyId: Int,
    val signedPrekeySignatureHex: String,
    val oneTimePrekeyHex: String?,
    val oneTimePrekeyId: String?,
    val expiresAt: Long
) {
    companion object {
        fun fromJson(jsonStr: String): ClaimResponseDto {
            val json = JSONObject(jsonStr)
            return ClaimResponseDto(
                claimId = json.optString("claim_id", ""),
                initiatorNxId = json.optString("initiator_nx_id", ""),
                initiatorDeviceId = json.optString("initiator_device_id", ""),
                targetNxId = json.optString("target_nx_id", ""),
                targetDeviceId = json.optString("target_device_id", ""),
                identityKeyHex = json.optString("identity_key", ""),
                signingKeyHex = json.optString("signing_key", json.optString("identity_key", "")),
                signedPrekeyHex = json.optString("signed_prekey", ""),
                signedPrekeyId = json.optInt("signed_prekey_id", 1),
                signedPrekeySignatureHex = json.optString("signed_prekey_sig", ""),
                oneTimePrekeyHex = json.optString("one_time_prekey").takeIf { it.isNotBlank() && it != "null" },
                oneTimePrekeyId = if (json.has("one_time_prekey_id") && !json.isNull("one_time_prekey_id")) json.get("one_time_prekey_id").toString() else null,
                expiresAt = json.optLong("expires_at", 0L)
            )
        }
    }
}

data class DispatchRequestDto(
    val recipientNxId: String,
    val encryptedEnvelope: String,
    val powTimestamp: Long,
    val powNonce: Long
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("recipient_nx_id", recipientNxId)
            put("encrypted_envelope", encryptedEnvelope)
            put("pow_timestamp", powTimestamp)
            put("pow_nonce", powNonce)
        }.toString()
    }
}
