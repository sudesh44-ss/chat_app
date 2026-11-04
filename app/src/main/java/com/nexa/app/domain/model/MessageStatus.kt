package com.nexa.app.domain.model

enum class MessageStatus {
    PENDING,    // Clock icon: in local outbox
    SENT,       // Single check: acknowledged by server relay
    DELIVERED,  // Double check: acknowledged by recipient device
    READ,       // Double colored check: recipient opened conversation
    FAILED      // Exclamation: delivery expired or connection error
}
