# NEXA — Directed User Relationship Graph: Architecture & Implementation Audit

**Document Reference:** `docs/audit/DIRECTED_RELATIONSHIP_GRAPH_AUDIT.md`  
**Execution Date:** November 9, 2026  
**Project:** NEXA Messaging Application (Android + Go Backend)  
**Workspace:** `H:\chat_app`  
**Audit Scope:** Go Backend (`backend/`), PostgreSQL Database Schema, Upstash Redis, Android Room Database (`app/`), Transport Layer, and UI Integration  
**Audit Mode:** Phase 1 — Strict Read-Only Architecture Audit  

---

## 1. Executive Summary

This technical audit investigates the architecture and data structures of NEXA to evaluate the implementation of a **Directed User Relationship Graph**. 

### The Core Requirement
The relationship model in NEXA must preserve two orthogonal concepts:
1. **Request Direction:** Who initiated the connection request ($A \dashrightarrow B$). Once accepted, the origin must remain permanently identifiable ($A \longrightarrow B$).
2. **Confirmed Relationship:** Whether communication is authorized. Both parties can exchange encrypted messages after acceptance (subject to blocking and security policies).
3. **Mutual Relationship:** An independently established bilateral relationship ($A \longleftrightarrow B$). This state occurs **only** when User B independently initiates the reverse connection to User A, and **not** merely by accepting User A's initial request.
4. **Independent Permissions:** Phone-number visibility, online presence, and mesh relay capabilities are distinct permissions that are never automatically inherited from either a single-headed or double-headed relationship arrow.

### Summary of Audit Findings
1. **Database Schema Capabilities:**
   - The Go backend database currently tracks requests via `connection_requests` and established connections via `connections`.
   - `connection_requests` records `sender_nx_id` and `recipient_nx_id`, preserving request direction during the pending phase.
   - However, when a request is accepted, `connections` stores only the lexicographically canonical pair `(user_a_nx_id < user_b_nx_id)`. Crucially, `connections` **lacks an `initiator_nx_id` column** and **lacks mutual relationship tracking** (`has_reciprocal_relationship`).
   - In `postgres_repository.go:CreateRequest()`, if an active connection already exists, any subsequent attempt by User B to independently establish the reverse relationship is rejected with `ErrAlreadyConnected`.
   - In `postgres_repository.go:AcceptRequest()`, line 359 automatically sets any pending reciprocal request from B to A to `ACCEPTED`, conflating an accepted incoming request with an independently established mutual relationship.
