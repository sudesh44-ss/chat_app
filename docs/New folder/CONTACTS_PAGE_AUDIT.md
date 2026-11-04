# NEXA — Contacts Page (Android) Architecture Audit

**Audit Date:** November 4, 2026  
**Auditor:** Senior Android UI/Platform Engineer  
**Scope:** Device Address Book Integration, UI Design & Real-Time Sync  

---

## 1. Feature Specifications & Requirements

The user request specifies a dedicated **Contacts Page** inside the NEXA Android application adhering to the following criteria:

### A. UI Requirements
- **Page Title**: `Contacts`
- **Liquid Glass Theme**: Styled with NEXA dark glassmorphism surfaces (`com.nexa.app.core.theme`).
- **Interactive Search Field**: Real-time filtering of local contacts by name and normalized phone number.
- **Top Add Contact Action**: Modal / action to search and add a contact by phone number.
- **Contact List**: Each item displays:
  - Avatar / initial monogram
  - Contact Display Name (from address book)
  - Normalized Phone Number
  - Availability Pill: `NEXA User` (Verified Green) or `Invite to NEXA` (Neutral)
  - Action Trigger: "Chat" button (opens conversation) or "Invite" button (triggers SMS invite)
- **Lifecycle States**:
  - `Loading`: Progress indicator while reading ContentResolver / performing lookup.
  - `Permission Denied`: Educational card explaining `READ_CONTACTS` necessity with "Grant Permission" and "Open App Settings" actions.
  - `Empty State`: Friendly empty illustration if device address book contains 0 contacts.
  - `Network Unavailable / Error`: Banner preserving local contacts while offering a "Retry Lookup" action.

### B. Permissions Architecture
- **Runtime Permission**: `android.permission.READ_CONTACTS`.
- **Permission Flow**:
  1. Check permission using `ContextCompat.checkSelfPermission()`.
  2. If denied, show educational rationale dialog.
  3. Launch permission request using `rememberLauncherForActivityResult(RequestPermission())`.
  4. If permanently denied, provide intent shortcut to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.
- **Optional Contact Saving**:
  Saving a newly added contact to the phone's address book uses the native Android system Contact Insertion intent (`Intent(Intent.ACTION_INSERT)`), which operates with full user confirmation and does not require dangerous background write privileges.

### C. Real-Time Synchronization via `ContentObserver`
- **Observer Target**: `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`.
- **Lifecycle Management**:
  - Register observer in `onResume()` / `DisposableEffect`.
  - Unregister in `onPause()` / `onDispose()` to guarantee zero memory leaks.
  - Debounce rapid address book modifications (500ms window) before reloading.
  - Automatic refresh when the app returns from background to foreground.

### D. Navigation Integration
- Route: `Screen.Contacts` (`"contacts"`).
- Entry Points:
  - Icon button on `MainChatDashboardScreen` TopAppBar (next to QR scanner).
  - Floating action / direct list navigation.
- Chat Navigation:
  - Selecting a registered NEXA contact resolves their canonical `nx_id` and opens `Screen.Chat.createRoute(conversationId, participantNxId, participantName)`.
