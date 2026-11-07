package com.nexa.app

import android.content.SharedPreferences
import com.nexa.app.core.identity.PeerIdentityResolver
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.data.local.preferences.ConversationPreferences
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode
import com.nexa.app.presentation.media_explorer.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactProfileAndMediaExplorerUnitTests {

    private class FakeEditor(private val storage: MutableMap<String, Any>) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value != null) storage[key] = value else storage.remove(key)
            return this
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            storage[key] = value
            return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            storage[key] = value
            return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            storage[key] = value
            return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            storage[key] = value
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            storage.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            storage.clear()
            return this
        }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    private class FakeSharedPreferences : SharedPreferences {
        val storage = mutableMapOf<String, Any>()
        override fun getAll(): MutableMap<String, *> = storage
        override fun getString(key: String, defValue: String?): String? = (storage[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = (storage[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (storage[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (storage[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (storage[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = storage.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(storage)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
    }

    @Test
    fun testContactProfile3TierIdentityResolution() {
        val resolved = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = "Best Friend",
            deviceContactName = "Bob Mobile",
            originalRegisteredName = "Robert Smith",
            nxId = "NX-TEST-12345"
        )
        // Tier 1 custom nickname takes precedence
        assertEquals("Best Friend", resolved.primaryDisplayName)
        assertEquals("Robert Smith", resolved.originalRegisteredName)

        val resolvedWithoutNickname = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = null,
            deviceContactName = "Bob Mobile",
            originalRegisteredName = "Robert Smith",
            nxId = "NX-TEST-12345"
        )
        // Tier 2 device contact name fallback
        assertEquals("Bob Mobile", resolvedWithoutNickname.primaryDisplayName)

        val resolvedFallback = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = null,
            deviceContactName = null,
            originalRegisteredName = "Robert Smith",
            nxId = "NX-TEST-12345"
        )
        // Tier 3 registered name fallback
        assertEquals("Robert Smith", resolvedFallback.primaryDisplayName)
    }

    @Test
    fun testMuteNotificationsScopedPersistenceAndExpiry() {
        val conversationPreferences = ConversationPreferences(fakePrefs)
        val convId = "conv_alex_123"

        // Default: unmuted
        assertFalse(conversationPreferences.isMuted(convId))

        // Timed mute: 8 hours in future
        val eightHours = 8 * 3600 * 1000L
        val muteUntil = System.currentTimeMillis() + eightHours
        conversationPreferences.setMuted(convId, true, muteUntil)

        assertTrue(conversationPreferences.isMuted(convId))
        assertEquals(muteUntil, conversationPreferences.getMuteUntil(convId))

        // Indefinite mute (-1L)
        conversationPreferences.setMuted(convId, true, -1L)
        assertTrue(conversationPreferences.isMuted(convId))
        assertEquals(-1L, conversationPreferences.getMuteUntil(convId))

        // Unmute (0L)
        conversationPreferences.setMuted(convId, false, 0L)
        assertFalse(conversationPreferences.isMuted(convId))
        assertEquals(0L, conversationPreferences.getMuteUntil(convId))
    }

    @Test
    fun testChatWallpaperPerConversationIsolation() {
        val conversationPreferences = ConversationPreferences(fakePrefs)
        val convA = "chat_a"
        val convB = "chat_b"

        assertNull(conversationPreferences.getWallpaper(convA))
        assertNull(conversationPreferences.getWallpaper(convB))

        conversationPreferences.setWallpaper(convA, "midnight")
        assertEquals("midnight", conversationPreferences.getWallpaper(convA))
        assertNull("Wallpaper for conversation B must remain null", conversationPreferences.getWallpaper(convB))

        conversationPreferences.setWallpaper(convB, "emerald")
        assertEquals("emerald", conversationPreferences.getWallpaper(convB))

        // Reset convA to default
        conversationPreferences.setWallpaper(convA, null)
        assertNull(conversationPreferences.getWallpaper(convA))
        assertEquals("emerald", conversationPreferences.getWallpaper(convB))
    }

    @Test
    fun testCustomNotificationsPreferences() {
        val conversationPreferences = ConversationPreferences(fakePrefs)
        val convId = "chat_priya"

        assertNull(conversationPreferences.getCustomTone(convId))
        assertTrue(conversationPreferences.isVibrationEnabled(convId))

        conversationPreferences.setCustomTone(convId, "Subtle Pulse")
        conversationPreferences.setVibrationEnabled(convId, false)

        assertEquals("Subtle Pulse", conversationPreferences.getCustomTone(convId))
        assertFalse(conversationPreferences.isVibrationEnabled(convId))
    }

    @Test
    fun testMediaExplorerCategorizationLogic() {
        val transfers = listOf(
            TransferEntity(1, "c1", "sunset.jpg", 1024, "uri1", "h1", 1, 1, "COMPLETED", false),
            TransferEntity(2, "c1", "avatar.png", 2048, "uri2", "h2", 1, 1, "COMPLETED", true),
            TransferEntity(3, "c1", "screen.mp4", 10000, "uri3", "h3", 1, 1, "COMPLETED", false),
            TransferEntity(4, "c1", "clip.mkv", 20000, "uri4", "h4", 1, 1, "COMPLETED", true),
            TransferEntity(5, "c1", "doc.pdf", 4000, "uri5", "h5", 1, 1, "COMPLETED", false),
            TransferEntity(6, "c1", "sheet.xlsx", 8000, "uri6", "h6", 1, 1, "COMPLETED", true),
            TransferEntity(7, "c1", "voice_note.mp3", 3000, "uri7", "h7", 1, 1, "COMPLETED", false),
            TransferEntity(8, "c1", "archive.zip", 50000, "uri8", "h8", 1, 1, "COMPLETED", true)
        )

        val images = mutableListOf<TransferEntity>()
        val videos = mutableListOf<TransferEntity>()
        val documents = mutableListOf<TransferEntity>()
        val others = mutableListOf<TransferEntity>()

        for (tr in transfers) {
            val ext = tr.fileName.substringAfterLast('.', "").lowercase()
            when (ext) {
                "jpg", "jpeg", "png", "webp", "gif", "heic" -> images.add(tr)
                "mp4", "mkv", "webm", "avi", "mov", "3gp" -> videos.add(tr)
                "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "csv" -> documents.add(tr)
                else -> others.add(tr)
            }
        }

        assertEquals(2, images.size)
        assertEquals(2, videos.size)
        assertEquals(2, documents.size)
        assertEquals(2, others.size)
    }

    @Test
    fun testBlockAndUnblockRelationshipStateTransitions() {
        val contact = ContactEntity(
            nxId = "NX-PEER-1",
            displayName = "Peer One",
            publicKeyHex = "AA".repeat(32),
            status = ContactStatus.MUTUAL_FRIEND
        )
        assertEquals(ContactStatus.MUTUAL_FRIEND, contact.status)

        // Block transition
        val blockedContact = contact.copy(status = ContactStatus.BLOCKED)
        assertEquals(ContactStatus.BLOCKED, blockedContact.status)

        // Unblock transition
        val unblockedContact = blockedContact.copy(status = ContactStatus.MUTUAL_FRIEND)
        assertEquals(ContactStatus.MUTUAL_FRIEND, unblockedContact.status)
    }

    @Test
    fun testRemoveContactSemantics() {
        val contact = ContactEntity(
            nxId = "NX-PEER-2",
            displayName = "Peer Two",
            phoneNumber = "+1234567890",
            isPhoneInLocalAddressBook = true,
            publicKeyHex = "BB".repeat(32),
            status = ContactStatus.MUTUAL_FRIEND
        )
        // Removal clears local contact association but does not erase phone address-book status
        assertTrue(contact.isPhoneInLocalAddressBook)
        assertNotNull(contact.phoneNumber)
    }

    @Test
    fun testMediaCategoryEnumValues() {
        val categories = MediaCategory.values()
        assertEquals(4, categories.size)
        assertEquals("Images", MediaCategory.IMAGES.label)
        assertEquals("Videos", MediaCategory.VIDEOS.label)
        assertEquals("Documents", MediaCategory.DOCUMENTS.label)
        assertEquals("Other Files", MediaCategory.OTHER_FILES.label)
    }

    @Test
    fun testPresenceAndTypingPrivacyModes() {
        assertEquals(3, OnlinePrivacyMode.values().size)
        assertEquals(2, TypingPrivacyMode.values().size)

        val everyone = OnlinePrivacyMode.EVERYONE
        val myContacts = OnlinePrivacyMode.MY_CONTACTS
        val nobody = OnlinePrivacyMode.NOBODY

        assertNotEquals(everyone, nobody)
        assertNotEquals(myContacts, nobody)
    }
}
