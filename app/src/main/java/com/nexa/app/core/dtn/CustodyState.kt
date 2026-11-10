package com.nexa.app.core.dtn

/**
 * Independent Custody State Machine for DTN Store-and-Forward Relays.
 *
 * Keeps intermediate relay custody state strictly separated from the
 * final recipient delivery state (MessageStatus.DELIVERED).
 */
enum class CustodyState {
    NOT_ACCEPTED,        // Relay rejected envelope or initial pre-persistence state
    CUSTODY_ACCEPTED,    // Envelope validated and durably persisted in local SQLCipher vault
    FORWARDING,          // Currently attempting onward replication to an adjacent peer
    CUSTODY_TRANSFERRED, // Downstream relay confirmed durable persistence via valid Custody ACK
    CUSTODY_RELEASED,    // Custody responsibility safely relinquished according to replication policy
    CUSTODY_EXPIRED,     // Envelope TTL expired before successful transfer
    CUSTODY_FAILED;      // Downstream transmission or verification failed

    companion object {
        fun fromString(value: String): CustodyState = try {
            valueOf(value)
        } catch (_: Exception) {
            NOT_ACCEPTED
        }
    }
}
