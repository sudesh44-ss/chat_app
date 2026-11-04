package com.nexa.app.core.qr

import com.nexa.app.core.crypto.Ed25519Signer
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Encodes and decodes self-contained `nx://add` URIs with Ed25519 digital signatures.
 *
 * Wire Scheme:
 * nx://add?id={nx_id}&name={display_name}&pk={x25519_pubkey}&peer={ble_peer_id}&exp={epoch_sec}&sig={ed25519_sig}
 */
object QrPayloadCodec {

    const val SCHEME = "nx"
    const val HOST = "add"

    fun encode(payload: QrPeerPayload, privateSigningKey: ByteArray): String {
        val rawDataToSign = payload.toCanonicalString()
        val signature = Ed25519Signer.sign(rawDataToSign.toByteArray(Charsets.UTF_8), privateSigningKey)
        val signatureHex = signature.joinToString("") { "%02x".format(it) }

        val params = StringBuilder()
        params.append("id=").append(URLEncoder.encode(payload.nxId, "UTF-8"))
        params.append("&name=").append(URLEncoder.encode(payload.displayName, "UTF-8"))
        params.append("&pk=").append(URLEncoder.encode(payload.publicKeyHex, "UTF-8"))
        params.append("&peer=").append(URLEncoder.encode(payload.blePeerId, "UTF-8"))
        params.append("&sig=").append(URLEncoder.encode(signatureHex, "UTF-8"))

        payload.expiresAtEpochSec?.let {
            params.append("&exp=").append(it)
        }

        return "$SCHEME://$HOST?$params"
    }

    fun decode(rawUriString: String): QrPeerPayload? {
        return try {
            val prefix = "$SCHEME://$HOST?"
            if (!rawUriString.startsWith(prefix)) return null

            val queryString = rawUriString.removePrefix(prefix)
            val params = queryString.split("&").mapNotNull { part ->
                val pair = part.split("=", limit = 2)
                if (pair.size == 2) {
                    val key = URLDecoder.decode(pair[0], "UTF-8")
                    val value = URLDecoder.decode(pair[1], "UTF-8")
                    key to value
                } else {
                    null
                }
            }.toMap()

            val id = params["id"] ?: return null
            val name = params["name"] ?: return null
            val pk = params["pk"] ?: return null
            val peer = params["peer"] ?: return null
            val sig = params["sig"] ?: return null
            val exp = params["exp"]?.toLongOrNull()

            QrPeerPayload(
                nxId = id,
                displayName = name,
                publicKeyHex = pk,
                blePeerId = peer,
                expiresAtEpochSec = exp,
                signatureHex = sig
            )
        } catch (e: Exception) {
            null
        }
    }
}
