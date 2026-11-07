package com.nexa.app

import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.remote.dto.BlockedUserItemDto
import com.nexa.app.data.remote.dto.ConnectionItemDto
import com.nexa.app.data.remote.dto.ConnectionRequestItemDto
import com.nexa.app.data.remote.dto.DiscoveredUserDto
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.presentation.contacts.ContactUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase D Connection & Contact Management Unit Tests.
 *
 * Verifies:
 * 1. NX-ID validation format matching ^NX-[A-Z0-9]{8}$.
 * 2. Canonical user pair ordering for duplicate prevention.
 * 3. Privacy boundary verification (no phone, email, or private key fields).
 * 4. DTO mapping and state transitions (REQUEST_RECEIVED, MUTUAL_FRIEND, BLOCKED).
 * 5. Expiration detection on connection requests.
 * 6. ContactUiState transitions for Phase D actions.
 * 7. End-to-end repository workflow via FakeContactRepository.
 */
class PhaseDConnectionTests {

    private val nxIdRegex = Regex("^NX-[A-Z0-9]{8}$")

    @Test
    fun testNxIdValidationFormat() {
        // Valid NX-IDs
        assertTrue(nxIdRegex.matches("NX-29AB731Q"))
        assertTrue(nxIdRegex.matches("NX-A7K92B4M"))
        assertTrue(nxIdRegex.matches("NX-00000000"))
        assertTrue(nxIdRegex.matches("NX-ZZZZZZZZ"))

        // Invalid: lowercase letters
        assertFalse(nxIdRegex.matches("nx-29ab731q"))
        assertFalse(nxIdRegex.matches("NX-29ab731q"))

        // Invalid: incorrect lengths
        assertFalse(nxIdRegex.matches("NX-29AB731"))    // 7 chars
        assertFalse(nxIdRegex.matches("NX-29AB731QA"))  // 9 chars
        assertFalse(nxIdRegex.matches("NX-"))

        // Invalid: missing prefix or bad characters
        assertFalse(nxIdRegex.matches("29AB731Q"))
        assertFalse(nxIdRegex.matches("XX-29AB731Q"))
        assertFalse(nxIdRegex.matches("NX-29AB731!"))
        assertFalse(nxIdRegex.matches("NX-29AB 731"))
    }

    @Test
    fun testCanonicalUserPairOrdering() {
        fun canonicalPair(u1: String, u2: String): Pair<String, String> {
            return if (u1 < u2) Pair(u1, u2) else Pair(u2, u1)
        }

        val alice = "NX-11111111"
        val bob = "NX-22222222"

        val pair1 = canonicalPair(alice, bob)
        val pair2 = canonicalPair(bob, alice)

        assertEquals("Canonical ordering must be symmetric", pair1, pair2)
        assertEquals(alice, pair1.first)
        assertEquals(bob, pair1.second)
    }

    @Test
    fun testPrivacyBoundaryNoSensitiveFields() {
        // Inspect DiscoveredUserDto fields to ensure zero domestic phone, email, or private keys
        val discovered = DiscoveredUserDto(
            nxId = "NX-A7K92B4M",
            displayName = "Alice",
            avatarUrl = "https://cdn.example.com/avatar.jpg",
            bio = "NEXA user",
            publicKeyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            isConnected = false,
            isPending = false,
            isBlocked = false
        )

        val properties = discovered::class.java.declaredFields.map { it.name }
        assertFalse("Must not expose phone number", properties.any { it.contains("phone", ignoreCase = true) })
        assertFalse("Must not expose email", properties.any { it.contains("email", ignoreCase = true) })
        assertFalse("Must not expose private key", properties.any { it.contains("private", ignoreCase = true) })
        assertFalse("Must not expose secret", properties.any { it.contains("secret", ignoreCase = true) })
    }

    @Test
    fun testConnectionRequestItemDtoExpiration() {
        val now = 10000L
        val activeRequest = ConnectionRequestItemDto(
            id = "req_active",
            senderNxId = "NX-11111111",
            recipientNxId = "NX-22222222",
            introMessage = "Hello",
            status = "PENDING",
            createdAt = 8000L,
            expiresAt = 12000L
        )

        val expiredRequest = ConnectionRequestItemDto(
            id = "req_expired",
            senderNxId = "NX-33333333",
            recipientNxId = "NX-22222222",
            introMessage = "Old request",
            status = "PENDING",
            createdAt = 1000L,
            expiresAt = 5000L
        )

        fun isExpired(req: ConnectionRequestItemDto, currentTime: Long): Boolean {
            return req.expiresAt <= currentTime
        }

        assertFalse(isExpired(activeRequest, now))
        assertTrue(isExpired(expiredRequest, now))
    }

