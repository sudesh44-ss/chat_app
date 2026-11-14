# NEXA — Phase 1: UI & Navigation Implementation Plan

**Repository:** `H:\chat_app`  
**Subsystem:** Navigation Graph, Screen Wiring & UI Interaction Completion  
**Scope:** Controlled Step-by-Step Implementation Specifications  
**Date:** November 2026  
**Status:** **PROPOSED — AWAITING USER APPROVAL PRIOR TO IMPLEMENTATION**

---

## 1. Remediation Scope & Guidelines

This plan outlines the minimal, non-destructive code modifications required to connect orphaned screens, fix incorrect navigation paths, provide proper UI screens for unhandled settings actions, and correct Quick Action menu targets without modifying cryptographic engines, database schemas, messaging transports, or backend protocols.

---

## 2. Issue Remediation Specifications

### Issue 1: Reconnecting Orphaned `PendingRequestsScreen` (P0)
- **Exact Files & Functions:**
  - `app/src/main/java/com/nexa/app/presentation/contacts/ContactListScreen.kt` &rarr; `ContactListScreen()` (TopAppBar actions)
  - `app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt` &rarr; `MainChatDashboardScreen()` (NexaQuickActionMenu callback)
- **Current Behavior:**
  - In `ContactListScreen.kt` line 108, `onNavigateToPending: () -> Unit` is passed into the composable but never invoked or bound to any button or badge.
  - In `MainChatDashboardScreen.kt` line 415, `onNavigateConnectionRequests` calls `onOpenContacts()`, which lands on `ContactListScreen` where no pending request entry point exists.
  - `PendingRequestsScreen.kt` is completely unreachable by the user.
- **Expected Behavior:**
  - Tapping "Connection Requests" in the Dashboard Quick Action FAB menu navigates directly to `Screen.PendingRequests.route`.
  - In `ContactListScreen.kt`, a dedicated badge/icon in the TopAppBar (or a banner above the contact list) displays pending connection request count and navigates to `Screen.PendingRequests.route` when tapped.
- **Required Changes:**
  1. In `MainChatDashboardScreen.kt`, add `onOpenPendingRequests: () -> Unit = {}` callback and wire `onNavigateConnectionRequests = { isPlusMenuOpen = false; onOpenPendingRequests() }`.
  2. In `AuthNavGraph.kt` under `composable(route = Screen.Dashboard.route)`, pass `onOpenPendingRequests = { navController.navigate(Screen.PendingRequests.route) }`.
  3. In `ContactListScreen.kt` TopAppBar `actions`, add an `IconButton` with `Icons.Default.PersonAdd` or badge calling `onNavigateToPending()`.
- **Dependencies:** None (uses existing `Screen.PendingRequests` route and `ContactViewModel`).
- **Risk Level:** Very Low.
- **Test Cases Required:**
  - `testPendingRequestsNavigationFromQuickActionMenu`
  - `testPendingRequestsNavigationFromContactsTopBar`

---

### Issue 2: Incorrect Route — `SettingsDestination.PRIVACY_AND_SAFETY` (P1)
- **Exact Files & Functions:**
  - `app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt` &rarr; `Screen` & `SettingsDestination.PRIVACY_AND_SAFETY`
  - New File: `app/src/main/java/com/nexa/app/presentation/settings/privacy/PrivacySettingsScreen.kt`
- **Current Behavior:**
  - In `AuthNavGraph.kt:687`, `SettingsDestination.PRIVACY_AND_SAFETY` executes `navController.navigate(Screen.NotificationSettings.route)`.
  - User lands on notification sounds and channels instead of privacy preferences.
- **Expected Behavior:**
  - Navigates to a dedicated `PrivacySettingsScreen` that exposes the user privacy controls already built into `ChatPrivacyPrefs.kt`:
    - Read Receipts toggle (`readReceiptsEnabled`)
    - Typing Indicators mode (`typingIndicatorMode`: EVERYONE / NOBODY)
    - Online Presence mode (`onlinePresenceMode`: EVERYONE / MY_CONTACTS / NOBODY)
    - Transport badges display (`showTransportBadges`)
    - Preferred transport routing (`preferredTransportRoute`: AUTO, INTERNET, MESH, SMS).
