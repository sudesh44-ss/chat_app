package com.nexa.app.core.identity

/**
 * 3-Tier Identity Resolution Model for NEXA.
 * Prevents impersonation and phishing attacks while guaranteeing zero-server leakage
 * and maximum personal convenience.
 *
 * Tier 1: User's locally assigned alias (customNickname) stored strictly on-device in SQLCipher.
 * Tier 2: Address book contact name (deviceContactName) read locally via Android ContactsProvider.
 * Tier 3: Cryptographically signed public profile name (originalRegisteredName) signed with peer's Ed25519 key.
 * Anchor: Immutable public handle / network hash (nxId).
 */
data class ResolvedPeerIdentity(
    val primaryDisplayName: String,
    val secondarySubLabel: String?,
    val originalRegisteredName: String,
    val nxId: String
) {
    /**
     * True if the peer is being addressed by a local personal alias (Tier 1 or Tier 2)
     * rather than their raw registered identity.
     */
    val hasCustomAlias: Boolean
        get() = primaryDisplayName != originalRegisteredName

    /**
     * Formats original registered profile name with anti-phishing '~' prefix
     * whenever an alias is currently active.
     */
    val formattedOriginalName: String
        get() = if (hasCustomAlias) "~ $originalRegisteredName" else originalRegisteredName

    /**
     * Contextual subtitle string designed for TopBars and preview lists:
     * - Tier 1 + Tier 2: "Device Name • ~ Original Name"
     * - Tier 1 or Tier 2 alone: "~ Original Name"
     * - Tier 3 alone: null
     */
    val topBarSubtitle: String?
        get() = when {
            secondarySubLabel != null && originalRegisteredName.isNotBlank() ->
                "$secondarySubLabel • ~ $originalRegisteredName"
            hasCustomAlias && originalRegisteredName.isNotBlank() ->
                "~ $originalRegisteredName"
            else -> null
        }
}

object PeerIdentityResolver {

    /**
     * Resolves peer display identity according to the 3-Tier Hierarchy:
     *
     * 1. Both Nickname + Device Contact present:
     *    Primary = customNickname, Sub-label = deviceContactName, Original = ~ originalRegisteredName
     * 2. Only Nickname set:
     *    Primary = customNickname, Sub-label = null, Original = ~ originalRegisteredName
     * 3. Only Device Contact present:
     *    Primary = deviceContactName, Sub-label = null, Original = ~ originalRegisteredName
     * 4. Neither present:
     *    Primary = originalRegisteredName (fallback to nxId), Sub-label = null, Original = originalRegisteredName
     */
    fun resolveIdentityDisplay(
        customNickname: String?,
        deviceContactName: String?,
        originalRegisteredName: String,
        nxId: String
    ): ResolvedPeerIdentity {
        val cleanNick = customNickname?.trim()?.takeIf { it.isNotBlank() }
        val cleanContact = deviceContactName?.trim()?.takeIf { it.isNotBlank() }
        val cleanOriginal = originalRegisteredName.trim().ifBlank { nxId }

        return when {
            cleanNick != null && cleanContact != null -> {
                ResolvedPeerIdentity(
                    primaryDisplayName = cleanNick,
                    secondarySubLabel = cleanContact,
                    originalRegisteredName = cleanOriginal,
                    nxId = nxId
                )
            }
            cleanNick != null -> {
                ResolvedPeerIdentity(
                    primaryDisplayName = cleanNick,
                    secondarySubLabel = null,
                    originalRegisteredName = cleanOriginal,
                    nxId = nxId
                )
            }
            cleanContact != null -> {
                ResolvedPeerIdentity(
                    primaryDisplayName = cleanContact,
                    secondarySubLabel = null,
                    originalRegisteredName = cleanOriginal,
                    nxId = nxId
                )
            }
            else -> {
                ResolvedPeerIdentity(
                    primaryDisplayName = cleanOriginal,
                    secondarySubLabel = null,
                    originalRegisteredName = cleanOriginal,
                    nxId = nxId
                )
            }
        }
    }
}