2. **Android Room Model Capabilities:**
   - The Android client stores relationships in the SQLCipher `contacts` table via [`ContactEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/ContactEntity.kt).
   - Currently, `ContactStatus` has five states: `REQUEST_SENT`, `REQUEST_RECEIVED`, `MUTUAL_FRIEND`, `BLOCKED`, and `CONTACT_VERIFIED`.
   - Upon request acceptance, the client immediately updates the status to `MUTUAL_FRIEND`, losing the distinction between `OUTBOUND_CONFIRMED` ($A \longrightarrow B$), `INBOUND_CONFIRMED` ($B \longleftarrow A$), and true independently established `MUTUAL` ($A \longleftrightarrow B$).
3. **Messaging & Transport Authorization:**
   - Direct messaging over WebSocket is guarded by `ConnectionValidator.IsConnected` and `IsBlocked` in [`hub.go`](file:///H:/chat_app/backend/internal/connection/hub.go).
   - Once a connection is active, communication is authorized symmetrically. Blocking is enforced symmetrically in both directions.
   - Offline mesh routing in [`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt) and [`DtnRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/dtn/DtnRepositoryImpl.kt) is decoupled from the social graph. Data mules forward sealed DTN envelopes without social graph checks and cannot decrypt E2EE payloads.

---

## 2. Backend Database Architecture Audit

### 2.1 Existing Tables & Relationships (`backend/internal/database/migrations.go`)

| Table Name | Primary Key | Foreign Keys | Unique / Check Constraints | Existing Indexes | Current Role |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`accounts`** | `id VARCHAR(64)` | None | `nx_id UNIQUE`, `public_key_hex UNIQUE` | `idx_accounts_phone`, `idx_accounts_pubkey` | User account identity root |
| **`devices`** | `device_id VARCHAR(128)` | `account_id -> accounts(id)`, `nx_id -> accounts(nx_id)` | None | `idx_devices_nx_id`, `idx_devices_account_id` | Physical device registration & session anchor |
| **`connection_requests`** | `id VARCHAR(64)` | `sender_nx_id -> accounts(nx_id)`, `recipient_nx_id -> accounts(nx_id)` | `chk_no_self_request (sender != recipient)` | `idx_unique_pending_request (sender, recipient) WHERE status='PENDING'`, `idx_conn_req_sender`, `idx_conn_req_recipient`, `idx_conn_req_status`, `idx_conn_req_expires` | Asymmetric invitation queue |
| **`connections`** | `id VARCHAR(64)` | `user_a_nx_id -> accounts(nx_id)`, `user_b_nx_id -> accounts(nx_id)` | `chk_canonical_users (user_a < user_b)`, `uq_connection_pair (user_a, user_b)` | `idx_connections_user_a`, `idx_connections_user_b`, `idx_connections_status` | Established communication link |
| **`user_blocks`** | `(blocker_nx_id, blocked_nx_id)` | `blocker_nx_id -> accounts(nx_id)`, `blocked_nx_id -> accounts(nx_id)` | `chk_no_self_block (blocker != blocked)` | `idx_user_blocks_blocker`, `idx_user_blocks_blocked` | Bidirectional message suppression |
| **`phone_visibility_grants`** | `id VARCHAR(64)` | `granter_nx_id -> accounts(nx_id)`, `grantee_nx_id -> accounts(nx_id)` | `uq_phone_grant (granter, grantee)`, `chk_different_parties (granter != grantee)` | `idx_phone_grants_granter`, `idx_phone_grants_grantee` | Explicit directional phone sharing |

### 2.2 Lifecycle & Invariant Analysis

#### 1. How Connection Requests Are Created
- Invoked via `POST /api/v1/connections/request` handled by `Handler.handleSendRequest()` and `PostgresRepository.CreateRequest()`.
- Validates:
  1. Sender session is authenticated and device is active.
  2. Sender $\ne$ Recipient (`ErrSelfRequest`).
  3. Neither party has blocked the other (`IsBlocked`).
  4. Recipient exists in `accounts`.
- **Existing Limitation:**
  In `postgres_repository.go:121-130`:
  ```go
  uA, uB := CanonicalPair(senderNxID, recipientNxID)
  var alreadyConnected bool
  connSQL := `SELECT EXISTS(SELECT 1 FROM connections WHERE user_a_nx_id = $1 AND user_b_nx_id = $2 AND status = 'ACTIVE')`
  if err := tx.QueryRow(ctx, connSQL, uA, uB).Scan(&alreadyConnected); err != nil {
      return nil, err
  }
  if alreadyConnected {
      return nil, ErrAlreadyConnected
  }
  ```
  If User A sent a request to User B and B accepted it, an `ACTIVE` row already exists in `connections`. If User B now wishes to independently initiate the reciprocal connection to User A, the call fails immediately with `ErrAlreadyConnected`.

#### 2. How Incoming Requests Are Accepted or Rejected
- **Acceptance:** Invoked via `POST /api/v1/connections/requests/accept` with `request_id`.
  - Transaction locks the request row `FOR UPDATE`.
  - Verifies authenticated caller is `recipient_nx_id` and status is `PENDING`.
  - Checks if request has expired (`expires_at <= now`).
  - Checks bidirectional block status.
  - Updates request status to `ACCEPTED`.
  - Inserts/updates `connections` record with `CanonicalPair(sender, recipient)`.
- **Existing Limitation:**
  In `postgres_repository.go:358-360`:
  ```go
  // Mark any reciprocal requests accepted
  _, _ = tx.Exec(ctx, "UPDATE connection_requests SET status = 'ACCEPTED', updated_at = $3 WHERE sender_nx_id = $1 AND recipient_nx_id = $2 AND status = 'PENDING'",
      req.RecipientNxID, req.SenderNxID, now)
  ```
  This automatically accepts any unaccepted pending reciprocal request from B to A.

#### 3. How Request Direction Is Preserved
- In `connection_requests`: Request direction is preserved as `sender_nx_id` and `recipient_nx_id`.
- In `connections`: **Request direction is currently lost**. Because `user_a_nx_id` is always alphabetically less than `user_b_nx_id`, the row does not identify who initiated the connection.

#### 4. How Confirmed Connections Are Stored
- Stored as a single canonical row in `connections` where `user_a_nx_id < user_b_nx_id`.
- Duplicate connections between the same pair are prevented by `CONSTRAINT uq_connection_pair UNIQUE (user_a_nx_id, user_b_nx_id)`.

#### 5. Bidirectional Blocking Verification
- Handled by `user_blocks` table with composite primary key `(blocker_nx_id, blocked_nx_id)`.
- `IsBlocked(userA, userB)` executes:
  ```sql
  SELECT EXISTS(
      SELECT 1 FROM user_blocks 
      WHERE (blocker_nx_id = $1 AND blocked_nx_id = $2) 
         OR (blocker_nx_id = $2 AND blocked_nx_id = $1)
  );
  ```
  This guarantees that blocking operates symmetrically in both directions.
- When `BlockUser` is executed:
  1. Block record is inserted.
  2. Any active connection in `connections` is set to `REMOVED`.
  3. Any pending requests in `connection_requests` between them are set to `REJECTED`.
- When `UnblockUser` is executed:
  - Deletes row from `user_blocks`.
  - **Does NOT** resurrect the previously removed connection. Communication remains disabled until a new request is explicitly initiated and accepted.

#### 6. Can Mutual Relationships Be Derived Reliably?
- **Currently: NO.** Because:
  1. `connections` only indicates that an active connection exists between `{A, B}`.
  2. `connections` does not store the original initiator.
  3. Reciprocal requests are blocked if a connection is already active, preventing User B from establishing an independent reverse relationship.
  4. Auto-accept in `AcceptRequest` marks reciprocal pending requests as accepted without explicit user intent.

---

## 3. Android Room Database Audit

### 3.1 Existing Room Entities & DAOs

#### `ContactEntity.kt` ([`ContactEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/ContactEntity.kt))
- Table: `contacts`
- Primary Key: `nxId`
- Current Fields:
  ```kotlin
  @Entity(tableName = "contacts", indices = [Index(value = ["nxId"], unique = true)])
  data class ContactEntity(
      @PrimaryKey val nxId: String,
      val displayName: String,
      val publicKeyHex: String,
      val introMessage: String? = null,
      val status: ContactStatus = ContactStatus.MUTUAL_FRIEND,
      val blePeerId: String? = null,
      val createdAtTimestamp: Long = System.currentTimeMillis(),
      val customNickname: String? = null,
      val deviceContactName: String? = null,
      val phoneNumber: String? = null,
      val isPhoneInLocalAddressBook: Boolean = false,
      val isPhoneSharedByPeer: Boolean = false,
      val connectionSource: String = "ADDRESS_BOOK",
      val avatarLocalPath: String? = null,
      val deviceContactPhotoUri: String? = null,
      val nexaAvatarUrl: String? = null
  )
  ```
