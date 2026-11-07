# PHASE D — CONNECTION SYSTEM AUDIT & ARCHITECTURE SPECIFICATION

## 1. Executive Summary

Phase D implements the production connection and contact management subsystem for NEXA. Prior to Phase D, NEXA lacked an authenticated, server-authoritative contact discovery and lifecycle management engine: user relationships were managed either through local peer mocks or ad-hoc cryptographic handshakes without bidirectional persistence.

Under Phase D:
1. **User Discovery by NX-ID:** Users can discover other registered NEXA accounts exclusively via exact canonical NX-ID (`^NX-[A-Z0-9]{8}$`).
2. **Strict Public Profile Boundary:** The system exposes only non-sensitive public metadata (`nxId`, `displayName`, `avatarUrl`, `bio`, `publicKeyHex`, and mutual connection status). Phone numbers, email addresses, device tokens, and private cryptographic key materials are strictly non-discoverable and non-enumerable.
3. **Bilateral Connection Lifecycle:** Connection states transition through a well-defined state machine: `NONE` → `PENDING` → `CONNECTED` / `REJECTED` / `CANCELLED` / `REMOVED`.
4. **Canonical Pair Ordering:** Database persistence enforces `user_a_nx_id < user_b_nx_id` to guarantee that duplicate connection records between two users are structurally impossible.
5. **Reciprocal Request Resolution:** Simultaneous requests between two users auto-resolve into an active mutual connection atomically.
6. **Robust Blocklist Enforcements:** Blocking a user immediately terminates any existing active connection, auto-rejects pending requests, conceals profile lookup with an anti-enumeration 404, and rejects inbound request attempts with 403 Forbidden.
7. **Real-Time Notification Pipeline:** In-flight connection events trigger real-time WebSocket notifications to connected peers while maintaining REST as the authoritative, paginated source of truth.

---

## 2. Source Code Architecture & Modified Components

### Go Backend Components
- **`backend/internal/database/migrations.go` (Migration 002):**
  - Defines and executes database migration `002_create_connections_schema`.
  - Tables: `connection_requests`, `connections`, `user_blocks`.
  - Check constraints: `chk_no_self_request`, `chk_canonical_users`, `chk_no_self_block`.
- **`backend/internal/connections/model.go`:**
  - Domain models: `ConnectionRequest`, `Connection`, `UserBlock`, `DiscoveredUser`.
  - Canonical pair ordering helper: `CanonicalPair(user1, user2 string) (string, string)`.
  - Status constants: `RequestStatusPending`, `RequestStatusAccepted`, `RequestStatusRejected`, `RequestStatusCancelled`, `RequestStatusExpired`, `ConnectionStatusActive`, `ConnectionStatusRemoved`.
- **`backend/internal/connections/repository.go`:**
  - `ConnectionRepository` interface specifying contracts for discovery, request lifecycles, connection querying/removal, and blocklist operations.
- **`backend/internal/connections/postgres_repository.go`:**
  - Production PostgreSQL implementation using `pgxpool.Pool`.
  - Implements atomic transactions, reciprocal request detection and resolution, connection pairing, and block enforcement.
- **`backend/internal/connections/memory_repository.go`:**
  - In-memory implementation used for local fallback and high-speed unit testing.
- **`backend/internal/connections/handler.go`:**
  - HTTP REST API handler enforcing Bearer token session authentication and verified device status.
  - Rate limiting: sliding window limit (10 discovery requests/min, 20 connection requests/hour).
  - Anti-enumeration: returns identical 404 responses for nonexistent users and blocked accounts.
- **`backend/internal/connection/hub.go`:**
  - Extended with `BroadcastEventToNxID(nxId string, eventType string, payload any)` to push live connection events (`connection:request_received`, `connection:accepted`, `connection:rejected`, `connection:removed`, `user:blocked`) over active WebSockets.
- **`backend/cmd/server/main.go`:**
  - Initializes `connections.NewPostgresRepository` (with fallback to `NewMemoryRepository`) and registers routes under `/api/v1/`.

### Android Client Components
- **`app/src/main/java/com/nexa/app/data/remote/dto/ConnectionDto.kt`:**
  - DTOs for discovered profiles, requests, connections, and blocked peers.
- **`app/src/main/java/com/nexa/app/data/remote/api/ConnectionApiService.kt`:**
  - OkHttp client and REST interface for all Phase D endpoints.
- **`app/src/main/java/com/nexa/app/domain/repository/ContactRepository.kt`:**
  - Domain interface updated with Phase D connection methods.
- **`app/src/main/java/com/nexa/app/data/repository/ContactRepositoryImpl.kt`:**
  - Integrates `ConnectionApiService` with local Room `ContactDao` and `SecureStorage`.
- **`app/src/main/java/com/nexa/app/presentation/contacts/ContactUiState.kt` & `ContactViewModel.kt`:**
  - UI state and view model handlers for discovery searching, sending requests, accepting/rejecting requests, and blocking/removing peers.
- **`app/src/main/java/com/nexa/app/presentation/contacts/components/AddContactDialog.kt`:**
  - Dialog with NX-ID validation, live profile preview, and send request button.
- **`app/src/main/java/com/nexa/app/presentation/contacts/PendingRequestsScreen.kt`:**
  - Tabbed interface separating Incoming (Accept/Reject) and Outgoing (Cancel) requests.
- **`app/src/main/java/com/nexa/app/presentation/contacts/components/PeerProfileDialog.kt`:**
  - Profile dialog providing Remove Connection and Block User actions.

---

## 3. Threat Model & Security Evaluation

| Risk / Threat Vector | Mitigation Strategy Implemented in Phase D | Status |
| :--- | :--- | :--- |
| **User Enumeration** | Discovery endpoint returns identical HTTP 404 error when an NX-ID does not exist or when the requesting user is blocked by the target. | Verified |
| **Profile Snooping** | Non-discoverable fields: phone numbers, emails, and device IDs are stripped from API outputs. Only public key and profile info are returned. | Verified |
| **Self-Connection Spoofing** | Database constraint `chk_no_self_request` and API handler checks reject self-requests with HTTP 400. | Verified |
| **Spam / Request Flooding** | Discovery rate limiter (10 req/min) and request rate limiter (20 req/hour) throttle spam attempts with HTTP 429. | Verified |
| **Race Conditions / Duplicate Pairs** | PostgreSQL unique partial index `idx_unique_pending_request` and canonical ordering constraint `chk_canonical_users` prevent race duplicates. | Verified |
| **Simultaneous Cross-Requests** | Atomic transaction detects reciprocal pending request and automatically transitions to an active mutual connection. | Verified |
| **Harassment Post-Block** | User blocks immediately terminate connections, reject pending requests, and prevent future request dispatches (HTTP 403). | Verified |

---

## 4. Verification & Audit Conclusion

Phase D connection management is completely implemented and passes 100% of unit, integration, and security test suites across both Go backend and Android Kotlin codebases.
