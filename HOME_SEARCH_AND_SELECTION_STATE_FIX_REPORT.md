# Home Search and Selection State Fix Report

## Root Causes

- Search had no query-driven suggestions. The advanced search screen displayed recent conversations and filter chips, but did not derive contact, conversation, or content-category suggestions from local data.
- Several filter predicates were guesses rather than data-backed operations: favorite/archive used conversation ID text, media used message-preview text, and Contacts treated an empty contact list as matching every direct chat.
- The Home screen always added the Stories row, even while the search field was focused or a query was active.
- Selection toolbar visibility used an independent `isSelectionMode` Boolean alongside the selected-ID set. Deselecting the final chat could leave that Boolean true. The normal row long-press also opened a context menu instead of entering selection mode.

## Audit

Inspected the Home dashboard and search field, advanced search screen and filter chips, filter enum, selected-chat toolbar, conversation row, chat and message repository APIs, message/contact models, archive DAO/repository path, status row/view model, navigation wiring, and existing Home/search tests.

## Files Modified

- `app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaSearchBar.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaSelectionToolbar.kt`
- `app/src/main/java/com/nexa/app/presentation/search/NexaSearchScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/search/model/SearchFilter.kt`
- `app/src/test/java/com/nexa/app/AdvancedSearchScreenUnitTests.kt`

## Changes

- Added focus reporting to the Home search field. Stories are conditionally omitted during active search, without altering the status streams; clearing/exiting search restores the normal layout.
- The dedicated search screen now presents matching local contacts, conversation/group names, and applicable content-category suggestions while focused. Suggestions use names/IDs rather than message bodies. Matching message text and media metadata come from the local message repository.
- Implemented all displayed filters using available local data: unread/manual-unread state, starred messages for Favorites, archive state, group/contact membership, stored message types for media, and URLs in message text for Links. Filters combine with AND semantics and update from reactive conversation/message flows. Archived conversations are included in search.
- Selected-chat mode is derived from IDs in the current Chats/Groups tab. Long-press enters selection, the last deselection removes the toolbar immediately, Select All toggles to Deselect All, and tab changes/list refreshes prune invalid IDs. Back first clears selection, then exits search. Bulk archive/delete operate on a snapshot of currently selected IDs.

## Verification

- `:app:testDebugUnitTest --tests com.nexa.app.AdvancedSearchScreenUnitTests` — passed.
- `:app:assembleDebug` — passed.
- `:app:testDebugUnitTest --tests com.nexa.app.AdvancedSearchScreenUnitTests --tests com.nexa.app.HomeScreenRedesignUnitTests --tests com.nexa.app.HomeScreenResilienceUnitTests` — passed.
- `:app:connectedDebugAndroidTest` — Android app and test APKs compiled/packaged, but device execution could not start: Gradle reported **No connected devices**. `adb` is unavailable on PATH.
- The existing `androidTest` source contains only a placeholder context test; no Compose UI interaction tests cover these flows.

Builds emitted existing Kotlin/JDK target and unrelated deprecation warnings.

## Remaining Limitations

- Search history is not persisted; the screen retains its existing recent-conversation shortcuts instead of inventing recent searches.
- Favorites represent conversations containing locally starred messages; there is no separate conversation-favorites property in the current domain model.
- Device-level keyboard, Back, scrolling, and selection interaction verification remains pending an available Android device/emulator.