- Current `ContactStatus` Enum:
  - `REQUEST_SENT` — Outbound pending request.
  - `REQUEST_RECEIVED` — Inbound pending request.
  - `MUTUAL_FRIEND` — Both outbound confirmed and inbound confirmed are conflated here.
  - `BLOCKED` — User is blocked locally.
  - `CONTACT_VERIFIED` — Day-0 QR code instant verification.

#### Room Client Distinguishability Matrix

| State Required by Spec | Can Current Client Distinguish? | Current Implementation State |
| :--- | :---: | :--- |
| **OUTBOUND_PENDING** | **YES** | Represented as `ContactStatus.REQUEST_SENT`. |
| **INBOUND_PENDING** | **YES** | Represented as `ContactStatus.REQUEST_RECEIVED`. |
| **OUTBOUND_CONFIRMED** ($A \longrightarrow B$) | **NO** | Conflated with `MUTUAL_FRIEND`. Initiator identity is lost. |
| **INBOUND_CONFIRMED** ($B \longleftarrow A$) | **NO** | Conflated with `MUTUAL_FRIEND`. Target acceptance is lost. |
| **MUTUAL** ($A \longleftrightarrow B$) | **NO** | Conflated with `MUTUAL_FRIEND`. Independent reverse edge cannot be proven. |
| **BLOCKED** | **YES** | Represented as `ContactStatus.BLOCKED`. |
| **REVOKED** | **NO** | Record is simply deleted from Room via `contactDao.deleteContact(nxId)`. |

