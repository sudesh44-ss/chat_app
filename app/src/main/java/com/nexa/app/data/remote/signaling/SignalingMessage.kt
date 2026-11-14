package com.nexa.app.data.remote.signaling

import org.json.JSONObject

sealed class SignalingMessage(val action: String) {

    abstract fun toJson(): JSONObject

    data class OfferTransfer(
        val transferId: Long,
        val fileName: String,
        val fileSizeBytes: Long,
        val fileHash: String,
        val senderNxId: String,
        val recipientNxId: String,
        val senderScanStatus: String? = null
    ) : SignalingMessage(ACTION_OFFER) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("transfer_id", transferId)
            put("file_name", fileName)
            put("file_size_bytes", fileSizeBytes)
            put("file_hash", fileHash)
            put("sender_nx_id", senderNxId)
            put("recipient_nx_id", recipientNxId)
            senderScanStatus?.let { put("sender_scan_status", it) }
        }
    }

    data class AcceptTransfer(
        val transferId: Long,
        val senderNxId: String,
        val recipientNxId: String
    ) : SignalingMessage(ACTION_ACCEPT) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("transfer_id", transferId)
            put("sender_nx_id", senderNxId)
            put("recipient_nx_id", recipientNxId)
        }
    }

    data class DeclineTransfer(
        val transferId: Long,
        val senderNxId: String,
        val recipientNxId: String
    ) : SignalingMessage(ACTION_DECLINE) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("transfer_id", transferId)
            put("sender_nx_id", senderNxId)
            put("recipient_nx_id", recipientNxId)
        }
    }

    data class SignalingSdp(
        val transferId: Long,
        val sdpType: String, // "offer" or "answer"
        val sdpDescription: String,
        val senderNxId: String,
        val recipientNxId: String
    ) : SignalingMessage(ACTION_SDP) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("transfer_id", transferId)
            put("sdp_type", sdpType)
            put("sdp_description", sdpDescription)
            put("sender_nx_id", senderNxId)
            put("recipient_nx_id", recipientNxId)
        }
    }

    data class SignalingIce(
        val transferId: Long,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val sdpCandidate: String,
        val senderNxId: String,
        val recipientNxId: String
    ) : SignalingMessage(ACTION_ICE) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("transfer_id", transferId)
            put("sdp_mid", sdpMid)
            put("sdp_mline_index", sdpMLineIndex)
            put("sdp_candidate", sdpCandidate)
            put("sender_nx_id", senderNxId)
            put("recipient_nx_id", recipientNxId)
        }
    }

    data class ResumeFromChunk(
        val transferId: Long,
        val startChunkIndex: Int,
        val senderNxId: String,
        val recipientNxId: String
    ) : SignalingMessage(ACTION_RESUME) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("transfer_id", transferId)
            put("start_chunk_index", startChunkIndex)
            put("sender_nx_id", senderNxId)
            put("recipient_nx_id", recipientNxId)
        }
    }

    companion object {
        const val ACTION_OFFER = "OFFER_TRANSFER"
        const val ACTION_ACCEPT = "ACCEPT_TRANSFER"
        const val ACTION_DECLINE = "DECLINE_TRANSFER"
        const val ACTION_SDP = "SIGNALING_SDP"
        const val ACTION_ICE = "SIGNALING_ICE"
        const val ACTION_RESUME = "RESUME_FROM_CHUNK"

        fun fromJson(jsonStr: String): SignalingMessage? {
            return try {
                val json = JSONObject(jsonStr)
                when (json.optString("action")) {
                    ACTION_OFFER -> OfferTransfer(
                        transferId = json.getLong("transfer_id"),
                        fileName = json.getString("file_name"),
                        fileSizeBytes = json.getLong("file_size_bytes"),
                        fileHash = json.optString("file_hash", ""),
                        senderNxId = json.getString("sender_nx_id"),
                        recipientNxId = json.getString("recipient_nx_id"),
                        senderScanStatus = json.optString("sender_scan_status").takeIf { it.isNotBlank() }
                    )
                    ACTION_ACCEPT -> AcceptTransfer(
                        transferId = json.getLong("transfer_id"),
                        senderNxId = json.getString("sender_nx_id"),
                        recipientNxId = json.getString("recipient_nx_id")
                    )
                    ACTION_DECLINE -> DeclineTransfer(
                        transferId = json.getLong("transfer_id"),
                        senderNxId = json.getString("sender_nx_id"),
                        recipientNxId = json.getString("recipient_nx_id")
                    )
                    ACTION_SDP -> SignalingSdp(
                        transferId = json.getLong("transfer_id"),
                        sdpType = json.getString("sdp_type"),
                        sdpDescription = json.getString("sdp_description"),
                        senderNxId = json.getString("sender_nx_id"),
                        recipientNxId = json.getString("recipient_nx_id")
                    )
                    ACTION_ICE -> SignalingIce(
                        transferId = json.getLong("transfer_id"),
                        sdpMid = json.optString("sdp_mid", ""),
                        sdpMLineIndex = json.optInt("sdp_mline_index", 0),
                        sdpCandidate = json.getString("sdp_candidate"),
                        senderNxId = json.getString("sender_nx_id"),
                        recipientNxId = json.getString("recipient_nx_id")
                    )
                    ACTION_RESUME -> ResumeFromChunk(
                        transferId = json.getLong("transfer_id"),
                        startChunkIndex = json.getInt("start_chunk_index"),
                        senderNxId = json.getString("sender_nx_id"),
                        recipientNxId = json.getString("recipient_nx_id")
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
