package com.nexa.app.data.remote.dto

import org.json.JSONObject

data class PrekeyBundleResponse(
    val nxId: String,
    val identityKeyHex: String,
    val signingKeyHex: String,
    val signedPrekeyHex: String,
    val signedPrekeySignatureHex: String,
    val oneTimePrekeyHex: String?,
    val oneTimePrekeyId: String?
) {
    companion object {
        fun fromJson(jsonStr: String): PrekeyBundleResponse {
            val json = JSONObject(jsonStr)
            return PrekeyBundleResponse(
                nxId = json.getString("nx_id"),
                identityKeyHex = json.getString("identity_key"),
                signingKeyHex = json.optString("signing_key", json.getString("identity_key")),
                signedPrekeyHex = json.getString("signed_prekey"),
                signedPrekeySignatureHex = json.optString("signed_prekey_sig", ""),
                oneTimePrekeyHex = json.optString("one_time_prekey").takeIf { it.isNotBlank() },
                oneTimePrekeyId = json.optString("one_time_prekey_id").takeIf { it.isNotBlank() }
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
