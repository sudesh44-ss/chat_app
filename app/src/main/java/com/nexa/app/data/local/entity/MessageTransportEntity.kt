package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks multi-transport observation history independently from message content.
 * e.g. Message ID -> Received through {INTERNET_WS, OFFLINE_MESH, NEARBY_CONNECTIONS}.
 */
@Entity(
    tableName = "message_transport_observations",
    foreignKeys = [
        ForeignKey(
            entity = MessageDedupEntity::class,
            parentColumns = ["packetId"],
            childColumns = ["packetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["packetId", "transportMedium"], unique = true),
        Index(value = ["packetId"]),
        Index(value = ["observedAt"])
    ]
)
data class MessageTransportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packetId: String,
    val transportMedium: String,
    val observedAt: Long = System.currentTimeMillis(),
    val sourcePeerId: String? = null
)
