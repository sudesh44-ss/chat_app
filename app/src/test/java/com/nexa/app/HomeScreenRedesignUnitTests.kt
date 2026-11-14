package com.nexa.app

import androidx.compose.ui.graphics.Color
import com.nexa.app.core.theme.AppearanceMode
import com.nexa.app.core.theme.NexaDarkTextPrimary
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.core.theme.NexaLightTextPrimary
import com.nexa.app.core.theme.NexaPureBlack
import com.nexa.app.core.theme.NexaPureWhite
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.presentation.dashboard.components.BottomNavDestination
import com.nexa.app.presentation.dashboard.components.HomeDashboardTab
import com.nexa.app.presentation.dashboard.isGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenRedesignUnitTests {

    // ==========================================
    // 1. Group vs Individual Conversation Detection
    // ==========================================

    @Test
    fun testIsGroup_correctlyIdentifiesGroupsAndDirectChats() {
        val individualChat = ChatConversation(
            conversationId = "demo_personal_contact",
            participantNxId = "NX-DEMO-CONTACT",
            participantName = "Demo Contact",
            lastMessageText = "Hey! Welcome to NEXA.",
            lastMessageTimestamp = 1000L
        )
        assertFalse("Individual chat should not be identified as group", individualChat.isGroup)

        val groupChat = ChatConversation(
            conversationId = "demo_group_testing",
            participantNxId = "NX-DEMO-GROUP",
            participantName = "Demo Group",
            lastMessageText = "Welcome everyone!",
            lastMessageTimestamp = 2000L
        )
        assertTrue("Group chat should be identified as group", groupChat.isGroup)
    }

    // ==========================================
    // 2. Tab Filtering & Archive Logic
    // ==========================================

    @Test
    fun testTabFiltering_separatesChatsAndGroups() {
        val chats = listOf(
            ChatConversation("conv_1", "NX-1", "Alice", "Hi", 1000L),
            ChatConversation("demo_group_testing", "NX-DEMO-GROUP", "Testing Group", "Hi all", 2000L),
            ChatConversation("conv_2", "NX-2", "Bob", "Hey", 3000L)
        )

        val directChats = chats.filter { !it.isGroup }
        assertEquals(2, directChats.size)
        assertEquals("Alice", directChats[0].participantName)
        assertEquals("Bob", directChats[1].participantName)

        val groupChats = chats.filter { it.isGroup }
        assertEquals(1, groupChats.size)
        assertEquals("Testing Group", groupChats[0].participantName)
    }

    @Test
    fun testArchivedConversationsAreExcluded() {
        val chats = listOf(
            ChatConversation("conv_1", "NX-1", "Alice", "Hi", 1000L),
            ChatConversation("conv_2", "NX-2", "Bob", "Hey", 3000L)
        )
        val archivedIds = setOf("conv_1")

        val visible = chats.filter { !archivedIds.contains(it.conversationId) }
        assertEquals(1, visible.size)
        assertEquals("Bob", visible[0].participantName)
    }

    // ==========================================
    // 3. Search and Pinned Sorting Logic
    // ==========================================

    @Test
    fun testPinnedConversationsFloatToTop() {
        val chats = listOf(
            ChatConversation("conv_1", "NX-1", "Alice", "Hi", 1000L),
            ChatConversation("conv_2", "NX-2", "Bob", "Hey", 5000L),
            ChatConversation("conv_3", "NX-3", "Charlie", "Yo", 2000L)
        )
        val pinnedIds = setOf("conv_1") // Alice is oldest (1000L) but pinned

        val sorted = chats.sortedWith(
            compareByDescending<ChatConversation> { pinnedIds.contains(it.conversationId) }
                .thenByDescending { it.lastMessageTimestamp }
        )

        assertEquals("Alice must be first because pinned", "conv_1", sorted[0].conversationId)
        assertEquals("Bob must be second by timestamp", "conv_2", sorted[1].conversationId)
        assertEquals("Charlie must be third", "conv_3", sorted[2].conversationId)
    }

    @Test
    fun testSearchFilteringMatchesNameAndMessage() {
        val chats = listOf(
            ChatConversation("conv_1", "NX-1", "Alice Sharma", "Secret code 42", 1000L),
            ChatConversation("conv_2", "NX-2", "Bob Verma", "Lunch today?", 2000L)
        )

        val nameSearch = chats.filter { it.participantName.contains("sharma", ignoreCase = true) }
        assertEquals(1, nameSearch.size)
        assertEquals("Alice Sharma", nameSearch[0].participantName)

        val textSearch = chats.filter { it.lastMessageText.contains("lunch", ignoreCase = true) }
        assertEquals(1, textSearch.size)
        assertEquals("Bob Verma", textSearch[0].participantName)
    }

    // ==========================================
    // 4. Design Tokens & Appearance Architecture
    // ==========================================

    @Test
    fun testPureBlackAndPureWhiteDesignTokens() {
        assertEquals("Dark mode background must be Pure Black #000000", Color(0xFF000000), NexaPureBlack)
        assertEquals("Light mode background must be Pure White #FFFFFF", Color(0xFFFFFFFF), NexaPureWhite)
        assertEquals("Dark mode primary text must be Pure White #FFFFFF", Color(0xFFFFFFFF), NexaDarkTextPrimary)
        assertEquals("Light mode primary text must be Pure Black #000000", Color(0xFF000000), NexaLightTextPrimary)
    }

    @Test
    fun testLiquidGlassTokens() {
        assertEquals(24f, NexaGlassTokens.LargeCardRadius.value, 0.01f)
        assertEquals(18f, NexaGlassTokens.SmallCardRadius.value, 0.01f)
        assertEquals(32f, NexaGlassTokens.SearchRadius.value, 0.01f)
        assertEquals(36f, NexaGlassTokens.PillRadius.value, 0.01f)
        assertEquals(1f, NexaGlassTokens.NormalBorderWidth.value, 0.01f)
        assertEquals(1.5f, NexaGlassTokens.SelectedBorderWidth.value, 0.01f)
        assertEquals(66f, NexaGlassTokens.FloatingCapsuleHeight.value, 0.01f)
        assertEquals(58f, NexaGlassTokens.FloatingPlusSize.value, 0.01f)
    }

    @Test
    fun testAppearanceModeEnumValues() {
        val modes = AppearanceMode.values()
        assertEquals(3, modes.size)
        assertTrue(modes.contains(AppearanceMode.SYSTEM))
        assertTrue(modes.contains(AppearanceMode.DARK))
        assertTrue(modes.contains(AppearanceMode.LIGHT))
    }

    // ==========================================
    // 5. Navigation & Context Enums
    // ==========================================

    @Test
    fun testBottomNavDestinations() {
        val destinations = BottomNavDestination.values()
        assertEquals(3, destinations.size)
        assertTrue(destinations.contains(BottomNavDestination.HOME))
        assertTrue(destinations.contains(BottomNavDestination.PROFILE))
        assertTrue(destinations.contains(BottomNavDestination.SETTINGS))
    }

    @Test
    fun testHomeDashboardTabs() {
        val tabs = HomeDashboardTab.values()
        assertEquals(2, tabs.size)
        assertTrue(tabs.contains(HomeDashboardTab.CHATS))
        assertTrue(tabs.contains(HomeDashboardTab.GROUPS))
    }

    // ==========================================
    // 6. Custom NEXA Icon Resource Verification
    // ==========================================

    @Test
    fun testCustomNexaIconDrawablesExist() {
        val requiredDrawables = listOf(
            R.drawable.ic_nexa_home,
            R.drawable.ic_nexa_profile,
            R.drawable.ic_nexa_search,
            R.drawable.ic_nexa_headphones,
            R.drawable.ic_nexa_more_vert,
            R.drawable.ic_nexa_plus,
            R.drawable.ic_nexa_close,
            R.drawable.ic_nexa_chats,
            R.drawable.ic_nexa_groups,
            R.drawable.ic_nexa_group_48,
            R.drawable.ic_nexa_contacts,
            R.drawable.ic_nexa_mesh,
            R.drawable.ic_nexa_handshake,
            R.drawable.ic_nexa_create_group,
            R.drawable.ic_nexa_pin,
            R.drawable.ic_nexa_archive,
            R.drawable.ic_nexa_mute,
            R.drawable.ic_nexa_select,
            R.drawable.ic_nexa_delete,
            R.drawable.ic_nexa_mark_read,
            R.drawable.ic_nexa_block,
            R.drawable.ic_nexa_leave_group,
            R.drawable.ic_nexa_view_contact,
            R.drawable.ic_nexa_chevron_right,
            R.drawable.ic_nexa_wifi,
            R.drawable.ic_nexa_qr_code,
            R.drawable.ic_nexa_shield,
            R.drawable.ic_nexa_bell
        )

        for (resId in requiredDrawables) {
            assertTrue("Resource ID must be non-zero (valid compiled resource)", resId != 0)
        }
    }
}