### 3.2 Contact & Phone Visibility Logic
- Enforced on device via `ContactEntity.shouldShowPhoneNumber`:
  ```kotlin
  val shouldShowPhoneNumber: Boolean
      get() = isPhoneInLocalAddressBook || (isPhoneSharedByPeer && !phoneNumber.isNullOrBlank())
  ```
- In `ContactProfileViewModel.kt`:
  ```kotlin
  val phone = if (isSelf) {
      myProfile?.phoneNumber
  } else if (contact?.shouldShowPhoneNumber == true) {
      contact.phoneNumber
  } else {
      null
  }
  ```
- This satisfies Phase 4:
  - Phone number is visible **only** if the peer explicitly shared it (`isPhoneSharedByPeer`) or if already present in the user's local Android address book (`isPhoneInLocalAddressBook`).
  - Connecting does not automatically grant phone visibility.
  - Senders' phone numbers are not disclosed by default upon sending a connection request.

---

## 4. Messaging & Transport Authorization Audit

### 4.1 WebSocket Direct Messaging (`backend/internal/connection/hub.go`)
- Before routing any `PACKET_MESSAGE` or `PACKET_TYPING`, the Hub executes:
  1. **Blocklist Check:**
     ```go
     blocked, err := h.connValidator.IsBlocked(ctx, sender.NxId, packet.RecipientNxId)
     if err == nil && blocked {
         log.Printf("[Hub] Wire Policy: Packet %s dropped (communication blocked)", packet.PacketId)
         return
     }
     ```
  2. **Connection Check:**
     ```go
     connected, err := h.connValidator.IsConnected(ctx, sender.NxId, packet.RecipientNxId)
     if err != nil || !connected {
         log.Printf("[Hub] Wire Policy: Packet %s dropped (not actively connected)", packet.PacketId)
         return
     }
     ```
- **Finding:** Senders cannot message users unless a confirmed relationship exists and neither party has blocked the other.
- **Directional Behavior:** Once a relationship is confirmed ($A \longrightarrow B$), communication is authorized for both parties. This complies with Phase 2: *"Both users may communicate after acceptance, subject to blocking and other security policies."*

