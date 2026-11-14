# NEXA Profile and Friend Request Navigation Fix Report

## Root Cause

The Home dashboard's `onOpenProfile` callback navigated to `Screen.Settings`. The top-bar avatar and bottom Profile button both reused that callback. The top-bar overflow menu's Settings item also reused the profile callback, so the profile and Settings actions were not independent.

## Routes and Callbacks Inspected

- `NexaTopAppBar`: top-right avatar, clickable NEXA brand, and overflow Settings callback.
- `MainChatDashboardScreen`: routes passed to the top bar and floating bottom navigation.
- `NexaFloatingBottomNavigation`: Home/Profile selected-state behavior.
- `AuthNavGraph`: `Screen.PendingRequests`, `Screen.Account`, and `Screen.Settings` definitions and destinations.
- `PendingRequestsScreen` and `ContactViewModel`: existing connection-request loading and action flow.
- `AccountScreen` and `SettingsScreen`: existing account information and bottom-navigation behavior.

## Changes Implemented

- Top-right profile avatar now navigates to the existing `Screen.PendingRequests` route.
- The overflow Settings action navigates independently to `Screen.Settings`; the brand click retains its previous Settings destination.
- Bottom Profile navigates to the existing `Screen.Account` route. Account now displays the shared Home/Profile navigation with Profile selected.
- Account's Home button returns to the existing Dashboard in the back stack, or navigates to it if absent. Repeated Account, Requests, and Settings navigation uses `launchSingleTop`.
- Settings is represented as a non-selected bottom-navigation destination; its Profile button now opens Account.
- Incoming Requests now renders the existing remote request list and request IDs, uses the existing accept/reject repository actions, displays available request time, and shows loading, empty, retryable fetch-error, and action-error states.
- Updated the bottom-navigation destination unit test for the separate Settings state.

## Files Modified

- `app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaTopAppBar.kt`
- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaFloatingBottomNavigation.kt`
- `app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt`
- `app/src/main/java/com/nexa/app/presentation/settings/SettingsScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/settings/account/AccountScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/contacts/ContactUiState.kt`
- `app/src/main/java/com/nexa/app/presentation/contacts/ContactViewModel.kt`
- `app/src/main/java/com/nexa/app/presentation/contacts/PendingRequestsScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/contacts/components/RequestItemCard.kt`
- `app/src/test/java/com/nexa/app/HomeScreenRedesignUnitTests.kt`

## Verification

- `.\gradlew.bat :app:compileDebugKotlin` — passed.
- `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` — passed; the app assembled and all 631 unit tests passed.
- `git diff --check` on the changed files — passed.
- Source-level callback/route inspection confirmed the independent destinations and back-stack callbacks described above.

## Remaining Limitations

- No emulator or device interaction test was run, so individual taps and visual/back behavior were not exercised at runtime. Navigation and selected-state behavior were verified from the wired callbacks and successful compilation/unit suite.
- The build emits existing Kotlin/JDK target and deprecation warnings; they did not prevent assembly or tests.
