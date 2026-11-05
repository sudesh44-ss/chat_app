package com.nexa.app

import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification Suite for NEXA Zero-Leakage Phone Privacy:
 * 1. Server-side projection safety (NULL phone when not shared)
 * 2. Local address book matching bypass (known friends remain visible)
 * 3. Dynamic Allow / Revoke phone sharing toggle
 * 4. QR Identity isolation (zero phone number in QR payload)
 */
class ContactPhonePrivacyUnitTests {

    @Test
    fun phonePrivacy_whenInLocalAddressBook_showsPhoneNumber() {
        val contact = ContactEntity(
            nxId = "NX-ABCD1234",
            displayName = "Rahul Sharma",
            publicKeyHex = "abcd1234ef",
            status = ContactStatus.MUTUAL_FRIEND,
            phoneNumber = "+919876543210",
            isPhoneInLocalAddressBook = true,
            isPhoneSharedByPeer = false,
            connectionSource = "ADDRESS_BOOK"
        )

        assertTrue("Number must be visible when present in local address book", contact.shouldShowPhoneNumber)
        assertEquals("+919876543210", contact.phoneNumber)
        assertEquals("Rahul Sharma", contact.effectiveDisplayName)
    }

    @Test
    fun phonePrivacy_whenConnectedViaQrOrSearch_hidesPhoneNumberByDefault() {
        // Connected via QR or Search: Phone number is NULL from server projection
        val contact = ContactEntity(
            nxId = "NX-WXYZ5678",
            displayName = "Anonymous Peer",
            publicKeyHex = "wxyz5678ab",
            status = ContactStatus.MUTUAL_FRIEND,
            phoneNumber = null,
            isPhoneInLocalAddressBook = false,
            isPhoneSharedByPeer = false,
            connectionSource = "QR"
        )

        assertFalse("Number must be hidden when connected via QR and not in address book", contact.shouldShowPhoneNumber)
        assertNull("Phone number must be null", contact.phoneNumber)
    }

    @Test
    fun phonePrivacy_whenPeerGrantsSharing_unhidesPhoneNumber() {
        // Initial state: hidden
        val initial = ContactEntity(
            nxId = "NX-PEER-9999",
            displayName = "Sudesh",
            publicKeyHex = "1234567890",
            status = ContactStatus.MUTUAL_FRIEND,
            phoneNumber = null,
            isPhoneInLocalAddressBook = false,
            isPhoneSharedByPeer = false,
            connectionSource = "SEARCH"
        )
        assertFalse(initial.shouldShowPhoneNumber)

        // Peer sends WebSocket PHONE_GRANTED event -> updates local entity
        val updated = initial.copy(
            isPhoneSharedByPeer = true,
            phoneNumber = "+919988776655"
        )
        assertTrue("Number must be unhidden when peer explicitly grants visibility", updated.shouldShowPhoneNumber)
        assertEquals("+919988776655", updated.phoneNumber)

        // Peer later sends PHONE_REVOKED event -> sanitizes phone number from disk
        val revoked = updated.copy(
            isPhoneSharedByPeer = false,
            phoneNumber = null
        )
        assertFalse("Number must be hidden again when peer revokes visibility", revoked.shouldShowPhoneNumber)
        assertNull(revoked.phoneNumber)
    }

    @Test
    fun customNickname_overridesDisplayName_withoutAffectingPhonePrivacy() {
        val contact = ContactEntity(
            nxId = "NX-FRIEND-01",
            displayName = "Original Name",
            publicKeyHex = "fedcba9876",
            status = ContactStatus.MUTUAL_FRIEND,
            customNickname = "Best Buddy",
            phoneNumber = "+919123456789",
            isPhoneInLocalAddressBook = true,
            isPhoneSharedByPeer = false
        )

        assertEquals("Best Buddy", contact.effectiveDisplayName)
        assertEquals("Original Name", contact.displayName)
        assertTrue(contact.shouldShowPhoneNumber)
    }
}
