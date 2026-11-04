package com.nexa.app.data.remote.signaling

import org.json.JSONArray
import org.json.JSONObject

sealed class SwarmSignalingDto(val action: String) {

    abstract fun toJson(): JSONObject

    data class SwarmAdvertise(
        val fileHash: String,
        val peerId: String,
        val totalChunks: Int
    ) : SwarmSignalingDto(ACTION_SWARM_ADVERTISE) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("file_hash", fileHash)
            put("peer_id", peerId)
            put("total_chunks", totalChunks)
        }
    }

    data class SwarmQuery(
        val fileHash: String,
        val requesterId: String
    ) : SwarmSignalingDto(ACTION_SWARM_QUERY) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("file_hash", fileHash)
            put("requester_id", requesterId)
        }
    }

    data class SwarmResponse(
        val fileHash: String,
        val seeders: List<String>
    ) : SwarmSignalingDto(ACTION_SWARM_RESPONSE) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("file_hash", fileHash)
            put("seeders", JSONArray(seeders))
        }
    }

    data class MetadataBroadcast(
        val fileHash: String,
        val conversationId: String,
        val senderNxId: String,
        val fileName: String,
        val fileSizeBytes: Long,
        val totalChunks: Int,
        val blurHash: String? = null
    ) : SwarmSignalingDto(ACTION_METADATA_BROADCAST) {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("file_hash", fileHash)
            put("conversation_id", conversationId)
            put("sender_nx_id", senderNxId)
            put("file_name", fileName)
            put("file_size_bytes", fileSizeBytes)
            put("total_chunks", totalChunks)
            blurHash?.let { put("blur_hash", it) }
        }
    }

    companion object {
        const val ACTION_SWARM_ADVERTISE = "SWARM_ADVERTISE"
        const val ACTION_SWARM_QUERY = "SWARM_QUERY"
        const val ACTION_SWARM_RESPONSE = "SWARM_RESPONSE"
        const val ACTION_METADATA_BROADCAST = "METADATA_BROADCAST"

        fun fromJson(jsonStr: String): SwarmSignalingDto? {
            return try {
                val json = JSONObject(jsonStr)
                when (json.optString("action")) {
                    ACTION_SWARM_ADVERTISE -> SwarmAdvertise(
                        fileHash = json.getString("file_hash"),
                        peerId = json.getString("peer_id"),
                        totalChunks = json.getInt("total_chunks")
                    )
                    ACTION_SWARM_QUERY -> SwarmQuery(
                        fileHash = json.getString("file_hash"),
                        requesterId = json.getString("requester_id")
                    )
                    ACTION_SWARM_RESPONSE -> {
                        val arr = json.getJSONArray("seeders")
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            list.add(arr.getString(i))
                        }
                        SwarmResponse(
                            fileHash = json.getString("file_hash"),
                            seeders = list
                        )
                    }
                    ACTION_METADATA_BROADCAST -> MetadataBroadcast(
                        fileHash = json.getString("file_hash"),
                        conversationId = json.getString("conversation_id"),
                        senderNxId = json.getString("sender_nx_id"),
                        fileName = json.getString("file_name"),
                        fileSizeBytes = json.getLong("file_size_bytes"),
                        totalChunks = json.getInt("total_chunks"),
                        blurHash = json.optString("blur_hash", null)
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
