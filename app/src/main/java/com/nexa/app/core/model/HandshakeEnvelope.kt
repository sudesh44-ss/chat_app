package com.nexa.app.core.model

import org.json.JSONObject

/**
 * Wire-format envelope carrying an encrypted X3DH handshake request or accept confirmation.
 */
data class HandshakeEnvelope(
    val senderNxId: String,
    val ephemeralPublicKeyHex: String,
    val identityPublicKeyHex: String,
    val oneTimePrekeyId: String?,
    val saltHex: String,
    val ivHex: String,
    val ciphertextHex: String,
    val signatureHex: String,
    val powTimestamp: Long,
    val powNonce: Long,
    val isAccept: Boolean = false
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("sender_nx_id", senderNxId)
            put("ephemeral_pubkey", ephemeralPublicKeyHex)
            put("identity_pubkey", identityPublicKeyHex)
            put("one_time_prekey_id", oneTimePrekeyId ?: "")
            put("salt", saltHex)
            put("iv", ivHex)
            put("ciphertext", ciphertextHex)
            put("signature", signatureHex)
            put("pow_timestamp", powTimestamp)
            put("pow_nonce", powNonce)
            put("is_accept", isAccept)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): HandshakeEnvelope? {
            return try {
                val json = JSONObject(jsonStr)
                HandshakeEnvelope(
                    senderNxId = json.getString("sender_nx_id"),
                    ephemeralPublicKeyHex = json.getString("ephemeral_pubkey"),
                    identityPublicKeyHex = json.getString("identity_pubkey"),
                    oneTimePrekeyId = json.optString("one_time_prekey_id").takeIf { it.isNotBlank() },
                    saltHex = json.getString("salt"),
                    ivHex = json.getString("iv"),
                    ciphertextHex = json.getString("ciphertext"),
                    signatureHex = json.getString("signature"),
                    powTimestamp = json.getLong("pow_timestamp"),
                    powNonce = json.getLong("pow_nonce"),
                    isAccept = json.optBoolean("is_accept", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Decrypted plaintext payload inside the encrypted handshake envelope.
 */
data class HandshakePayload(
    val senderNxId: String,
    val displayName: String,
    val introMessage: String?,
    val timestamp: Long,
    val ephemeralPublicKeyHex: String,
    val isAccept: Boolean = false
) {
    fun toBytes(): ByteArray {
        return JSONObject().apply {
            put("sender_nx_id", senderNxId)
            put("display_name", displayName)
            put("intro_msg", introMessage ?: "")
            put("timestamp", timestamp)
            put("ephemeral_pubkey", ephemeralPublicKeyHex)
            put("is_accept", isAccept)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): HandshakePayload? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                HandshakePayload(
                    senderNxId = json.getString("sender_nx_id"),
                    displayName = json.getString("display_name"),
                    introMessage = json.optString("intro_msg").takeIf { it.isNotBlank() },
                    timestamp = json.getLong("timestamp"),
                    ephemeralPublicKeyHex = json.getString("ephemeral_pubkey"),
                    isAccept = json.optBoolean("is_accept", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
