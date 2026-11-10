package com.nexa.app.domain.model

enum class MessageStatus {
    // Canonical Delivery State Machine:
    CREATED,    // Clock icon: message created in local outbox
    QUEUED,     // Single check: accepted into Cloud Gateway queue
    RELAYED,    // Intermediate relay custody or hop forward (distinct from DELIVERED)
    DELIVERED,  // Double check: acknowledged by final recipient device
    READ,       // Cyan double check: recipient opened conversation
    FAILED,     // Exclamation: permanent failure or rejected
    EXPIRED,    // Message TTL expired before delivery

    // Legacy Compatibility Aliases:
    PENDING,    // Alias for CREATED
    SENT        // Alias for QUEUED
}
