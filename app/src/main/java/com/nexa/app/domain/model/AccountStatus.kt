package com.nexa.app.domain.model

/**
 * Authoritative lifecycle states for NEXA Accounts.
 */
enum class AccountStatus {
    ACTIVE,
    DEACTIVATED,
    DELETION_REQUESTED,
    IDENTITY_VERIFIED,
    DELETION_CONFIRMED,
    DELETION_PROCESSING,
    DELETED,
    DELETION_FAILED,
    DELETION_CANCELLED
}