    @Test
    fun testContactUiStatePhaseDUpdates() {
        val initial = ContactUiState()
        assertEquals(0, initial.incomingRequests.size)
        assertEquals(0, initial.outgoingRequests.size)
        assertNull(initial.discoveredUser)

        val discovered = DiscoveredUserDto(
            nxId = "NX-A7K92B4M",
            displayName = "Bob",
            publicKeyHex = "abcd"
        )

        val stateAfterDiscovery = initial.copy(
            isSearchingUser = false,
            discoveredUser = discovered
        )
        assertNotNull(stateAfterDiscovery.discoveredUser)
        assertEquals("Bob", stateAfterDiscovery.discoveredUser?.displayName)

        val incoming = listOf(
            ConnectionRequestItemDto(
                id = "req_1",
                senderNxId = "NX-A7K92B4M",
                recipientNxId = "NX-MYSELF12",
                status = "PENDING",
                createdAt = 1000L,
                expiresAt = 9000L,
                profile = discovered
            )
        )

        val stateWithRequests = stateAfterDiscovery.copy(incomingRequests = incoming)
        assertEquals(1, stateWithRequests.incomingRequests.size)
        assertEquals("req_1", stateWithRequests.incomingRequests.first().id)
    }

    @Test
    fun testFakeContactRepositoryPhaseDOperations() = runBlocking {
        val fakeRepo = FakeContactRepository()

        // 1. Discover user
        val userRes = fakeRepo.discoverUser("NX-A7K92B4M")
        assertTrue(userRes.isSuccess)
        assertEquals("Bob", userRes.getOrNull()?.displayName)

        // 2. Send request
        val sendRes = fakeRepo.sendFriendRequest("NX-A7K92B4M", "Hi Bob!")
        assertTrue(sendRes.isSuccess)

        // 3. Get outgoing requests
        val outgoingRes = fakeRepo.getOutgoingConnectionRequests()
        assertTrue(outgoingRes.isSuccess)
        assertEquals(1, outgoingRes.getOrNull()?.size)

        // 4. Accept incoming request
        val acceptRes = fakeRepo.acceptConnectionRequestById(
            "req_in_1",
            ContactEntity(
                nxId = "NX-CHARLIE1",
                displayName = "Charlie",
                publicKeyHex = "feedbeef",
                status = ContactStatus.REQUEST_RECEIVED
            )
        )
        assertTrue(acceptRes.isSuccess)

        // 5. Remove connection
        val removeRes = fakeRepo.removeConnection("NX-CHARLIE1")
        assertTrue(removeRes.isSuccess)

        // 6. Block and unblock user
        val blockRes = fakeRepo.blockUser("NX-BADUSER1")
        assertTrue(blockRes.isSuccess)
        val blockedList = fakeRepo.getBlockedUsers()
        assertTrue(blockedList.isSuccess)
        assertTrue(blockedList.getOrNull()?.any { it.nxId == "NX-BADUSER1" } == true)

        val unblockRes = fakeRepo.unblockUser("NX-BADUSER1")
        assertTrue(unblockRes.isSuccess)
        val unblockedList = fakeRepo.getBlockedUsers()
        assertFalse(unblockedList.getOrNull()?.any { it.nxId == "NX-BADUSER1" } == true)
    }

    /**
     * In-memory Fake ContactRepository implementing Phase D methods for testing.
     */
    private class FakeContactRepository : ContactRepository {
        val contacts = mutableMapOf<String, ContactEntity>()
        val outgoing = mutableListOf<ConnectionRequestItemDto>()
        val incoming = mutableListOf<ConnectionRequestItemDto>()
        val blocked = mutableMapOf<String, BlockedUserItemDto>()

        init {
            incoming.add(
                ConnectionRequestItemDto(
                    id = "req_in_1",
                    senderNxId = "NX-CHARLIE1",
                    recipientNxId = "NX-MYSELF12",
                    status = "PENDING",
                    createdAt = 1000L,
                    expiresAt = 9000L,
                    profile = DiscoveredUserDto(
                        nxId = "NX-CHARLIE1",
                        displayName = "Charlie",
                        publicKeyHex = "feedbeef"
                    )
                )
            )
        }

