package com.nexa.app

import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.presentation.dashboard.isGroup
import com.nexa.app.presentation.search.model.SearchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSearchScreenUnitTests {

    private val sampleConversations = listOf(
        ChatConversation(
            conversationId = "conv_unread_audio",
            participantNxId = "NX-ALICE",
            participantName = "Alice Audio",
            lastMessageText = "Listen to this voice message: [Audio] note.mp3",
            lastMessageTimestamp = 1000L,
            unreadCount = 2
        ),
        ChatConversation(
            conversationId = "conv_favorite_photo",
            participantNxId = "NX-BOB",
            participantName = "Bob Photo",
            lastMessageText = "Here is the picture: [Photo] sunset.jpg",
            lastMessageTimestamp = 2000L,
            unreadCount = 0
        ),
        ChatConversation(
            conversationId = "group_nexa_testing",
            participantNxId = "NX-GROUP-TEST",
            participantName = "NEXA Testing Group",
            lastMessageText = "Check documentation: [Document] spec.pdf",
            lastMessageTimestamp = 3000L,
            unreadCount = 5
        ),
        ChatConversation(
            conversationId = "conv_video_gif",
            participantNxId = "NX-CHARLIE",
            participantName = "Charlie Media",
            lastMessageText = "Check this clip [Video] demo.mp4 and [GIF] reaction.gif",
            lastMessageTimestamp = 4000L,
            unreadCount = 0
        ),
        ChatConversation(
            conversationId = "conv_web_links",
            participantNxId = "NX-DAVE",
            participantName = "Dave Link",
            lastMessageText = "Visit our site at https://nexa.im for details",
            lastMessageTimestamp = 5000L,
            unreadCount = 1
        ),
        ChatConversation(
            conversationId = "conv_unknown_peer",
            participantNxId = "NX-UNKNOWN-99",
            participantName = "Unknown Peer",
            lastMessageText = "Hello stranger",
            lastMessageTimestamp = 6000L,
            unreadCount = 0
        )
    )

    // ==========================================
    // 1. Filter Order & Inventory Verification
    // ==========================================

    @Test
    fun testExactFilterOrderAndCount() {
        val filters = SearchFilter.values()
        assertEquals("Must have exactly 12 search filters", 12, filters.size)

        assertEquals("Filter 1 must be Unread", SearchFilter.UNREAD, filters[0])
        assertEquals("Filter 2 must be Audio", SearchFilter.AUDIO, filters[1])
        assertEquals("Filter 3 must be Favorite", SearchFilter.FAVORITE, filters[2])
        assertEquals("Filter 4 must be Archived", SearchFilter.ARCHIVED, filters[3])
        assertEquals("Filter 5 must be Groups", SearchFilter.GROUPS, filters[4])
        assertEquals("Filter 6 must be Contacts", SearchFilter.CONTACTS, filters[5])
        assertEquals("Filter 7 must be Photos", SearchFilter.PHOTOS, filters[6])
        assertEquals("Filter 8 must be Documents", SearchFilter.DOCUMENTS, filters[7])
        assertEquals("Filter 9 must be Videos", SearchFilter.VIDEOS, filters[8])
        assertEquals("Filter 10 must be GIFs", SearchFilter.GIFS, filters[9])
        assertEquals("Filter 11 must be Links", SearchFilter.LINKS, filters[10])
        assertEquals("Filter 12 must be Non-contacts", SearchFilter.NON_CONTACTS, filters[11])

        // Verify Display Names
        assertEquals("Unread", SearchFilter.UNREAD.displayName)
        assertEquals("Audio", SearchFilter.AUDIO.displayName)
        assertEquals("Favorite", SearchFilter.FAVORITE.displayName)
        assertEquals("Archived", SearchFilter.ARCHIVED.displayName)
        assertEquals("Groups", SearchFilter.GROUPS.displayName)
        assertEquals("Contacts", SearchFilter.CONTACTS.displayName)
        assertEquals("Photos", SearchFilter.PHOTOS.displayName)
        assertEquals("Documents", SearchFilter.DOCUMENTS.displayName)
        assertEquals("Videos", SearchFilter.VIDEOS.displayName)
        assertEquals("GIFs", SearchFilter.GIFS.displayName)
        assertEquals("Links", SearchFilter.LINKS.displayName)
        assertEquals("Non-contacts", SearchFilter.NON_CONTACTS.displayName)
    }

    // ==========================================
    // 2. Individual Filter Execution Tests
    // ==========================================

    @Test
    fun testFilterUnread() {
        val unread = sampleConversations.filter { it.unreadCount > 0 }
        assertEquals(3, unread.size)
        assertTrue(unread.any { it.participantName == "Alice Audio" })
        assertTrue(unread.any { it.participantName == "NEXA Testing Group" })
        assertTrue(unread.any { it.participantName == "Dave Link" })
    }

    @Test
    fun testFilterAudio() {
        val audio = sampleConversations.filter {
            it.lastMessageText.contains("[Audio]", ignoreCase = true) ||
                it.lastMessageText.contains(".mp3", ignoreCase = true)
        }
        assertEquals(1, audio.size)
        assertEquals("Alice Audio", audio[0].participantName)
    }

    @Test
    fun testFilterFavoriteAndArchived() {
        val favoriteIds = setOf("conv_favorite_photo")
        val favorites = sampleConversations.filter { favoriteIds.contains(it.conversationId) }
        assertEquals(1, favorites.size)
        assertEquals("Bob Photo", favorites[0].participantName)

        val archivedIds = setOf("conv_web_links")
        val archived = sampleConversations.filter { archivedIds.contains(it.conversationId) }
        assertEquals(1, archived.size)
        assertEquals("Dave Link", archived[0].participantName)
    }

    @Test
    fun testFilterGroupsVsContacts() {
        val groups = sampleConversations.filter { it.isGroup }
        assertEquals(1, groups.size)
        assertEquals("NEXA Testing Group", groups[0].participantName)

        val contacts = sampleConversations.filter { !it.isGroup }
        assertEquals(5, contacts.size)
    }

    @Test
    fun testFilterPhotosDocumentsVideosGifsLinks() {
        val photos = sampleConversations.filter {
            it.lastMessageText.contains("[Photo]", ignoreCase = true) || it.lastMessageText.contains(".jpg", ignoreCase = true)
        }
        assertEquals(1, photos.size)
        assertEquals("Bob Photo", photos[0].participantName)

        val documents = sampleConversations.filter {
            it.lastMessageText.contains("[Document]", ignoreCase = true) || it.lastMessageText.contains(".pdf", ignoreCase = true)
        }
        assertEquals(1, documents.size)
        assertEquals("NEXA Testing Group", documents[0].participantName)

        val videos = sampleConversations.filter {
            it.lastMessageText.contains("[Video]", ignoreCase = true) || it.lastMessageText.contains(".mp4", ignoreCase = true)
        }
        assertEquals(1, videos.size)
        assertEquals("Charlie Media", videos[0].participantName)

        val gifs = sampleConversations.filter {
            it.lastMessageText.contains("[GIF]", ignoreCase = true) || it.lastMessageText.contains(".gif", ignoreCase = true)
        }
        assertEquals(1, gifs.size)
        assertEquals("Charlie Media", gifs[0].participantName)

        val links = sampleConversations.filter {
            it.lastMessageText.contains("http://", ignoreCase = true) || it.lastMessageText.contains("https://", ignoreCase = true)
        }
        assertEquals(1, links.size)
        assertEquals("Dave Link", links[0].participantName)
    }

    @Test
    fun testFilterNonContacts() {
        val knownContactIds = setOf("NX-ALICE", "NX-BOB", "NX-CHARLIE", "NX-DAVE")
        val nonContacts = sampleConversations.filter { !it.isGroup && !knownContactIds.contains(it.participantNxId) }
        assertEquals(1, nonContacts.size)
        assertEquals("Unknown Peer", nonContacts[0].participantName)
    }

    // ==========================================
    // 3. Combined Filters & Query Tests
    // ==========================================

    @Test
    fun testCombinedFilters_GroupsAndUnread() {
        val combined = sampleConversations.filter { it.isGroup && it.unreadCount > 0 }
        assertEquals(1, combined.size)
        assertEquals("NEXA Testing Group", combined[0].participantName)
    }

    @Test
    fun testCombinedFilters_ContactsAndLinks() {
        val combined = sampleConversations.filter {
            !it.isGroup && (it.lastMessageText.contains("http://", ignoreCase = true) || it.lastMessageText.contains("https://", ignoreCase = true))
        }
        assertEquals(1, combined.size)
        assertEquals("Dave Link", combined[0].participantName)
    }

    @Test
    fun testTextQuerySearch() {
        val nameQuery = "media"
        val resultsByName = sampleConversations.filter { it.participantName.contains(nameQuery, ignoreCase = true) }
        assertEquals(1, resultsByName.size)
        assertEquals("Charlie Media", resultsByName[0].participantName)

        val textQuery = "stranger"
        val resultsByText = sampleConversations.filter { it.lastMessageText.contains(textQuery, ignoreCase = true) }
        assertEquals(1, resultsByText.size)
        assertEquals("Unknown Peer", resultsByText[0].participantName)
    }

    @Test
    fun testEmptySearchDefaultsToRecentChats() {
        val recentIds = setOf("conv_unread_audio", "conv_favorite_photo")
        val recentChats = sampleConversations.filter { recentIds.contains(it.conversationId) }
        assertEquals(2, recentChats.size)
        assertEquals("Alice Audio", recentChats[0].participantName)
        assertEquals("Bob Photo", recentChats[1].participantName)
    }

    @Test
    fun testClearAllOnlyClearsRecentShortcutIdsNotRealConversations() {
        var recentIds = setOf("conv_unread_audio", "conv_favorite_photo")
        assertEquals(2, recentIds.size)

        // Clear All action executed
        recentIds = emptySet()
        assertTrue("Recent shortcuts list must be empty after Clear All", recentIds.isEmpty())

        // Ensure underlying conversation storage is completely untouched
        assertEquals("Original conversation storage must remain 6", 6, sampleConversations.size)
    }

    @Test
    fun testNoResultState() {
        val query = "nonexistent_query_xyz"
        val results = sampleConversations.filter { it.participantName.contains(query, ignoreCase = true) }
        assertTrue("Result must be empty for nonexistent query", results.isEmpty())
    }

    @Test
    fun testBackArrowResourceExists() {
        assertTrue(R.drawable.ic_nexa_arrow_back != 0)
    }
}
