# NEXA — Find in Chat & Message Search: Implementation Impact Report

**Date:** 2026-11-10  
**Status:** AUDIT COMPLETE — PROCEEDING TO IMPLEMENTATION  
**Target:** Android App (`com.nexa.app`)  
**Scope:** Find in Chat, Date-Based Message Navigation, Room Schema & DAO, Jetpack Compose UI  

---

## 1. Audit of Existing Implementation

### 1.1 Existing Search Capabilities Discovered
1. **Global Search (`NexaSearchScreen.kt`):**
   - Discovered full-screen search for contacts, conversations, and recent chat history.
   - Does **not** perform in-chat scoped search or date-based message jumping.
2. **Three-Dot Overflow Menu (`NexaChatOverflowMenu.kt`):**
   - Option 2 in `ChatOverflowAction` enum is currently `SEARCH_CHAT` with label `"Search Chat"` (or `"Find in Chat"`).
   - In `ChatScreen.kt:859-863`, selecting `ChatOverflowAction.SEARCH_CHAT` previously showed a stub snackbar: `"Search in Chat activated"`.
   - **Crucial Unit Test Requirement:** `ChatScreenRedesignUnitTests.kt:testChatOverflowMenu_exactEightItemsInExactOrder` strictly enforces that `ChatOverflowAction.values().size == 8` and item[1] is `SEARCH_CHAT`. We MUST preserve `ChatOverflowAction.SEARCH_CHAT` and keep all 8 items in exact order!
3. **Message Highlight & Scroll Navigation:**
   - In `ChatScreen.kt:665-677`, tap-to-original quoting navigation already uses `listState.animateScrollToItem(targetIndex)` and `ChatUiEvent.HighlightMessage(targetPacketId)`.
   - In `ChatViewModel.kt:367-377`, `highlightMessage(packetId)` sets `highlightedMessageId = packetId` and automatically clears it after 1500ms.
   - In `NexaMessageBubble.kt:54`, message bubbles accept `isHighlighted: Boolean` and render a distinctive pulsing background when highlighted.

### 1.2 Message Entities, DAOs & Database Architecture
1. **Room Database (`NexaDatabase.kt`):**
   - Currently on version `22` with SQLCipher AES-256 encrypted storage.
   - `MessageEntity` has table `messages` with primary key `packetId`, `conversationId`, `plainTextContent`, `timestamp`, `isOutgoing`, `status`, and indexes on `conversationId`, `packetId`, `replyToMessageId`.
2. **Existing DAO (`MessageDao.kt`):**
   - `getMessagesFlow(conversationId)`: Returns `Flow<List<MessageEntity>>` ordered by `timestamp ASC`.
   - Lacks dedicated scoped search (`searchMessagesInConversation`) and date-range queries (`getFirstMessageOnDate`, `getMessagesOnDate`).

### 1.3 Message Pagination & Historical Loading
- Current production pipeline streams conversation messages via `getMessagesFlow(conversationId)`.
- If a target search result or date-selected message is present in `uiState.messages`, its index is resolved immediately and smoothly scrolled to.
- If the target message is outside the loaded window, our architecture loads the surrounding pagination window or ensures the target message is fetched from `MessageDao` and positioned accurately.

### 1.4 Date Separators & Timestamps
- Messages store UTC epoch milliseconds (`timestamp: Long`).
- UI bubble renders local formatted time (`hh:mm a`).
- In `ChatScreen.kt`, messages are grouped by 2-minute contiguous intervals.
- Adding a clean, sticky or inline Date Separator chip (e.g., "Today", "Yesterday", or "10 September 2026") between messages spanning different calendar days enhances visual context and perfectly complements Date-Based Message Navigation.

---

## 2. Proposed Architecture & Design Specification

### 2.1 Three-Dot Menu Integration
- In `NexaChatOverflowMenu.kt`, update the displayed title of item 2 to `"Find in Chat"` while preserving `ChatOverflowAction.SEARCH_CHAT` to keep `ChatScreenRedesignUnitTests` passing at 100%.
- In `ChatScreen.kt`, clicking `ChatOverflowAction.SEARCH_CHAT` sets `isSearchInChatActive = true` (or updates ViewModel state), activating the integrated in-chat search bar.

