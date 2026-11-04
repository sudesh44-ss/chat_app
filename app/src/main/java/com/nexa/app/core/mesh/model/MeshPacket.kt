package com.nexa.app.core.mesh.model

/**
 * Universal MANET/DTN wire frame carrying Destination Sequence Numbers (DSN),
 * strict 7-hop limit bounds, blind crypto tokens, and payload bytes (Scenario 10 & 18).
 */
data class MeshPacket(
    val packetId: String,
    val sourceId: String,
    val destinationId: String,
    val dsn: Long,
    val hopLimit: Int = 7,
    val hopCount: Int = 0,
    val payload: ByteArray,
    val blindToken: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun canForward(): Boolean = hopLimit > 0 && hopCount < 7

    fun nextHopPacket(): MeshPacket {
        return copy(
            hopLimit = (hopLimit - 1).coerceAtLeast(0),
            hopCount = hopCount + 1
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshPacket
        return packetId == other.packetId
    }

    override fun hashCode(): Int = packetId.hashCode()
}
