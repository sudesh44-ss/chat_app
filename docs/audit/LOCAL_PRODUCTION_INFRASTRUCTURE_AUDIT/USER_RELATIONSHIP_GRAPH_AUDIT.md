# NEXA — User Relationship Graph, Connection Management & Privacy Technical Audit

**Document Reference:** `docs/audit/USER_RELATIONSHIP_GRAPH_AUDIT.md`  
**Audit Date:** 2026-11-09  
**Audit Scope:** Android Client (`app/`), Go Backend (`backend/`), PostgreSQL / Supabase Schema, and Infrastructure Configuration  
**Audit Mode:** Read-Only Source Code & System Architecture Inspection (Phase A)  

---

## 1. Executive Summary

This audit evaluates the complete user relationship, connection management, privacy, contact discovery, and mesh separation subsystems in NEXA.

### Core Architectural Conclusions

1. **Backend Relationship Model Exists in Go:**  
   The Go backend has a dedicated package [`backend/internal/connections/`](file:///H:/chat_app/backend/internal/connections/) backed by PostgreSQL schema `002_create_connections_schema` in [`backend/internal/database/migrations.go`](file:///H:/chat_app/backend/internal/database/migrations.go#L96). It supports directional connection requests (`connection_requests`), canonical mutual connections (`connections`), and bidirectional blocks (`user_blocks`).
2. **Frontend-to-Backend Disconnect in Connection Lifecycle:**  
   While Android has a full UI for contacts and pending requests ([`ContactListScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/ContactListScreen.kt), [`PendingRequestsScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/PendingRequestsScreen.kt)) and an HTTP client ([`ConnectionApiService.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/ConnectionApiService.kt)), the UI currently delegates friend requests and accepts solely to the cryptographic blind mailbox (`HandshakeApi.dispatchHandshake`) rather than invoking the backend's REST connection endpoints (`/api/v1/connections/request`, `/accept`, `/reject`, `/cancel`).
3. **Address-Book Discovery Separation:**  
   Android Contacts Provider queries are strictly local via [`ContactsProviderHelper.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/contacts/ContactsProviderHelper.kt). The address book is never uploaded in bulk. However, batch discovery against `/api/v1/contacts/lookup` is currently bypassed in `ContactRepositoryImpl.loadDeviceContacts()`.
4. **Phone Privacy Leak Risk in Backend Query:**  
   In [`backend/internal/database/repository.go:253`](file:///H:/chat_app/backend/internal/database/repository.go#L253), phone matching uses `(phone_verified = TRUE OR is_discoverable_by_phone = TRUE)` instead of `AND`. This leaks discovery for users who set `is_discoverable_by_phone = FALSE`.
5. **Directional Phone Sharing Grants Missing in Go Schema:**  
   Legacy SQL migration `016_create_phone_visibility_grants.sql` references obsolete `public.profiles(id)` UUIDs and was never ported to Go's `accounts(nx_id)` schema.
6. **Mesh Discovery Decoupled from Social Graph:**  
   [`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt) operates with Google Nearby Connections `Strategy.P2P_CLUSTER` purely as a blind data mule. Physical discovery does not alter the social graph or expose personal contact info.

---

## 2. Existing Database Audit

### 2.1 Active PostgreSQL Tables (`backend/internal/database/migrations.go`)

| Table Name | Primary Key | Foreign Keys | Unique Constraints | Indexes | Status |
| :--- | :--- | :--- | :--- | :--- | :---: |
| **`accounts`** | `id VARCHAR(64)` | None | `nx_id`, `public_key_hex`, `phone_number`, `google_id`, `supabase_user_id` | `idx_accounts_phone`, `idx_accounts_google_id`, `idx_accounts_supabase_uid`, `idx_accounts_pubkey` | **IMPLEMENTED AND WORKING** |
| **`devices`** | `device_id VARCHAR(128)` | `account_id -> accounts(id)`, `nx_id -> accounts(nx_id)` | None | `idx_devices_nx_id`, `idx_devices_account_id` | **IMPLEMENTED AND WORKING** |
| **`connection_requests`** | `id VARCHAR(64)` | `sender_nx_id -> accounts(nx_id)`, `recipient_nx_id -> accounts(nx_id)` | `(sender_nx_id, recipient_nx_id) WHERE status = 'PENDING'` | `idx_conn_req_sender`, `idx_conn_req_recipient`, `idx_conn_req_status`, `idx_conn_req_expires` | **IMPLEMENTED AND WORKING** |
| **`connections`** | `id VARCHAR(64)` | `user_a_nx_id -> accounts(nx_id)`, `user_b_nx_id -> accounts(nx_id)` | `(user_a_nx_id, user_b_nx_id)` (Canonical: `user_a < user_b`) | `idx_connections_user_a`, `idx_connections_user_b`, `idx_connections_status` | **IMPLEMENTED AND WORKING** |
| **`user_blocks`** | `(blocker_nx_id, blocked_nx_id)` | `blocker_nx_id -> accounts(nx_id)`, `blocked_nx_id -> accounts(nx_id)` | Primary Key | `idx_user_blocks_blocker`, `idx_user_blocks_blocked` | **IMPLEMENTED AND WORKING** |
| **`phone_visibility_grants`** | None in Go | None in Go | None in Go | None in Go | **NOT IMPLEMENTED IN GO** |

### 2.2 Directional Requests & Confirmed Connections Analysis
- **Directional Requests (`connection_requests`):**
  - Columns: `id`, `sender_nx_id`, `recipient_nx_id`, `intro_message`, `status`, `created_at`, `updated_at`, `expires_at`.
  - Constraint `chk_no_self_request CHECK (sender_nx_id != recipient_nx_id)`.
  - Partial unique index `idx_unique_pending_request` strictly prevents duplicate pending requests from User A to User B.
  - States supported: `PENDING`, `ACCEPTED`, `REJECTED`, `CANCELLED`, `EXPIRED`.
- **Confirmed Mutual Connections (`connections`):**
  - Canonical ordering constraint: `chk_canonical_users CHECK (user_a_nx_id < user_b_nx_id)`.
  - Uniqueness constraint: `uq_connection_pair UNIQUE (user_a_nx_id, user_b_nx_id)`.
  - Guarantee: Exactly **one canonical record** is stored for any confirmed relationship regardless of which party initiated or accepted the request.
  - Missing field: `connection_source VARCHAR(32)` (e.g., `ADDRESS_BOOK`, `QR_SCAN`, `NEXA_ID`, `MESH_PROXIMITY`).
- **User Blocks (`user_blocks`):**
  - Bidirectional check `IsBlocked(ctx, userA, userB)` prevents interactions if either party blocked the other.
  - Constraint: `chk_no_self_block CHECK (blocker_nx_id != blocked_nx_id)`.

---

## 3. Android Implementation Audit

### 3.1 Contacts Screen & Local Database
- **Presentation Screen:** [`ContactListScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/ContactListScreen.kt).
  - Tabs: "Chats / Friends" (mutual connections), "Contacts" (device address-book contacts).
  - Floating Action Button / Quick Actions: Add Contact by Phone dialog, Add Contact by NX-ID dialog, QR scanner shortcut.
- **Local Room Entity:** [`ContactEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/ContactEntity.kt):
  - Table: `contacts`.
  - Fields: `nxId`, `displayName`, `publicKeyHex`, `introMessage`, `status` (`ContactStatus`: `REQUEST_SENT`, `REQUEST_RECEIVED`, `MUTUAL_FRIEND`, `BLOCKED`, `CONTACT_VERIFIED`), `blePeerId`, `customNickname`, `deviceContactName`, `phoneNumber`, `isPhoneInLocalAddressBook`, `isPhoneSharedByPeer`, `connectionSource`, `avatarLocalPath`, `deviceContactPhotoUri`, `nexaAvatarUrl`.
  - 3-Tier Identity Hierarchy: `customNickname` > `deviceContactName` > `displayName`.
  - Phone Privacy Property: `val shouldShowPhoneNumber: Boolean get() = isPhoneInLocalAddressBook || (isPhoneSharedByPeer && !phoneNumber.isNullOrBlank())`.
- **Local Room DAO:** [`ContactDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/ContactDao.kt):
  - Full reactive Flow support for `getContactsByStatus`, `getAllContacts`, `searchContacts`, `getPendingReceivedCount`.
  - Updates for nickname, device contact name, phone sharing flag, avatar URL, photo URI.

### 3.2 Address-Book Reading & Privacy Audit
- **Android Contacts Provider Helper:** [`ContactsProviderHelper.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/contacts/ContactsProviderHelper.kt#L59):
  - Queries `ContactsContract.CommonDataKinds.Phone.CONTENT_URI` strictly in local IO dispatcher.
  - Reads `DISPLAY_NAME`, `NUMBER`, `PHOTO_URI`.
  - Normalizes numbers using ITU-T E.164 rules (`normalizePhoneNumber`).
  - Strict Zero-Knowledge: Full address-book lists are **never uploaded** to the backend.
- **Contact Matching Disconnect:**
  - In [`ContactRepositoryImpl.kt#L341`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/ContactRepositoryImpl.kt#L341):
    ```kotlin
    override suspend fun loadDeviceContacts(): Result<List<DeviceContact>> = withContext(Dispatchers.IO) {
        val localContacts = ContactsProviderHelper.readDeviceContacts(context)
        Result.success(localContacts)
    }
    ```
    *Flaw:* It reads contacts locally but fails to call `authApiService.lookupContacts(...)` in batch. As a consequence, `isNexaUser` remains `false` for all device contacts, forcing every contact row to render an "Invite" button instead of "Chat".

### 3.3 Connection Requests UI & Disconnect
- **Pending Requests Screen:** [`PendingRequestsScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/PendingRequestsScreen.kt):
  - Tabs: Incoming (`pendingReceived`), Outgoing (`pendingSent`).
  - Action buttons: Accept, Reject, Cancel.
- **The Disconnect:**
  - `onAccept` calls `viewModel.acceptRequest(contact)` → calls `contactRepository.acceptFriendRequest(contact)` (which only derives Double Ratchet keys and dispatches an X3DH envelope to the blind mailbox). It **never calls** `connectionApiService.acceptRequest(...)` (`POST /api/v1/connections/requests/accept`).
  - `onReject` and `Cancel` call `viewModel.rejectContact(nxId)` → which only deletes the contact locally from Room SQLite. It **never calls** `/api/v1/connections/requests/reject` or `/cancel`.
  - Outgoing request sending in `ContactListScreen.kt` calls `viewModel.sendFriendRequest` → which dispatches an envelope to `/api/v1/handshake/dispatch`, but **never calls** `connectionApiService.sendRequest` (`POST /api/v1/connections/request`).
  - *Result:* The server's `connection_requests` and `connections` tables never record client actions.

### 3.4 QR Scanning & Cloud Sync Disconnect
- **Scanner Implementation:** [`QrViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/qr/QrViewModel.kt) + [`QrHandshakeRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/QrHandshakeRepositoryImpl.kt).
  - Validates 15-minute anti-stalking TTL, Ed25519 signature, sets status to `CONTACT_VERIFIED`.
  - Dispatches sync to [`QrSyncApi.syncQrHandshake`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/QrSyncApi.kt#L38) (`POST /api/v1/contacts/sync-qr`).
  - *Result:* The Go backend has no route registered for `/api/v1/contacts/sync-qr`, resulting in a 404 HTTP response that is silently swallowed by the client.

### 3.5 Phone Visibility Controls
- **Account Screen:** User can toggle `is_discoverable_by_phone` on their own profile (`PUT /api/v1/profile`).
- **Contact Profile Screen:** [`ContactProfileViewModel.kt#L74`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/profile/ContactProfileViewModel.kt#L74) reads `contact?.phoneNumber` directly without checking `contact.shouldShowPhoneNumber`.
- **Directional Sharing API:** Neither client nor backend currently implements bilateral phone visibility grant endpoints (`POST /api/v1/users/phone-visibility`).

---

## 4. Go Backend Audit

### 4.1 Endpoints Implemented in `backend/internal/connections/handler.go`

| Endpoint | Method | Repository Method | Verification & Protection | Status |
| :--- | :---: | :--- | :--- | :---: |
| `/api/v1/users/discover` | GET | `SearchUserByNxID` | Rate-limited (30/min), bearer session auth, block check, sanitized public profile | **IMPLEMENTED AND WORKING** |
| `/api/v1/connections/request` | POST | `SendRequest` | Rate-limited (10/min), self-request blocked, block check, simultaneous auto-accept | **IMPLEMENTED AND WORKING** |
| `/api/v1/connections/requests/incoming` | GET | `GetIncomingRequests` | Session auth, pagination, unexpired pending requests | **IMPLEMENTED AND WORKING** |
| `/api/v1/connections/requests/outgoing` | GET | `GetOutgoingRequests` | Session auth, pagination, unexpired pending requests | **IMPLEMENTED AND WORKING** |
| `/api/v1/connections/requests/accept` | POST | `AcceptRequest` | Row-level locking (`FOR UPDATE`), recipient ownership check, block check, canonical insert | **IMPLEMENTED AND WORKING** |
| `/api/v1/connections/requests/reject` | POST | `RejectRequest` | Recipient ownership check, marks `REJECTED` | **IMPLEMENTED AND WORKING** |
| `/api/v1/connections/requests/cancel` | POST | `CancelRequest` | Sender ownership check, marks `CANCELLED` | **IMPLEMENTED AND WORKING** |
| `/api/v1/connections` | GET | `ListConnections` | Session auth, pagination, excludes blocked peers, active filter | **IMPLEMENTED AND WORKING** |
| `/api/v1/connections/remove` | POST | `RemoveConnection` | Session auth, canonical pair update to `REMOVED`, real-time hub notification | **IMPLEMENTED AND WORKING** |
| `/api/v1/users/block` | POST | `BlockUser` | Self-block check, records block, terminates active connection, rejects pending requests | **IMPLEMENTED AND WORKING** |
| `/api/v1/users/unblock` | POST | `UnblockUser` | Removes block record | **IMPLEMENTED AND WORKING** |
| `/api/v1/users/blocked` | GET | `ListBlockedUsers` | Returns public profiles of blocked accounts | **IMPLEMENTED AND WORKING** |
| `/api/v1/contacts/lookup` | POST | `LookupVerifiedPhoneNumbers` | Max 100 numbers, E.164 normalization, verified check | **IMPLEMENTED WITH LOGIC BUG** |
| `/api/v1/contacts/sync-qr` | POST | Missing | Android sends to this route; backend returns 404 | **NOT IMPLEMENTED** |
| `/api/v1/users/phone-visibility` | POST/GET | Missing | Directional phone visibility grants | **NOT IMPLEMENTED** |

### 4.2 Privacy Bug in Phone Lookup Query
In [`backend/internal/database/repository.go:249-254`](file:///H:/chat_app/backend/internal/database/repository.go#L249-L254):
```sql
SELECT phone_number, nx_id, profile_name, public_key_hex
FROM accounts
WHERE phone_number = ANY($1)
  AND (phone_verified = TRUE OR is_discoverable_by_phone = TRUE);
```
**Vulnerability:** The boolean condition uses `OR`. Any user whose phone is verified (`phone_verified = true`) is matched even if they explicitly opted out by setting `is_discoverable_by_phone = false`.  
**Remediation:** Must be `AND phone_verified = TRUE AND is_discoverable_by_phone = TRUE`.

---

## 5. Mesh Network & Social Graph Separation Audit

### 5.1 Architectural Invariants
1. **Physical Discovery != Social Relationship:**  
   [`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt) operates Delay-Tolerant Networking (DTN) using Bluetooth LE / Wi-Fi Direct. When a node discovers an endpoint, it logs the peer and exchanges a 64-byte Bloom filter. It **never** inserts a contact record, never creates a social connection, and never transmits contact books.
2. **Zero Central Server Dependency:**  
   Offline mesh muling executes completely without PostgreSQL or Redis.
3. **Payload Confidentiality:**  
   Envelopes relayed over the mesh are encrypted end-to-end with ChaCha20-Poly1305. Relay nodes cannot inspect sender identity, receiver identity, or message content.

---

## 6. Comprehensive Feature Status Matrix

| Subsystem / Feature | Implementation Status | Evidence / Source Reference |
| :--- | :---: | :--- |
| **Directional Connection Requests Schema** | **IMPLEMENTED AND WORKING** | [`migrations.go:99`](file:///H:/chat_app/backend/internal/database/migrations.go#L99) `connection_requests` table |
| **Canonical Bilateral Connections Schema** | **IMPLEMENTED AND WORKING** | [`migrations.go:118`](file:///H:/chat_app/backend/internal/database/migrations.go#L118) `connections` table (`user_a < user_b`) |
| **User Blocks Schema** | **IMPLEMENTED AND WORKING** | [`migrations.go:133`](file:///H:/chat_app/backend/internal/database/migrations.go#L133) `user_blocks` table |
| **Backend Connection REST Endpoints** | **IMPLEMENTED AND WORKING** | [`connections/handler.go:50-63`](file:///H:/chat_app/backend/internal/connections/handler.go#L50-L63) 12 registered routes |
| **Concurrent Request Auto-Acceptance** | **IMPLEMENTED AND WORKING** | [`connections/postgres_repository.go:141`](file:///H:/chat_app/backend/internal/connections/postgres_repository.go#L141) simultaneous request detection |
| **Backend Contact Lookup Route** | **PARTIALLY IMPLEMENTED** | [`auth/handler.go:708`](file:///H:/chat_app/backend/internal/auth/handler.go#L708) (has boolean `OR` privacy bug in `repository.go:253`) |
| **QR Handshake Backend Sync Route** | **NOT IMPLEMENTED** | `POST /api/v1/contacts/sync-qr` missing on Go backend |
| **Directional Phone Visibility Grants** | **NOT IMPLEMENTED** | Table & APIs missing in Go backend |
| **Android Address Book Local Provider** | **IMPLEMENTED AND WORKING** | [`ContactsProviderHelper.kt#L59`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/contacts/ContactsProviderHelper.kt#L59) strictly on-device |
| **Android Contact Batch Matching** | **IMPLEMENTED BUT NOT CONNECTED** | [`ContactRepositoryImpl.kt#L341`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/ContactRepositoryImpl.kt#L341) misses `/api/v1/contacts/lookup` |
| **Android Pending Requests REST Sync** | **IMPLEMENTED BUT NOT CONNECTED** | [`PendingRequestsScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/PendingRequestsScreen.kt) does not call REST endpoints |
| **Android Connection REST Dispatch** | **IMPLEMENTED BUT NOT CONNECTED** | [`ConnectionApiService.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/ConnectionApiService.kt) exists but uncalled by `sendFriendRequest` |
| **Android Phone Privacy Gate on UI** | **PARTIALLY IMPLEMENTED** | [`ContactEntity.shouldShowPhoneNumber`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/ContactEntity.kt#L72) exists but ignored by `ContactProfileViewModel` |
| **Nearby Mesh Social Decoupling** | **IMPLEMENTED AND WORKING** | [`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt) blind transport |

---

## 7. Implementation Plan (Phases B through G)

To achieve 100% functionality and compliance with the specification:

1. **Phase B & D (Backend Database & API Extensions):**
   - Add Migration 4 in `backend/internal/database/migrations.go`:
     - Add `source VARCHAR(32) NOT NULL DEFAULT 'NEXA_ID'` to `connections` table.
     - Add `phone_visibility_grants` table (`granter_nx_id`, `grantee_nx_id`, `is_active`, `created_at`, `updated_at`).
   - Fix privacy bug in `backend/internal/database/repository.go`: enforce `phone_verified = TRUE AND is_discoverable_by_phone = TRUE`.
   - Implement `POST /api/v1/contacts/sync-qr` handler in Go backend to record instant QR-verified mutual connections.
   - Implement `POST /api/v1/users/phone-visibility` and `GET /api/v1/users/phone-visibility` in Go backend.
   - Implement mutual connection calculation (`ListMutualConnections`) in Go backend.
2. **Phase C (Contact Discovery & Privacy):**
   - In `ContactRepositoryImpl.loadDeviceContacts()`, perform batch phone matching via `authApiService.lookupContacts` so registered NEXA users are identified with `isNexaUser = true`.
3. **Phase G (Frontend Integration):**
   - Wire `ContactRepositoryImpl.sendFriendRequest` to call `connectionApiService.sendRequest` in addition to the encrypted mailbox envelope.
   - Wire `acceptFriendRequest` in `ContactRepositoryImpl` to call `connectionApiService.acceptRequest`.
   - Wire `rejectOrBlockContact` in `ContactRepositoryImpl` to call `connectionApiService.rejectRequest`, `cancelRequest`, or `blockUser`.
   - Wire `ContactProfileViewModel` to respect `contact.shouldShowPhoneNumber`.
   - Wire `syncPendingRequests` to poll both incoming and outgoing requests from `connectionApiService`.
4. **Phase H (Automated Tests):**
   - Author comprehensive Go backend and Android client tests covering all 28 test requirements.
