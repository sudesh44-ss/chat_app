# PHASE D — ANDROID IMPLEMENTATION REPORT

## 1. Architecture Overview

On the Android client, Phase D extends NEXA's contact management layer with server-authoritative discovery and relationship lifecycle controls while preserving existing local SQLCipher Room caching.

```
                  ┌───────────────────────────────┐
                  │    ContactViewModel (MVI)     │
                  └──────────────┬────────────────┘
                                 │
                 ┌───────────────┴───────────────┐
                 │    ContactRepositoryImpl      │
                 └───────┬───────────────┬───────┘
                         │               │
        ┌────────────────▼─────┐   ┌─────▼────────────────┐
        │  ConnectionApiService│   │  Room DB (ContactDao)│
        │  (OkHttp / REST API) │   │  (SQLCipher Encrypted│
        └──────────────────────┘   └──────────────────────┘
```

---

## 2. Component Details

### 2.1 Remote DTOs (`ConnectionDto.kt`)
Created `com.nexa.app.data.remote.dto.ConnectionDto`:
- `DiscoveredUserDto`: Holds discovered public profile fields (`nxId`, `displayName`, `avatarUrl`, `bio`, `publicKeyHex`, `isConnected`, `isPending`, `isBlocked`, `createdAt`).
- `ConnectionRequestItemDto`: Holds inbound/outbound request details (`id`, `senderNxId`, `recipientNxId`, `introMessage`, `status`, `createdAt`, `expiresAt`, `profile`).
- `ConnectionItemDto`: Established connection model.
- `BlockedUserItemDto`: Blocked peer profile model.

Strict Privacy Invariant: Neither DTO contains domestic phone numbers, email addresses, or private keys.

---

### 2.2 Remote Service (`ConnectionApiService.kt`)
Created `ConnectionApiService` interface and its default OkHttp implementation:
- `discoverUser(token, nxId)`
- `sendConnectionRequest(token, targetNxId, introMessage)`
- `getIncomingRequests(token, limit, offset)`
- `getOutgoingRequests(token, limit, offset)`
- `acceptRequest(token, requestId)`
- `rejectRequest(token, requestId)`
- `cancelRequest(token, requestId)`
- `getConnections(token, limit, offset)`
- `removeConnection(token, targetNxId)`
- `blockUser(token, targetNxId, reason)`
- `unblockUser(token, targetNxId)`
- `getBlockedUsers(token)`

---

### 2.3 Repository Integration (`ContactRepositoryImpl.kt`)
Updated `ContactRepository` interface and `ContactRepositoryImpl`:
- `discoverUser(nxId)`: Queries `ConnectionApiService.discoverUser` with active session token.
- `sendConnectionRequest(targetNxId, introMessage)`: Dispatches request to backend, inserts provisional `ContactEntity` with `ContactStatus.REQUEST_SENT`.
- `getIncomingConnectionRequests()`: Fetches incoming requests from server and syncs/upserts sender profiles into Room with `ContactStatus.REQUEST_RECEIVED`.
- `acceptConnectionRequestById(requestId, contact)`: Calls backend accept endpoint and updates local Room status to `ContactStatus.MUTUAL_FRIEND`.
- `rejectConnectionRequestById(requestId, nxId)`: Calls backend reject endpoint and deletes local contact row.
- `cancelConnectionRequestById(requestId)`: Calls backend cancel endpoint and clears local pending row.
- `removeConnection(targetNxId)`: Calls backend remove endpoint and removes contact from Room.
- `blockUser(targetNxId)`: Calls backend block endpoint and updates local Room status to `ContactStatus.BLOCKED`.
- `unblockUser(targetNxId)`: Calls backend unblock endpoint and cleans up local contact record.

---

### 2.4 Presentation & ViewModel (`ContactViewModel.kt` & `ContactUiState.kt`)
Extended `ContactUiState`:
- Added `discoveredUser: DiscoveredUserDto?`
- Added `isSearchingUser: Boolean`
- Added `outgoingRequests: List<ConnectionRequestItemDto>`
- Added `incomingRequests: List<ConnectionRequestItemDto>`
- Added `blockedUsers: List<BlockedUserItemDto>`

Added ViewModel actions:
- `searchUserByNxId(query)`: Validates `^NX-[A-Z0-9]{8}$` format and executes debounced profile lookup.
- `clearUserDiscovery()`: Resets discovery search state.
- `sendConnectionRequest(targetNxId, intro)`: Sends request and triggers feedback notification.
- `loadPendingRequests()`: Loads both incoming and outgoing requests asynchronously.
- `acceptConnectionRequest(request)`: Accepts request and moves peer to mutual friends.
- `rejectConnectionRequest(request)`: Rejects request and dismisses.
- `cancelConnectionRequest(request)`: Cancels pending outgoing request.
- `removeConnection(nxId)`: Removes peer relationship.
- `blockUser(nxId)`: Blocks peer and terminates presence.

---

### 2.5 Jetpack Compose UI Components
1. **`AddContactDialog.kt`:**
   - Text input with real-time `^NX-[A-Z0-9]{8}$` formatting validation.
   - Live loading state with debounced lookup.
   - User profile preview card rendering `NexaAvatar`, `displayName`, and `publicKeyHex` snippet.
   - "Send Request" button with optional introduction message text field.
2. **`PendingRequestsScreen.kt`:**
   - TabRow with "Incoming" and "Outgoing" tabs.
   - Incoming tab: Displays sender profile card with Accept (Green) and Reject (Gray) action buttons.
   - Outgoing tab: Displays pending sent invitations with a "Cancel" action button.
   - Empty states for both tabs with clear descriptive icons.
3. **`PeerProfileDialog.kt` & `ContactListScreen.kt`:**
   - Peer action sheet offering "Remove Contact" and "Block User" options.
   - Destructive confirmation prompts before executing remove or block.

---

## 3. Verification & Android Build Validation

- Unit tests in `com.nexa.app.PhaseDConnectionTests`: **100% PASS**
- Kotlin Gradle build (`compileDebugKotlin`, `compileDebugUnitTestKotlin`, `testDebugUnitTest`): **SUCCESSFUL (1m 52s)**.
