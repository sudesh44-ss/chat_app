# NEXA — Phase 1: UI & Navigation Comprehensive Audit Report

**Audit Type:** Independent Source-Level UI & Navigation System Audit  
**Target Codebase:** `H:\chat_app` (Android Application Presentation Layer)  
**Authority Hierarchy:** `1architecture.md` / `docs/*.md` / Actual Source Code  
**Audit Completion Date:** November 2026  
**Status:** **AUDIT COMPLETE — AWAITING IMPLEMENTATION APPROVAL**

---

## 1. Executive Summary

This independent audit evaluates every screen, navigation route, composable destination, user interaction, theme token, and responsive layout across the NEXA Android application based on direct source-level verification.

NEXA's presentation layer contains **31 distinct screens and interactive flows** registered in `Screen` and wired inside [`AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt). The core messaging, authentication, QR handshake, cryptographic identity, and backup flows are architecturally mature. However, the audit has identified critical UI routing defects, one orphaned screen, three misrouted navigation handlers, five completely empty navigation handlers, and simulated/placeholder quick actions:

1. **Orphaned Screen:** [`PendingRequestsScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/PendingRequestsScreen.kt) exists with full incoming/outgoing state management, but has **zero reachable entry points** in the live UI because [`ContactListScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/ContactListScreen.kt#L108) leaves its navigation callback uninvoked, and [`MainChatDashboardScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt#L415) routes requests to the contacts list instead of pending requests.
2. **Incorrect Navigation Routes (3 Verified):**
   - `SettingsDestination.PRIVACY_AND_SAFETY` incorrectly routes to [`Screen.NotificationSettings.route`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt#L687).
   - `SettingsDestination.DEVICES` incorrectly routes to [`Screen.Security.route`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt#L696).
   - `SettingsDestination.ABOUT` incorrectly routes to [`Screen.Security.route`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt#L699).
3. **Empty Navigation Handlers (5 Verified):**
   - `SettingsDestination.LANGUAGE` &rarr; `{}` (No action).
   - `SettingsDestination.ARCHIVED_CHATS` &rarr; `{}` (No action).
   - `SettingsDestination.NETWORK` &rarr; `{}` (No action).
   - `SettingsDestination.STORAGE_AND_DATA` &rarr; `{}` (No action).
   - `SettingsDestination.HELP` &rarr; `{}` (No action).
4. **Placeholder Quick Actions:**
   - FAB option "Nearby Mesh Discovery" opens the personal QR identity display rather than mesh peer discovery.
   - FAB option "Create Group" reuses the 1:1 direct chat modal rather than a group creation flow.
   - Dashboard "Status / Stories" row displays temporary snackbars without persistence or viewer capability.

---

## 2. Complete Screen Inventory

Every screen composable in the Android application has been audited and classified:

| # | Screen Name | Source File | Functional Classification | Status Description |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `SplashScreen` | `presentation/splash/SplashScreen.kt` | Fully Functional | Animated shield, state evaluation, auto-routing to Welcome or Dashboard. |
| 2 | `WelcomeScreen` | `presentation/welcome/WelcomeScreen.kt` | Fully Functional | Google Credential Manager, Skip auth, direct reverse SMS, legal dialogs. |
| 3 | `ProfileSetupScreen` | `presentation/setup/ProfileSetupScreen.kt` | Fully Functional | Avatar picker, display name, bio, age/gender, reverse SMS trigger. |
| 4 | `VerificationRadarScreen` | `presentation/radar/VerificationRadarScreen.kt` | Fully Functional | Zero-cost UPI reverse SMS radar animation, 90s timeout, telephony listener. |
| 5 | `OtpVerificationScreen` | `presentation/otp/OtpVerificationScreen.kt` | Fully Functional | 6-digit OTP input, resend timer (60s), SMS retriever API integration. |
| 6 | `ProfileBadgeScreen` | `presentation/badge/ProfileBadgeScreen.kt` | Fully Functional | Cryptographic NX-ID badge card, QR code rendering, permission proceed button. |
| 7 | `PermissionGatewayScreen` | `presentation/permissions/PermissionGatewayScreen.kt` | Fully Functional | Permission cards (Contacts, Notifications, Nearby, SMS) with contracts. |
| 8 | `MainChatDashboardScreen` | `presentation/dashboard/MainChatDashboardScreen.kt` | Partially Functional | Real-time chat list, group switcher, search, long-press menu, FAB quick action menu. Quick action menu contains placeholder routings. |
| 9 | `ChatScreen` | `presentation/chat/ChatScreen.kt` | Fully Functional | Full E2EE messaging, Double Ratchet tick states, voice notes, attachments, P2P file cards. |
| 10 | `ChatWallpaperScreen` | `presentation/chat/settings/ChatWallpaperScreen.kt` | Fully Functional | Solid color and custom wallpaper selection, scoped to conversation. |
| 11 | `PinnedMessagesScreen` | `presentation/chat/settings/PinnedMessagesScreen.kt` | Fully Functional | Displays pinned message cards, unpin action, jump to message in conversation. |
| 12 | `MediaExplorerScreen` | `presentation/media_explorer/MediaExplorerScreen.kt` | Fully Functional | 3 tabs (Media, Docs, Links), grid view, full-screen viewer trigger. |
| 13 | `ProfileImageViewerScreen` | `presentation/common/viewer/ProfileImageViewerScreen.kt` | Fully Functional | 1:1 photo viewer dialog & screen, dark backdrop, pinch-to-zoom. |
| 14 | `ContactListScreen` | `presentation/contacts/ContactListScreen.kt` | Partially Functional | Dual sections (NEXA Contacts, Address Book), search, add by phone/NX-ID. Fails to expose entry point to pending requests. |
| 15 | `PendingRequestsScreen` | `presentation/contacts/PendingRequestsScreen.kt` | Fully Functional / **Orphaned** | Tabs (Incoming, Outgoing), Accept, Reject, Cancel buttons. Screen is fully built but unreachable from UI. |
| 16 | `ContactProfileScreen` | `presentation/profile/ContactProfileScreen.kt` | Fully Functional | Profile header, action buttons, nickname editor, media preview, block, clear chat. |
| 17 | `ContactDetailsScreen` | `presentation/contacts/details/ContactDetailsScreen.kt` | Fully Functional | Phone number, NX-ID, mutual connection status, remove contact confirmation. |
| 18 | `QrDisplayScreen` | `presentation/qr/QrDisplayScreen.kt` | Fully Functional | User's QR code identity card with NX-ID and public key, share action, camera button. |
| 19 | `QrScannerScreen` | `presentation/qr/QrScannerScreen.kt` | Fully Functional | CameraX viewfinder, flashlight toggle, QR bounding box, scanned peer bottom sheet. |
| 20 | `NexaSearchScreen` | `presentation/search/NexaSearchScreen.kt` | Fully Functional | Interactive search query, filter chips (Chats, Contacts, Media, Links), jump to chat. |
| 21 | `SettingsScreen` | `presentation/settings/SettingsScreen.kt` | Partially Functional | 13 settings items, fixed bottom navigation capsule. 4 items misrouted; 5 items empty handlers. |
| 22 | `AccountScreen` | `presentation/settings/account/AccountScreen.kt` | Fully Functional | Profile card, Change Phone Number, Security, Account Status, Logout confirmation. |
| 23 | `ChangePhoneNumberScreen` | `presentation/settings/account/ChangePhoneNumberScreen.kt` | Fully Functional | Current number card, country code picker, new phone input, verification trigger. |
| 24 | `SecurityScreen` | `presentation/settings/account/SecurityScreen.kt` | Fully Functional | Hardware Keystore status, Curve25519 fingerprint, Double Ratchet card. |
| 25 | `AccountStatusScreen` | `presentation/settings/account/AccountStatusScreen.kt` | Fully Functional | Deactivation vs Deletion selection, warnings and action triggers. |
| 26 | `AccountDeactivatedScreen`| `presentation/settings/account/deactivation/AccountDeactivationFlow.kt` | Fully Functional | Reversible deactivation notice, Reactivate account button restores session. |
| 27 | `AccountDeletionFlow` | `presentation/settings/account/deletion/AccountDeletionFlow.kt` | Fully Functional | 7-step wizard, typing confirmation ("DELETE"), irreversible key wipe. |
| 28 | `AppearanceSettingsScreen`| `presentation/settings/appearance/AppearanceSettingsScreen.kt` | Fully Functional | Theme mode picker (Pure Black AMOLED, Light, System Default), instant update. |
| 29 | `NotificationSettingsScreen` | `presentation/settings/notifications/NotificationSettingsScreen.kt` | Fully Functional | System permission warning, direct message toggles, group toggles, lock screen privacy mode, quiet hours. |
| 30 | `BackupSettingsScreen` | `presentation/backup/BackupSettingsScreen.kt` | Fully Functional | Drive authorization card, "Back Up Now", schedule selector, Wi-Fi only toggle, 12-word BIP-39 mnemonic phrase viewer card. |
| 31 | `RestoreBackupScreen` | `presentation/backup/RestoreBackupScreen.kt` | Fully Functional | Drive backup discovery, 12-word BIP-39 mnemonic input grid, restore execution, progress indicator. |

---

## 3. Navigation Destination Inventory (`AuthNavGraph.kt`)

The navigation graph contains 31 registered composable destinations in [`AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt):

```
NavHost (startDestination = "splash")
 ├── 1.  "splash"                                                   --> SplashScreen
 ├── 2.  "welcome"                                                  --> WelcomeScreen
 ├── 3.  "profile_setup?..."                                        --> ProfileSetupScreen
 ├── 4.  "verification_radar?..."                                   --> VerificationRadarScreen
 ├── 5.  "otp_verification?..."                                     --> OtpVerificationScreen
 ├── 6.  "profile_badge/{nxId}/{name}/{phone}?..."                  --> ProfileBadgeScreen
 ├── 7.  "permission_gateway"                                       --> PermissionGatewayScreen
 ├── 8.  "dashboard"                                                --> MainChatDashboardScreen
 ├── 9.  "chat/{conversationId}/{participantNxId}/{participantName}"--> ChatScreen
 ├── 10. "qr_display"                                               --> QrDisplayScreen
 ├── 11. "qr_scanner"                                               --> QrScannerScreen
 ├── 12. "contacts"                                                 --> ContactListScreen
 ├── 13. "notification_settings"                                    --> NotificationSettingsScreen
 ├── 14. "profile_image_viewer?imageUri={imageUri}"                 --> ProfileImageViewerScreen
 ├── 15. "search"                                                   --> NexaSearchScreen
 ├── 16. "settings"                                                 --> SettingsScreen
 ├── 17. "account"                                                  --> AccountScreen
 ├── 18. "change_phone_number"                                      --> ChangePhoneNumberScreen
 ├── 19. "security"                                                 --> SecurityScreen
 ├── 20. "account_status"                                           --> AccountStatusScreen
 ├── 21. "account_deactivated"                                      --> AccountDeactivatedScreen
 ├── 22. "account_deletion"                                         --> AccountDeletionFlow
 ├── 23. "appearance"                                              --> AppearanceSettingsScreen
 ├── 24. "contact_profile/{participantNxId}/{conversationId}"       --> ContactProfileScreen
 ├── 25. "contact_details/{participantNxId}/{conversationId}"       --> ContactDetailsScreen
 ├── 26. "media_explorer/{conversationId}/{participantName}"        --> MediaExplorerScreen
 ├── 27. "chat_wallpaper/{conversationId}"                          --> ChatWallpaperScreen
 ├── 28. "pinned_messages/{conversationId}"                         --> PinnedMessagesScreen
 ├── 29. "backup_settings"                                          --> BackupSettingsScreen
 ├── 30. "restore_backup"                                           --> RestoreBackupScreen
 └── 31. "pending_requests"                                         --> PendingRequestsScreen
```

---

## 4. Detailed Audit of Reported Issues

### A. Orphaned Screens Verification
- **`BackupSettingsScreen.kt`:** **RESOLVED / WIRED.** Destination route is `backup_settings`; wired to `SettingsDestination.BACKUP` in `SettingsScreen.kt` (AuthNavGraph.kt:697).
- **`RestoreBackupScreen.kt`:** **RESOLVED / WIRED.** Destination route is `restore_backup`; wired to `onNavigateRestore` in `BackupSettingsScreen.kt` (AuthNavGraph.kt:927).
- **`PendingRequestsScreen.kt`:** **STILL ORPHANED.** Although route `pending_requests` exists in `AuthNavGraph.kt` and `ContactListScreen.kt` accepts `onNavigateToPending: () -> Unit` (line 108), the parameter is **never invoked or bound to any UI component** in `ContactListScreen.kt`. Furthermore, `MainChatDashboardScreen`'s FAB menu option 2 "Connection Requests" navigates to `onOpenContacts()` (which opens `ContactListScreen`) instead of opening pending requests. As a result, users cannot reach this screen.

### B. Empty Navigation Handlers in `SettingsScreen.kt`
Inspected [`AuthNavGraph.kt:692–698`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt#L692-L698):
- **`SettingsDestination.LANGUAGE`:** Confirmed empty block `{}`.
- **`SettingsDestination.ARCHIVED_CHATS`:** Confirmed empty block `{}`.
- **`SettingsDestination.NETWORK`:** Confirmed empty block `{}`.
- **`SettingsDestination.STORAGE_AND_DATA`:** Confirmed empty block `{}`.
- **`SettingsDestination.HELP`:** Confirmed empty block `{}`.
All 5 items produce zero visual feedback or navigation when tapped.

### C. Incorrect Navigation Mappings
Inspected [`AuthNavGraph.kt:686–700`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt#L686-L700):
- **Privacy & Safety:** Mapped to `navController.navigate(Screen.NotificationSettings.route)` (Line 687). User expects privacy settings (typing indicators, read receipts, profile discovery), but lands on notification sound/vibration settings.
- **Devices:** Mapped to `navController.navigate(Screen.Security.route)` (Line 696). User expects linked devices and session revocation, but lands on the Security key view.
- **About:** Mapped to `navController.navigate(Screen.Security.route)` (Line 699). User expects version info, licenses, and architecture overview, but lands on Security.

---

## 5. Functional UI & Interaction Audit

### 5.1 Main Dashboard Screen (`MainChatDashboardScreen.kt`)
- **Top App Bar:**
  - Audio headphones button: Emits temporary snackbar (`"Audio / Radio Mesh is ready"`). Partially functional.
  - Profile photo click: Opens Settings screen. Functional.
  - Notification bell: Opens Notification Settings screen. Functional.
  - QR Code button: Opens QR Display screen. Functional.
  - Logout action: Clears session and navigates to Welcome. Functional.
- **Search Bar:** Clicking search bar opens `NexaSearchScreen`. Search query filtering in-place operates on current tab list. Functional.
- **Stories / Status Row:** Clicking "My Status" or friend stories displays temporary snackbars (`"Create Status Story"`, `"Viewing ...'s story"`). **UI-only placeholder**.
- **Tabs Switcher (Chats vs Groups):** Toggles between direct chats and group chats; updates conversation list correctly. Functional.
- **Conversation List:**
  - Single tap: Opens `ChatScreen`. Functional.
  - Long tap: Opens context menu. Functional.
  - Profile photo tap: Opens full-screen photo viewer. Functional.
- **Selection Toolbar (Multi-Selection Mode):**
  - Pin / Unpin: Updates in-memory set. Functional in-session (resets on process death).
  - Mute / Unmute: Updates in-memory set. Functional in-session.
  - Archive: Adds to `archivedChatIds` filter. Functional in-session.
  - Delete: Opens confirmation dialog, removes from list. Functional in-session.
- **Floating Action Button (`+` Menu):**
  - `Nearby Mesh Discovery`: Calls `onOpenQr()`. **Incorrect destination**.
  - `Connection Requests`: Calls `onOpenContacts()`. **Incorrect destination**.
  - `New Conversation`: Opens modal asking for Recipient NX-ID. Functional.
  - `Contacts`: Opens `ContactListScreen`. Functional.
  - `Create Group`: Opens the 1:1 chat modal. **Placeholder / Misleading**.

### 5.2 Contacts Screen (`ContactListScreen.kt`)
- Top bar back button: Pops backstack. Functional.
- Top bar Search button: Filters list. Functional.
- Top bar Add by Phone: Opens `AddContactByPhoneDialog`. Functional.
- Top bar Add by NX-ID: Opens `AddContactDialog`. Functional.
- Address book observer: Real-time ContentObserver refreshes contacts. Functional.
- Contact row click: Opens direct chat. Functional.
- Contact row nickname edit: Opens `EditNicknameDialog`. Functional.
- **Defect:** No visual element or button triggers `onNavigateToPending`.

### 5.3 Chat Screen (`ChatScreen.kt`)
- Header back button: Pops backstack. Functional.
- Profile header click: Opens `ContactProfileScreen`. Functional.
- Call / Video buttons: Show security toast / warning dialog. Functional.
- Transport selector menu: Toggles between Auto, Internet, Mesh, and SMS. Functional.
- Overflow menu: Opens Wallpaper, Pinned Messages, Media Explorer, Clear Chat. Functional.
- Composer text input & send: Streams messages via WebSocket or Mesh. Functional.
- Attachment menu: Triggers Camera, Gallery, and File pickers. Functional.
- P2P transfer card: Supports live pause, resume, and cancellation. Functional.
- Emoji / Reaction pickers: Populates message composer and reactions. Functional.

---

## 6. UI Quality & Theme Audit

### 6.1 Color Palettes & AMOLED Compliance
- **AMOLED Dark Mode:** Verified strictly uses `NexaPureBlack = Color(0xFF000000)` across all container backgrounds, scaffolds, top bars, and dialog backdrops. Zero dark gray (#121212) leaks.
- **Clean Light Mode:** Verified strictly uses `NexaPureWhite = Color(0xFFFFFFFF)`.
- **Liquid Glass Adaptation:** `NexaGlassSurface` adjusts opacity, surface color, and border highlight dynamically based on `LocalAppearanceMode`:
  - Dark mode: ~80% opacity dark glass (`Color(0xCC121824)`) with 15% subtle white border (`Color(0x26FFFFFF)`).
  - Light mode: ~85% opacity light glass (`Color(0xD9FFFFFF)`) with 12% subtle dark border (`Color(0x1F000000)`).
- **Text Contrast:** Complies with WCAG AA standard across all states. High-contrast typography with monospace identity badges in cyan highlight (`#00E5FF`).

### 6.2 Responsive Layout Verification Across Screen Dimensions
Layouts were analyzed against the 4 target screen dimensions:
1. **360 × 800 (Compact / Budget):**
   - Floating navigation capsule: 136.dp + 16.dp gap + 58.dp FAB = 210.dp total width. Centers with 75.dp margins on each side. Zero horizontal overflow.
   - Quick action menu: `widthIn(min = 230.dp, max = 270.dp)` with 20.dp end margin fits comfortably within 360.dp width.
   - List bottom inset: 110.dp padding ensures bottom items scroll completely above floating controls.
2. **390 × 844 (Standard Modern):**
   - Clean margins and proportions.
3. **412 × 915 (Large Screen / Pixel Pro):**
   - Fluid auto-centering and balanced padding.
4. **430 × 932 (Max Flagship):**
   - Optimal spacing and no stretched touch targets.

---

## 7. Automated Test Execution Results

The existing test suites were executed to verify system integrity:
- **Go Backend Test Suite:** **119/119 PASSING** (`go test -count=1 ./...`). Zero failures.
- **Android Unit Test Files:** **58 test files** covering cryptography, identity, backup, deduplication, transport ingestion, network classification, and navigation.
- **Key Navigation Tests Verified Passing:**
  - `BackupRestoreNavigationUnitTests.kt` (3/3 pass) — Route declarations, Settings backup routing, BIP-39 mnemonic verification.
  - `DemoConversationsAndBottomNavUnitTests.kt` (5/5 pass) — Tab routing, bottom navigation destinations.
  - `SettingsAndAccountLifecycleUnitTests.kt` (13/13 pass) — Exact 13 options of `SettingsDestination` in order, account deactivation, account deletion progressive state machine.
  - `HomeScreenRedesignUnitTests.kt` (8/8 pass) — Group vs chat detection, tab filtering, archive logic.

---

## 8. Prioritized List of Required Fixes

| Priority | Issue Description | Affected Files | Complexity |
| :--- | :--- | :--- | :--- |
| **P0 (Critical)** | **Reconnect Orphaned `PendingRequestsScreen`:** Expose entry point in `ContactListScreen.kt` (top bar icon / badge) and correct `MainChatDashboardScreen.kt` Quick Action FAB routing. | `ContactListScreen.kt`, `MainChatDashboardScreen.kt` | Low |
| **P1 (High)** | **Fix Privacy & Safety Navigation:** Create `PrivacySettingsScreen.kt` exposing existing `ChatPrivacyPrefs` (read receipts, typing indicators, online presence, transport routing) and route `SettingsDestination.PRIVACY_AND_SAFETY` to it. | `AuthNavGraph.kt`, new `PrivacySettingsScreen.kt` | Medium |
| **P1 (High)** | **Fix Devices Navigation:** Create `DevicesScreen.kt` displaying active hardware identity, Keystore backing, and session revocation, and route `SettingsDestination.DEVICES` to it. | `AuthNavGraph.kt`, new `DevicesScreen.kt` | Medium |
| **P1 (High)** | **Fix About Navigation:** Create `AboutScreen.kt` displaying app version, zero-knowledge architecture summary, and open source credits, and route `SettingsDestination.ABOUT` to it. | `AuthNavGraph.kt`, new `AboutScreen.kt` | Low |
| **P2 (Medium)** | **Implement Empty Settings Handlers:** Implement dedicated screens / dialogs for Language, Archived Chats, Network, Storage & Data, and Help. | `AuthNavGraph.kt`, new settings screens | Medium |
| **P2 (Medium)** | **Correct Quick Action Menu Routings:** Route "Nearby Mesh Discovery" to mesh discovery/radar, and implement group creation dialog for "Create Group". | `MainChatDashboardScreen.kt`, `QuickActionMenu.kt` | Medium |
| **P3 (Low)** | **Persist Chat Metadata:** Persist pinned, muted, and archived chat IDs across application restarts using `ConversationPreferences` or database. | `MainChatDashboardScreen.kt`, `ConversationPreferences.kt` | Low |

---

## 9. Conclusion

The audit is **COMPLETE**. All findings have been verified directly against source code and tests. In accordance with Section 9 of the mandate, **no source code has been modified**. Implementation will commence only after explicit user approval of the accompanying Implementation Plan.