### 4.2 Offline Mesh & Multi-Hop DTN Forwarding
- **Component:** [`NexaUniversalTransportIngest.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/transport/NexaUniversalTransportIngest.kt), [`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt), [`DtnRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/dtn/DtnRepositoryImpl.kt).
- **Physical Routing vs Social Graph:**
  - Physical radio links (BLE / Wi-Fi Direct / Nearby Connections `Strategy.P2P_CLUSTER`) operate autonomously.
  - DTN envelopes use 16-byte Blind Tokens derived from recipient public keys. Relay nodes do not know sender NX-ID or recipient NX-ID.
  - Relay nodes cannot decrypt payloads (payload is end-to-end encrypted with recipient's Double Ratchet / X3DH keys).
  - Envelopes enforce hop limits (decremented at each hop; dropped when 0) and TTL expiry epochs.
- **Unauthorized Direct Message Prevention:**
  - An unauthorized user cannot exploit a relay node to bypass Double Ratchet encryption.
  - If an unconnected sender crafts a DTN packet to a recipient, the recipient's ingest pipeline decrypts using Double Ratchet; if no bilateral session exists, decryption fails with Poly1305 MAC failure and the message is marked failed.

---

## 5. Security & Privacy Audit Findings

1. **Zero-Knowledge Contacts Preservation:**  
   The user's address book is never uploaded in bulk to the backend. Matching is performed locally or via batch phone lookups returning public profiles for verified users who have opted into discoverability.
2. **E2EE Integrity:**  
   The server never decrypts wire packets. Double Ratchet sessions, signed prekeys, and one-time prekeys are maintained exclusively on client devices.
3. **Impersonation Prevention:**  
   Anti-impersonation is enforced through the 3-tier identity hierarchy (`customNickname` > `deviceContactName` > `displayName`).
4. **Canonical Invariant Vulnerability:**  
   Currently, `connections` relies on `chk_canonical_users CHECK (user_a_nx_id < user_b_nx_id)`. While canonical pairs prevent duplicate rows, without an explicit `initiator_nx_id` and directional flags, the graph collapses directional knowledge once a request is accepted.

---

## 6. Implementation Gap & Component Status Matrix

| Component | Subsystem | Existing Status | Gaps to Address in Phases 2–9 |
| :--- | :--- | :--- | :--- |
| `connection_requests` table | PostgreSQL | **IMPLEMENTED** | Schema works; auto-accept in repository needs removal. |
| `connections` table | PostgreSQL | **PARTIALLY IMPLEMENTED** | Lacks `initiator_nx_id` and `is_mutual` / `reciprocal_confirmed_at`. |
| `user_blocks` table | PostgreSQL | **VERIFIED IMPLEMENTED** | Symmetric block enforcement complete. |
| `phone_visibility_grants` | PostgreSQL | **VERIFIED IMPLEMENTED** | Directional phone sharing operational. |
| Go Connection Repository | Go Backend | **PARTIALLY IMPLEMENTED** | `CreateRequest` prevents reverse request; `AcceptRequest` auto-accepts reciprocal; `CanonicalPair` drops initiator. |
| Go Connection Handlers | Go Backend | **PARTIALLY IMPLEMENTED** | REST responses do not expose direction or mutual state to clients. |
| `ContactEntity` | Android Room | **PARTIALLY IMPLEMENTED** | `ContactStatus` lacks `OUTBOUND_CONFIRMED`, `INBOUND_CONFIRMED`, `MUTUAL`, `REVOKED`. |
| `ContactDao` | Android Room | **PARTIALLY IMPLEMENTED** | Needs queries for directed relationship states. |
| `ContactRepositoryImpl` | Android Client | **PARTIALLY IMPLEMENTED** | Conflates accepted requests with `MUTUAL_FRIEND`; needs directed state resolution. |
| `ContactViewModel` | Android UI | **PARTIALLY IMPLEMENTED** | Needs to expose directed status ($A \longrightarrow B$ vs $A \longleftrightarrow B$) to UI state without altering layout. |
| Transport Ingestion | Android Core | **VERIFIED IMPLEMENTED** | Multi-transport ingest pipeline operational across WS, SMS, Mesh. |
| Mesh Routing Separation | Android Core | **VERIFIED IMPLEMENTED** | Social graph decoupled from DTN packet forwarding. |

---

## 7. Files Requiring Modification (Phases 2–9)

### 7.1 Backend (Go + PostgreSQL)
1. [`backend/internal/database/migrations.go`](file:///H:/chat_app/backend/internal/database/migrations.go):
   - Add Migration `005_add_directed_relationship_fields`:
     - `ALTER TABLE connections ADD COLUMN IF NOT EXISTS initiator_nx_id VARCHAR(32) REFERENCES accounts(nx_id) ON DELETE CASCADE;`
     - `ALTER TABLE connections ADD COLUMN IF NOT EXISTS is_mutual BOOLEAN NOT NULL DEFAULT FALSE;`
     - `ALTER TABLE connections ADD COLUMN IF NOT EXISTS mutual_established_at BIGINT;`
     - Create index on `(initiator_nx_id)` and update existing rows from `connection_requests`.
2. [`backend/internal/connections/model.go`](file:///H:/chat_app/backend/internal/connections/model.go):
   - Add `InitiatorNxID`, `IsMutual`, `MutualEstablishedAt` to `Connection`.
   - Add `RelationshipDirection` (`OUTBOUND`, `INBOUND`, `MUTUAL`, `PENDING_OUTBOUND`, `PENDING_INBOUND`, `NONE`) and `IsMutual` to `PublicProfile` and connection DTOs.
3. [`backend/internal/connections/postgres_repository.go`](file:///H:/chat_app/backend/internal/connections/postgres_repository.go):
   - In `CreateRequest`: Allow User B to send request to User A when an active single-headed connection ($A \longrightarrow B$) exists, establishing an independent reverse request ($B \dashrightarrow A$).
   - In `AcceptRequest`: Populate `initiator_nx_id`; if B accepts A's reverse request on an existing connection, transition `is_mutual = TRUE`. Remove unilateral auto-accept of reciprocal requests.
   - In `ListConnections`: Return directed status relative to authenticated user.
4. [`backend/internal/connections/memory_repository.go`](file:///H:/chat_app/backend/internal/connections/memory_repository.go):
   - Mirror database changes in test in-memory repository.
5. [`backend/internal/connections/handler.go`](file:///H:/chat_app/backend/internal/connections/handler.go):
   - Expose relationship direction and mutual state in `/api/v1/connections` and connection responses.
6. [`backend/internal/connections/connections_test.go`](file:///H:/chat_app/backend/internal/connections/connections_test.go):
   - Add automated test coverage for request direction preservation, independent reverse relationship, and mutual state calculation.

### 7.2 Android Client
1. [`app/src/main/java/com/nexa/app/data/local/entity/ContactEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/ContactEntity.kt):
   - Expand `ContactStatus` or add `DirectedRelationshipState` (`OUTBOUND_PENDING`, `INBOUND_PENDING`, `OUTBOUND_CONFIRMED`, `INBOUND_CONFIRMED`, `MUTUAL`, `BLOCKED`, `REVOKED`).
   - Add `initiatorNxId: String? = null` and `isMutual: Boolean = false`.
