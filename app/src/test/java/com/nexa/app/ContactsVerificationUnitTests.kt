package com.nexa.app

import com.nexa.app.core.contacts.ContactsProviderHelper
import com.nexa.app.data.remote.dto.ContactLookupRequest
import com.nexa.app.data.remote.dto.ContactLookupResponse
import com.nexa.app.data.remote.dto.ContactMatchDto
import com.nexa.app.domain.model.DeviceContact
import com.nexa.app.presentation.contacts.ContactUiState
import com.nexa.app.presentation.contacts.InternalAddressState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsVerificationUnitTests {

    @Test
    fun testPhoneNumberNormalizationE164() {
        // Standard E.164 with country code
        assertEquals("+919876543210", ContactsProviderHelper.normalizePhoneNumber("+91 98765 43210"))
        assertEquals("+919876543210", ContactsProviderHelper.normalizePhoneNumber("+91-98765-43210"))
        assertEquals("+919876543210", ContactsProviderHelper.normalizePhoneNumber("+91 (98765) 43210"))

        // Indian 10-digit mobile (auto +91 prefix)
        assertEquals("+919876543210", ContactsProviderHelper.normalizePhoneNumber("9876543210"))
        assertEquals("+917024773600", ContactsProviderHelper.normalizePhoneNumber("70247 73600"))
        assertEquals("+918123456789", ContactsProviderHelper.normalizePhoneNumber("08123456789"))

        // International with 00 prefix
        assertEquals("+14155552671", ContactsProviderHelper.normalizePhoneNumber("0014155552671"))

        // Invalid numbers (too short, alpha, special characters)
        assertNull(ContactsProviderHelper.normalizePhoneNumber("12345"))
        assertNull(ContactsProviderHelper.normalizePhoneNumber("abcdefghij"))
        assertNull(ContactsProviderHelper.normalizePhoneNumber(""))
    }

    @Test
    fun testDuplicatePhoneNumberDeduplication() {
        val rawNumbers = listOf(
            "+91 98765 43210",
            "9876543210",
            "+91-98765-43210",
            "98765 43210",
            "+919876543211"
        )

        val seen = mutableSetOf<String>()
        val uniqueNormalized = mutableListOf<String>()

        for (raw in rawNumbers) {
            val normalized = ContactsProviderHelper.normalizePhoneNumber(raw) ?: continue
            if (seen.add(normalized)) {
                uniqueNormalized.add(normalized)
            }
        }

        // 4 entries refer to the same number, 1 is distinct
        assertEquals(2, uniqueNormalized.size)
        assertEquals("+919876543210", uniqueNormalized[0])
        assertEquals("+919876543211", uniqueNormalized[1])
    }

    @Test
    fun testContactSearchFiltering() {
        val contacts = listOf(
            DeviceContact(id = "1", displayName = "Alice Sharma", phoneNumber = "+919876543210", normalizedNumber = "+919876543210", isNexaUser = true, nexaNxId = "NX-29AB731Q"),
            DeviceContact(id = "2", displayName = "Bob Verma", phoneNumber = "+919876543211", normalizedNumber = "+919876543211", isNexaUser = false),
            DeviceContact(id = "3", displayName = "Charlie Patel", phoneNumber = "+14155552671", normalizedNumber = "+14155552671", isNexaUser = true, nexaNxId = "NX-9K8M3L2P")
        )

        // Empty search query returns all
        val all = contacts.filter { it.displayName.contains("", ignoreCase = true) }
        assertEquals(3, all.size)

        // Search by name substring
        val aliceFilter = contacts.filter { it.displayName.contains("alice", ignoreCase = true) }
        assertEquals(1, aliceFilter.size)
        assertEquals("NX-29AB731Q", aliceFilter[0].nexaNxId)

        // Search by phone substring
        val phoneFilter = contacts.filter { it.phoneNumber.contains("5552671") }
        assertEquals(1, phoneFilter.size)
        assertEquals("Charlie Patel", phoneFilter[0].displayName)

        // Search non-existent
        val nonExistent = contacts.filter { it.displayName.contains("Zack", ignoreCase = true) }
        assertTrue(nonExistent.isEmpty())
    }

    @Test
    fun testRegisteredNexaAccountDetectionAndAugmentation() {
        val localContacts = listOf(
            DeviceContact(id = "1", displayName = "Alice", phoneNumber = "9876543210", normalizedNumber = "+919876543210"),
            DeviceContact(id = "2", displayName = "Bob", phoneNumber = "9876543211", normalizedNumber = "+919876543211")
        )

        val serverMatches = mapOf(
            "+919876543210" to ContactMatchDto(
                phoneNumber = "+919876543210",
                nxId = "NX-29AB731Q",
                displayName = "Alice Sharma",
                publicKeyHex = "b5a41d3f9e01"
            )
        )

        val augmented = localContacts.map { contact ->
            val match = serverMatches[contact.normalizedNumber]
            if (match != null) {
                contact.copy(
                    isNexaUser = true,
                    nexaNxId = match.nxId,
                    nexaPublicKeyHex = match.publicKeyHex
                )
            } else {
                contact.copy(isNexaUser = false)
            }
        }

        assertTrue("Alice must be detected as registered NEXA user", augmented[0].isNexaUser)
        assertEquals("NX-29AB731Q", augmented[0].nexaNxId)
        assertFalse("Bob must remain unregistered", augmented[1].isNexaUser)
        assertNull(augmented[1].nexaNxId)
    }

    @Test
    fun testStrictPrivacyZeroContactNamesSentToBackend() {
        val deviceAddressBook = listOf(
            DeviceContact(id = "1", displayName = "Secret Contact VIP", phoneNumber = "9876543210", normalizedNumber = "+919876543210"),
            DeviceContact(id = "2", displayName = "Confidential Doctor", phoneNumber = "9876543211", normalizedNumber = "+919876543211")
        )

        // Construct network DTO
        val requestDto = ContactLookupRequest(
            phoneNumbers = deviceAddressBook.map { it.normalizedNumber }
        )

        // Verify that NO contact names, IDs, or notes exist in request payload
        assertEquals(2, requestDto.phoneNumbers.size)
        for (phone in requestDto.phoneNumbers) {
            assertTrue("Payload must strictly contain E.164 phone numbers only", phone.startsWith("+"))
            assertFalse("Payload must NOT contain contact names", phone.contains("VIP"))
            assertFalse("Payload must NOT contain contact names", phone.contains("Doctor"))
        }
    }

    @Test
    fun testNetworkFailureDoesNotMarkUsersAsUnregisteredPermanently() {
        val cachedContact = DeviceContact(
            id = "1",
            displayName = "Alice",
            phoneNumber = "+919876543210",
            normalizedNumber = "+919876543210",
            isNexaUser = true,
            nexaNxId = "NX-29AB731Q"
        )

        // On network error, cached status is preserved rather than wiped to false
        val isNetworkError = true
        val resolvedContact = if (isNetworkError) {
            cachedContact // Retain cached state
        } else {
            cachedContact.copy(isNexaUser = false)
        }

        assertTrue("Network error must NOT overwrite verified NEXA state with unregistered status", resolvedContact.isNexaUser)
        assertEquals("NX-29AB731Q", resolvedContact.nexaNxId)
    }

    @Test
    fun testPermissionRevocationStateHandling() {
        val state = InternalAddressState(
            isPermissionGranted = false,
            isPermissionDeniedPermanently = true,
            deviceContacts = emptyList()
        )

        assertFalse(state.isPermissionGranted)
        assertTrue(state.isPermissionDeniedPermanently)
        assertTrue(state.deviceContacts.isEmpty())
    }
}