- **Required Changes:**
  1. Add `data object PrivacySettings : Screen("privacy_settings")` to `Screen` sealed class in `AuthNavGraph.kt`.
  2. Implement `PrivacySettingsScreen.kt` in `presentation/settings/privacy/` taking `app.chatPrivacyPrefs` and rendering clean Liquid Glass switches/pickers.
  3. In `AuthNavGraph.kt`, update `SettingsDestination.PRIVACY_AND_SAFETY` to `navController.navigate(Screen.PrivacySettings.route)`.
  4. Register `composable(route = Screen.PrivacySettings.route)` in `AuthNavGraph.kt`.
- **Dependencies:** Existing `app.chatPrivacyPrefs` (`ChatPrivacyPrefs.kt`).
- **Risk Level:** Low.
- **Test Cases Required:**
  - `testPrivacySettingsRouteRegistered`
  - `testPrivacyPreferencesPersistence`

---

### Issue 3: Incorrect Route — `SettingsDestination.DEVICES` (P1)
- **Exact Files & Functions:**
  - `app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt` &rarr; `SettingsDestination.DEVICES`
  - New File: `app/src/main/java/com/nexa/app/presentation/settings/devices/DevicesScreen.kt`
- **Current Behavior:**
  - In `AuthNavGraph.kt:696`, `SettingsDestination.DEVICES` executes `navController.navigate(Screen.Security.route)`.
  - User lands on the generic cryptographic keys view.
- **Expected Behavior:**
  - Navigates to a dedicated `DevicesScreen` displaying:
    - Current Active Device card (Hardware model, Android OS version, Hardware Keystore backing badge, Active since timestamp).
    - Session security details & linked devices list.
    - Revoke Session / Unlink Device action.
- **Required Changes:**
  1. Add `data object Devices : Screen("devices")` to `Screen` in `AuthNavGraph.kt`.
  2. Implement `DevicesScreen.kt` in `presentation/settings/devices/` using `authRepository.getActiveProfile()`.
  3. In `AuthNavGraph.kt`, update `SettingsDestination.DEVICES` to `navController.navigate(Screen.Devices.route)`.
  4. Register `composable(route = Screen.Devices.route)` in `AuthNavGraph.kt`.
- **Dependencies:** `AuthRepository`.
- **Risk Level:** Low.
- **Test Cases Required:**
  - `testDevicesRouteRegistered`
  - `testActiveDeviceDisplay`

---

### Issue 4: Incorrect Route — `SettingsDestination.ABOUT` (P1)
- **Exact Files & Functions:**
  - `app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt` &rarr; `SettingsDestination.ABOUT`
  - New File: `app/src/main/java/com/nexa/app/presentation/settings/about/AboutScreen.kt`
- **Current Behavior:**
  - In `AuthNavGraph.kt:699`, `SettingsDestination.ABOUT` executes `navController.navigate(Screen.Security.route)`.
- **Expected Behavior:**
  - Navigates to a dedicated `AboutScreen` displaying:
    - NEXA Logo, version string ("v2.0.0"), and build type.
    - Zero-Knowledge Architecture summary (Double Ratchet E2EE, Delay-Tolerant Mesh, Zero-Plaintext Cloud Fallback).
    - Open-source cryptographic library credits (Libsignal, Bouncy Castle, KaTeX).
    - Terms of Service & Privacy Policy dialog actions.
- **Required Changes:**
  1. Add `data object About : Screen("about")` to `Screen` in `AuthNavGraph.kt`.
  2. Implement `AboutScreen.kt` in `presentation/settings/about/`.
  3. In `AuthNavGraph.kt`, update `SettingsDestination.ABOUT` to `navController.navigate(Screen.About.route)`.
  4. Register `composable(route = Screen.About.route)` in `AuthNavGraph.kt`.
- **Dependencies:** None.
- **Risk Level:** Very Low.
- **Test Cases Required:**
  - `testAboutRouteRegistered`

---

