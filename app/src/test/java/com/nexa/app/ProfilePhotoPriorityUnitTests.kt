package com.nexa.app

import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.presentation.common.avatar.ResolvedAvatar
import com.nexa.app.presentation.common.avatar.canOpenPhotoViewer
import com.nexa.app.presentation.common.avatar.getDeterministicColorForName
import com.nexa.app.presentation.common.avatar.resolveAvatar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePhotoPriorityUnitTests {

    @Test
    fun case1_nexaAvatarPresent_priority1WinsOverContactPhoto() {
        // Case 1: Both VEYRA profile photo and device contact photo exist
        val nexaPhotoA = "https://pub-r2.veyra.app/avatars/photo_a.webp"
        val contactPhotoB = "content://com.android.contacts/display_photo/2"
        val name = "Rahul Sharma"

        val resolved = resolveAvatar(
            nexaAvatarPathOrUrl = nexaPhotoA,
            deviceContactPhotoUri = contactPhotoB,
            effectiveName = name
        )

        assertTrue("Priority 1 must win when NEXA/VEYRA photo is present", resolved is ResolvedAvatar.NetworkOrLocalUri)
        val uriModel = resolved as ResolvedAvatar.NetworkOrLocalUri
        assertEquals("Photo A (VEYRA avatar) must be selected", nexaPhotoA, uriModel.model)
    }

    @Test
    fun case2_nexaAvatarAbsent_priority2ContactPhotoWins() {
        // Case 2: No VEYRA profile photo, but phone contact photo exists
        val contactPhotoB = "content://com.android.contacts/display_photo/2"
        val name = "Rahul Sharma"

        // Null NEXA avatar
        val resolvedNull = resolveAvatar(
            nexaAvatarPathOrUrl = null,
            deviceContactPhotoUri = contactPhotoB,
            effectiveName = name
        )
        assertTrue("Priority 2 must be selected when NEXA photo is null", resolvedNull is ResolvedAvatar.NetworkOrLocalUri)
        assertEquals(contactPhotoB, (resolvedNull as ResolvedAvatar.NetworkOrLocalUri).model)

        // Blank/whitespace NEXA avatar
        val resolvedBlank = resolveAvatar(
            nexaAvatarPathOrUrl = "   ",
            deviceContactPhotoUri = contactPhotoB,
            effectiveName = name
        )
        assertTrue("Priority 2 must be selected when NEXA photo is blank", resolvedBlank is ResolvedAvatar.NetworkOrLocalUri)
        assertEquals(contactPhotoB, (resolvedBlank as ResolvedAvatar.NetworkOrLocalUri).model)
    }

    @Test
    fun case3_neitherPhotoExists_priority3InitialAvatarGenerated() {
        // Case 3A: Rahul Sharma -> 'R'
        val resolvedRahul = resolveAvatar(
            nexaAvatarPathOrUrl = null,
            deviceContactPhotoUri = null,
            effectiveName = "Rahul Sharma"
        )
        assertTrue("Priority 3 must be selected when neither photo exists", resolvedRahul is ResolvedAvatar.InitialAvatar)
        val initialRahul = resolvedRahul as ResolvedAvatar.InitialAvatar
        assertEquals('R', initialRahul.char)
        assertEquals(getDeterministicColorForName("Rahul Sharma"), initialRahul.backgroundColorHex)

        // Case 3B: Amit Kumar -> 'A'
        val resolvedAmit = resolveAvatar(
            nexaAvatarPathOrUrl = "",
            deviceContactPhotoUri = "",
            effectiveName = "Amit Kumar"
        )
        assertTrue(resolvedAmit is ResolvedAvatar.InitialAvatar)
        val initialAmit = resolvedAmit as ResolvedAvatar.InitialAvatar
        assertEquals('A', initialAmit.char)
        assertEquals(getDeterministicColorForName("Amit Kumar"), initialAmit.backgroundColorHex)

        // Case 3C: Leading whitespace / lowercase handling
        val resolvedWhitespace = resolveAvatar(
            nexaAvatarPathOrUrl = null,
            deviceContactPhotoUri = null,
            effectiveName = "  priya verma"
        )
        assertTrue(resolvedWhitespace is ResolvedAvatar.InitialAvatar)
        assertEquals('P', (resolvedWhitespace as ResolvedAvatar.InitialAvatar).char)

        // Case 3D: Empty string fallback -> '?'
        val resolvedEmpty = resolveAvatar(
            nexaAvatarPathOrUrl = null,
            deviceContactPhotoUri = null,
            effectiveName = ""
        )
        assertTrue(resolvedEmpty is ResolvedAvatar.InitialAvatar)
        assertEquals('?', (resolvedEmpty as ResolvedAvatar.InitialAvatar).char)
    }

    @Test
    fun deterministicColorPalette_isStableAndConsistent() {
        val color1 = getDeterministicColorForName("Rahul Sharma")
        val color2 = getDeterministicColorForName("Rahul Sharma")
        assertEquals("Same name must yield identical color hex deterministically", color1, color2)

        val colorAmit = getDeterministicColorForName("Amit Kumar")
        // Colors should be valid ARGB color hex values from the palette
        val validPalette = setOf(
            0xFF1E88E5, // Blue
            0xFF43A047, // Green
            0xFF8E24AA, // Purple
            0xFFE53935, // Red
            0xFFFB8C00, // Orange
            0xFF00ACC1, // Cyan
            0xFF3949AB  // Indigo
        )
        assertTrue("Color must belong to pre-defined Material palette", validPalette.contains(color1))
        assertTrue("Color must belong to pre-defined Material palette", validPalette.contains(colorAmit))
    }

    @Test
    fun profileUpdateControlPacket_jsonSerializationAndParsing() {
        val nxId = "NX-7F82K9A4"
        val newAvatarUrl = "https://pub-r2.veyra.app/avatars/hash_123.webp"
        val timestamp = 1731568200000L

        // 1. Serialize PROFILE_UPDATE
        val packetJson = JSONObject().apply {
            put("type", "PROFILE_UPDATE")
            put("nxId", nxId)
            put("newAvatarUrl", newAvatarUrl)
            put("timestamp", timestamp)
        }.toString()

        assertTrue(packetJson.contains("\"type\":\"PROFILE_UPDATE\""))

        // 2. Parse and verify
        val parsed = JSONObject(packetJson)
        assertEquals("PROFILE_UPDATE", parsed.getString("type"))
        assertEquals(nxId, parsed.getString("nxId"))
        assertEquals(newAvatarUrl, parsed.getString("newAvatarUrl"))
        assertEquals(timestamp, parsed.getLong("timestamp"))

        // 3. Photo Removal / Clear case: newAvatarUrl is null
        val removeJson = JSONObject().apply {
            put("type", "PROFILE_UPDATE")
            put("nxId", nxId)
            put("newAvatarUrl", JSONObject.NULL)
            put("timestamp", timestamp)
        }.toString()

        val parsedRemove = JSONObject(removeJson)
        assertTrue(parsedRemove.isNull("newAvatarUrl"))
    }

    @Test
    fun contactEntity_effectiveAvatarUrlResolvesCorrectly() {
        // Remote URL takes priority
        val contactWithBoth = ContactEntity(
            nxId = "NX-BOB",
            displayName = "Bob Dylan",
            publicKeyHex = "abcd",
            avatarLocalPath = "/data/cache/local_bob.webp",
            nexaAvatarUrl = "https://cdn.veyra.app/bob_avatar.webp"
        )
        assertEquals("https://cdn.veyra.app/bob_avatar.webp", contactWithBoth.effectiveAvatarUrl)

        // Local cached path fallback
        val contactWithLocalOnly = ContactEntity(
            nxId = "NX-ALICE",
            displayName = "Alice Wonderland",
            publicKeyHex = "1234",
            avatarLocalPath = "/data/cache/local_alice.webp",
            nexaAvatarUrl = null
        )
        assertEquals("/data/cache/local_alice.webp", contactWithLocalOnly.effectiveAvatarUrl)

        // Neither present
        val contactWithoutAvatar = ContactEntity(
            nxId = "NX-CHARLIE",
            displayName = "Charlie Chaplin",
            publicKeyHex = "5678",
            avatarLocalPath = null,
            nexaAvatarUrl = null
        )
        assertNull(contactWithoutAvatar.effectiveAvatarUrl)
    }

    @Test
    fun photoViewer_strictRule_opensOnlyOnRealPhotos_neverOnInitialAvatars() {
        // Priority 1: Real VEYRA photo
        val resolvedP1 = resolveAvatar(
            nexaAvatarPathOrUrl = "https://cdn.veyra.app/avatars/user.webp",
            deviceContactPhotoUri = null,
            effectiveName = "Rahul"
        )
        assertTrue("Priority 1 real photo MUST be eligible for full-screen viewer", canOpenPhotoViewer(resolvedP1, true))
        assertEquals("https://cdn.veyra.app/avatars/user.webp", (resolvedP1 as ResolvedAvatar.NetworkOrLocalUri).model)

        // Priority 2: Real Contact photo
        val resolvedP2 = resolveAvatar(
            nexaAvatarPathOrUrl = null,
            deviceContactPhotoUri = "content://com.android.contacts/display_photo/5",
            effectiveName = "Amit"
        )
        assertTrue("Priority 2 real contact photo MUST be eligible for full-screen viewer", canOpenPhotoViewer(resolvedP2, true))
        assertEquals("content://com.android.contacts/display_photo/5", (resolvedP2 as ResolvedAvatar.NetworkOrLocalUri).model)

        // Priority 3: Alphabet Initial Avatar -> STRICT RULE: MUST NEVER OPEN VIEWER
        val resolvedP3 = resolveAvatar(
            nexaAvatarPathOrUrl = null,
            deviceContactPhotoUri = null,
            effectiveName = "Priya"
        )
        assertTrue(resolvedP3 is ResolvedAvatar.InitialAvatar)
        org.junit.Assert.assertFalse(
            "Alphabet avatar MUST NEVER open full-screen photo viewer",
            canOpenPhotoViewer(resolvedP3, true)
        )
    }

    @Test
    fun photoViewer_loadingFailed_doesNotOpenViewer() {
        val resolvedP1 = resolveAvatar(
            nexaAvatarPathOrUrl = "https://cdn.veyra.app/broken.webp",
            deviceContactPhotoUri = null,
            effectiveName = "Vikram"
        )
        // If image failed to load (e.g. 404 or network error falling back to initial), viewer must not open
        org.junit.Assert.assertFalse(
            "Failed/unloaded photo must not open viewer",
            canOpenPhotoViewer(resolvedP1, isLoadedSuccessfully = false)
        )
    }
}