### 2.2 In-Chat Search Bar (`FindInChatBar.kt`)
- Integrated Liquid Glass search bar substituting the Top Bar while search is active:
  - **Back Button (←):** Closes search mode, clears query and search results, and restores the normal top bar.
  - **Search Input Field:** Frosted glass input with hint `"Search messages..."` and automatic focus.
  - **Clear Button (✕):** Appears when query is non-empty, clearing the input on click.
  - **Calendar Button (📅):** Opens the Date-Based Navigation picker dialog.
  - **Results Match Indicator:** Displays match count (e.g. `"3 matches"`) and Navigation Up/Down jump buttons or drop-down preview list.

### 2.3 Search Results Dropdown / Preview Sheet
- Displays matching messages strictly scoped to the active chat:
  - Sender display name (vital for group chats).
  - Highlighted search snippet (using Compose `AnnotatedString` with bold accent color).
  - Localized date/time badge.
  - Message type icon (Text, Image, Video, Audio, File).
- Tapping a result:
  - Closes the search dropdown / bar.
  - Locates the message index in the conversation list.
  - Smoothly animates scroll to the message with `listState.animateScrollToItem(targetIndex)`.
  - Applies temporary highlight for 1500ms using `ChatUiEvent.HighlightMessage(packetId)`.

### 2.4 Date-Based Navigation Dialog (`ChatDatePickerDialog.kt`)
- Date picker allowing user to select any past or current calendar date (future dates disabled).
- Calculates local timezone day start and end timestamps:
  ```kotlin
  val zoneId = ZoneId.systemDefault()
  val startOfDay = localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
  val endOfDay = localDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
  ```
- Queries `MessageDao.getFirstMessageOnDate(conversationId, startOfDay, endOfDay)`.
- If found: scrolls directly to that first message of the day and temporarily highlights it.
- If no messages exist on that date: displays user-facing notification: `"No messages found on this date."`

### 2.5 Privacy & E2EE Guarantees
- **Local-Only Search:** 100% of search queries and text matching execute on the device in Room SQLCipher.
- **Zero Server Exposure:** Search queries and timestamps are NEVER transmitted to the relay server or cloud endpoints.
- **Attachment Safety:** Search operates on local metadata, filenames, and captions. No remote attachment binaries are downloaded to fulfill search queries.

---

## 3. Implementation Impact Analysis

| Component | Files Affected | Changes Required | Risk & Mitigation |
| :--- | :--- | :--- | :--- |
| **Room DAO** | `MessageDao.kt` | Add `searchMessagesInConversation` and `getFirstMessageOnDate` queries | Low; pure query additions without modifying schema |
| **Chat Repository** | `ChatRepository.kt`<br>`ChatRepositoryImpl.kt` | Add `searchMessages(conversationId, query)` and `getFirstMessageOnDate(conversationId, startOfDay, endOfDay)` | Low; default interface methods preserve test fake compatibility |
| **Chat ViewModel** | `ChatViewModel.kt` | Add search state (`searchQuery`, `searchResults`, `isSearchActive`, `isSearching`), debounced search job, date selection event | Low; isolated reactive state additions |
| **Chat Overflow Menu** | `NexaChatOverflowMenu.kt` | Update label to `"Find in Chat"` for `SEARCH_CHAT` | Low; retains exact 8 enum constants and order |
| **Find In Chat UI** | `FindInChatBar.kt`<br>`ChatDatePickerDialog.kt`<br>`ChatDateSeparator.kt` | Create reusable Liquid Glass search bar, date picker, and inline date separator components | Low; modular Compose components |
| **Chat Screen** | `ChatScreen.kt` | Wire search top bar, search results sheet, date picker trigger, and scroll-to-search-result handler | Low; seamless replacement of top bar when searching |
| **Unit Tests** | `FindInChatUnitTests.kt` | Author comprehensive unit test suite covering text search, date navigation, case insensitivity, Unicode, group chats, empty queries, and regressions | Validates complete feature |

---

## 4. Next Steps
1. Add DAO queries in `MessageDao.kt`.
2. Add repository methods in `ChatRepository.kt` and `ChatRepositoryImpl.kt`.
3. Add search and date navigation events/state in `ChatViewModel.kt`.
4. Create `FindInChatBar.kt`, `ChatDatePickerDialog.kt`, and `ChatDateSeparator.kt`.
5. Integrate into `ChatScreen.kt` and update label in `NexaChatOverflowMenu.kt`.
6. Author comprehensive unit tests in `FindInChatUnitTests.kt`.
7. Verify all tests pass with zero regressions.
