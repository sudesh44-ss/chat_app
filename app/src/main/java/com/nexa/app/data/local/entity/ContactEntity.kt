package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.nexa.app.core.identity.PeerIdentityResolver
import com.nexa.app.core.identity.ResolvedPeerIdentity

/**
 * State machine representing mutual relationship stages.
 */
enum class ContactStatus {
    REQUEST_SENT,     // User A sent, waiting for B (Chat input locked)
    REQUEST_RECEIVED, // User B received, can Accept/Reject
    MUTUAL_FRIEND,    // Bilateral trust established, full chat & calls unlocked
    BLOCKED,          // Silent network-level packet discard
    CONTACT_VERIFIED  // Day-0 Instant QR Verified Trust (Chat & Mesh unlocked)
}

@Entity(
    tableName = "contacts",
    indices = [Index(value = ["nxId"], unique = true)]
)
data class ContactEntity(
    @PrimaryKey val nxId: String,
    val displayName: String,
    val publicKeyHex: String,
    val introMessage: String? = null,
    val status: ContactStatus = ContactStatus.MUTUAL_FRIEND,
    val blePeerId: String? = null,
    val createdAtTimestamp: Long = System.currentTimeMillis(),
    val customNickname: String? = null,
    val deviceContactName: String? = null,
    val phoneNumber: String? = null,
    val isPhoneInLocalAddressBook: Boolean = false,
    val isPhoneSharedByPeer: Boolean = false,
    val connectionSource: String = "ADDRESS_BOOK",
    val avatarLocalPath: String? = null,
    val deviceContactPhotoUri: String? = null,
    val nexaAvatarUrl: String? = null,
    @ColumnInfo(defaultValue = "0")
    val isStatusMuted: Boolean = false
) {
    /**
     * Resolves the effective official avatar URL, preferring remote CDN/R2 URL over local cached path.
     */
    val effectiveAvatarUrl: String?
        get() = nexaAvatarUrl ?: avatarLocalPath
    /**
     * Resolves the 3-Tier Identity Hierarchy:
     * Tier 1 (customNickname) > Tier 2 (deviceContactName) > Tier 3 (displayName)
     * Provides anti-impersonation context while maintaining zero server leakage.
     */
    val resolvedIdentity: ResolvedPeerIdentity
        get() = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = customNickname,
            deviceContactName = deviceContactName,
            originalRegisteredName = displayName,
            nxId = nxId
        )

    /**
     * Prioritizes user-defined local nickname override or device contact name over peer's original profile name.
     * Enforces Zero-Knowledge: customNickname is stored strictly on-device in local SQLCipher.
     */
    val effectiveDisplayName: String
        get() = resolvedIdentity.primaryDisplayName

    /**
     * Enforces Phone Privacy:
     * Number is visible if it already exists in the device's local address book,
     * OR if peer has explicitly shared their phone number.
     */
    val shouldShowPhoneNumber: Boolean
        get() = isPhoneInLocalAddressBook || (isPhoneSharedByPeer && !phoneNumber.isNullOrBlank())
}