        override suspend fun sendFriendRequest(targetNxId: String, introMessage: String?): Result<Unit> {
            outgoing.add(
                ConnectionRequestItemDto(
                    id = "req_out_${outgoing.size + 1}",
                    senderNxId = "NX-MYSELF12",
                    recipientNxId = targetNxId,
                    introMessage = introMessage,
                    status = "PENDING",
                    createdAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + 86400000L
                )
            )
            return Result.success(Unit)
        }

        override suspend fun acceptFriendRequest(contact: ContactEntity): Result<Unit> {
            contacts[contact.nxId] = contact.copy(status = ContactStatus.MUTUAL_FRIEND)
            return Result.success(Unit)
        }

        override suspend fun rejectOrBlockContact(nxId: String, block: Boolean): Result<Unit> {
            if (block) {
                contacts[nxId] = ContactEntity(nxId = nxId, displayName = "Blocked", publicKeyHex = "", status = ContactStatus.BLOCKED)
            } else {
                contacts.remove(nxId)
            }
            return Result.success(Unit)
        }

        override suspend fun syncPendingRequests(): Int = incoming.size
        override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> = MutableStateFlow(contacts.values.filter { it.status == status })
        override fun getPendingCount(): Flow<Int> = MutableStateFlow(incoming.size)
        override fun getAllContacts(): Flow<List<ContactEntity>> = MutableStateFlow(contacts.values.toList())
        override suspend fun getContact(nxId: String): ContactEntity? = contacts[nxId]
        override suspend fun setContactNickname(nxId: String, customNickname: String?): Result<Unit> = Result.success(Unit)
        override fun searchContacts(query: String): Flow<List<ContactEntity>> = MutableStateFlow(emptyList())
        override suspend fun loadDeviceContacts(): Result<List<com.nexa.app.domain.model.DeviceContact>> = Result.success(emptyList())
        override suspend fun lookupSinglePhone(phoneNumber: String): Result<com.nexa.app.domain.model.DeviceContact?> = Result.success(null)

        override suspend fun discoverUser(nxId: String): Result<DiscoveredUserDto> {
            return if (nxId == "NX-A7K92B4M") {
                Result.success(
                    DiscoveredUserDto(
                        nxId = nxId,
                        displayName = "Bob",
                        publicKeyHex = "abcd"
                    )
                )
            } else {
                Result.failure(Exception("User not found"))
            }
        }

        override suspend fun getIncomingConnectionRequests(): Result<List<ConnectionRequestItemDto>> {
            return Result.success(incoming)
        }

        override suspend fun getOutgoingConnectionRequests(): Result<List<ConnectionRequestItemDto>> {
            return Result.success(outgoing)
        }

        override suspend fun acceptConnectionRequestById(requestId: String, contact: ContactEntity): Result<Unit> {
            incoming.removeAll { it.id == requestId }
            contacts[contact.nxId] = contact.copy(status = ContactStatus.MUTUAL_FRIEND)
            return Result.success(Unit)
        }

        override suspend fun rejectConnectionRequestById(requestId: String, nxId: String): Result<Unit> {
            incoming.removeAll { it.id == requestId }
            contacts.remove(nxId)
            return Result.success(Unit)
        }

        override suspend fun cancelConnectionRequestById(requestId: String): Result<Unit> {
            outgoing.removeAll { it.id == requestId }
            return Result.success(Unit)
        }

        override suspend fun removeConnection(targetNxId: String): Result<Unit> {
            contacts.remove(targetNxId)
            return Result.success(Unit)
        }

        override suspend fun blockUser(targetNxId: String): Result<Unit> {
            contacts[targetNxId] = ContactEntity(
                nxId = targetNxId,
                displayName = "Blocked",
                publicKeyHex = "0000",
                status = ContactStatus.BLOCKED
            )
            blocked[targetNxId] = BlockedUserItemDto(
                nxId = targetNxId,
                displayName = "Blocked",
                publicKeyHex = "0000"
            )
            return Result.success(Unit)
        }

        override suspend fun unblockUser(targetNxId: String): Result<Unit> {
            contacts.remove(targetNxId)
            blocked.remove(targetNxId)
            return Result.success(Unit)
        }

        override suspend fun getBlockedUsers(): Result<List<BlockedUserItemDto>> {
            return Result.success(blocked.values.toList())
        }
    }
}