### Issue 5: Empty Settings Handlers (`LANGUAGE`, `ARCHIVED_CHATS`, `NETWORK`, `STORAGE_AND_DATA`, `HELP`) (P2)
- **Exact Files & Functions:**
  - `app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt` &rarr; Lines 692–698
  - New Files in `presentation/settings/`:
    - `presentation/settings/language/LanguageSelectionDialog.kt`
    - `presentation/settings/archived/ArchivedChatsScreen.kt`
    - `presentation/settings/network/NetworkSettingsScreen.kt`
    - `presentation/settings/storage/StorageAndDataScreen.kt`
    - `presentation/settings/help/HelpScreen.kt`
- **Current Behavior:**
  - Tapping Language, Archived Chats, Network, Storage & Data, or Help executes `{}` with zero response.
- **Expected Behavior:**
  - **Language:** Opens a language selection dialog (English - System Default, Hindi, Regional locales) or confirms system locale binding.
  - **Archived Chats:** Navigates to `ArchivedChatsScreen` displaying hidden conversations with unarchive options.
  - **Network:** Navigates to `NetworkSettingsScreen` displaying real-time transport telemetry (WebSocket status, DTN Mesh node count, Binary SMS status, `NetworkClassification` state).
  - **Storage & Data:** Navigates to `StorageAndDataScreen` displaying local SQLite database size, cached media size, auto-download toggles, and "Clear Media Cache" action.
  - **Help:** Navigates to `HelpScreen` displaying FAQ accordion, mesh communication guide, and support contact info.
- **Required Changes:**
  1. Register routes in `Screen`: `Language`, `ArchivedChats`, `NetworkSettings`, `StorageAndData`, `Help`.
  2. Implement corresponding screens/dialogs in `presentation/settings/`.
  3. Wire destinations in `SettingsScreen.kt` and `AuthNavGraph.kt`.
- **Dependencies:** `NetworkMonitor`, `ChatRepository`, `MediaPrefs`.
- **Risk Level:** Low.
- **Test Cases Required:**
  - `testSettingsAllDestinationsRoutedWithoutEmptyHandlers`

---

### Issue 6: Quick Action Menu Target Corrections (P2)
- **Exact Files & Functions:**
  - `app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt` &rarr; Lines 411–434
- **Current Behavior:**
  - "Nearby Mesh Discovery" opens `QrDisplayScreen` (QR code).
  - "Create Group" opens the single 1:1 chat modal asking for "Recipient NX-ID".
- **Expected Behavior:**
  - "Nearby Mesh Discovery" should navigate to `VerificationRadarScreen` (radar peer discovery).
  - "Create Group" should open a dedicated group creation dialog (Group Name + multi-contact selection).
- **Required Changes:**
  1. In `MainChatDashboardScreen.kt`, update `onNavigateNearbyMesh` to open `Screen.VerificationRadar.route`.
  2. Implement `CreateGroupDialog` accepting group name and member multi-selection, calling `chatRepository.createOrGetConversation(isGroup = true)`.
- **Dependencies:** `ChatRepository`.
- **Risk Level:** Low.
- **Test Cases Required:**
  - `testQuickActionMenuMeshRouting`
  - `testGroupCreationModal`

---

## 3. Implementation Order & Approval Gate

1. **Step 1:** Reconnect `PendingRequestsScreen` (P0 Blocker).
2. **Step 2:** Implement `PrivacySettingsScreen` and fix `PRIVACY_AND_SAFETY` route (P1).
3. **Step 3:** Implement `DevicesScreen` and `AboutScreen`, fixing their routes from `Security` (P1).
4. **Step 4:** Implement `NetworkSettingsScreen`, `StorageAndDataScreen`, `ArchivedChatsScreen`, and `HelpScreen`, eliminating all empty handlers (P2).
5. **Step 5:** Correct Quick Action FAB targets (Mesh Discovery and Create Group) (P2).
6. **Step 6:** Run complete regression test suite and verify UI quality at 360x800, 390x844, 412x915, and 430x932.

---

## 4. Approval Gate

In strict accordance with Phase 1 audit instructions:
- **Both audit reports have been compiled and finalized in `docs/audit/`.**
- **NO SOURCE CODE HAS BEEN MODIFIED.**
- **Awaiting explicit user approval before proceeding with code modifications.**