2. [`app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt):
   - Add Room migration `MIGRATION_18_19` to add `initiatorNxId` and `isMutual` columns to `contacts`.
3. [`app/src/main/java/com/nexa/app/data/local/dao/ContactDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/ContactDao.kt):
   - Add queries supporting directed relationship resolution.
4. [`app/src/main/java/com/nexa/app/data/remote/dto/ConnectionDtos.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dto/ConnectionDtos.kt):
   - Add `initiatorNxId`, `isMutual`, and `relationshipDirection` to `ConnectionItemDto` and `DiscoveredUserDto`.
5. [`app/src/main/java/com/nexa/app/data/repository/ContactRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/ContactRepositoryImpl.kt):
   - Resolve directed states on sync and connection acceptance.
6. [`app/src/test/java/com/nexa/app/data/repository/DirectedRelationshipGraphUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/data/repository/DirectedRelationshipGraphUnitTests.kt):
   - Add comprehensive Android unit tests covering directional states, phone privacy isolation, and mesh separation.

---

## 8. Verification Plan & Safety Guarantees

1. **Database Schema Safety:**
   - Migration 005 uses idempotent `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`.
   - Backward-compatible with existing `CanonicalPair` queries so WebSocket authorization (`IsConnected`) continues to function without schema breaking changes.
2. **Zero Plaintext Server Leakage:**
   - No E2EE keys or message plaintext will be exposed.
3. **UI Preservation:**
   - Existing Jetpack Compose screens, layouts, and Liquid Glass styling remain unchanged. Directed status is rendered within existing profile badges and contact item metadata.
4. **Automated Verification:**
   - Backend: `go test -v ./internal/connections/...`
   - Android: `.\gradlew testDebugUnitTest`
