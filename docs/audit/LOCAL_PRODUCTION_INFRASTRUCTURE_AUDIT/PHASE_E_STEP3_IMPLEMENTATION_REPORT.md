# NEXA — Phase E Step 3 Implementation Report: Prekey Repository & APIs

**Date:** 2026-11-07  
**Branch:** `main`  
**Phase:** Phase E — Step 3 (Backend Implementation)  
**Status:** IMPLEMENTATION COMPLETE & VERIFIED

---

## 1. Executive Summary

Phase E Step 3 implements the server-side cryptographic prekey infrastructure, PostgreSQL storage repositories, and authenticated REST APIs required for End-to-End Encrypted (E2EE) session establishment.

In accordance with user authorization and strict security boundaries:
- The **Mandatory OPK Security Rule** is fully implemented and enforced: an OPK disclosed to any client is permanently bound and never recycled or reassigned to another claim.
- The Go backend acts strictly as an authenticated blind directory for public keys. **Zero plaintext, zero private keys, and zero ratchet secrets** are ever handled or stored by the server.
- The implementation adheres to fail-closed security: production requires PostgreSQL and rejects unauthenticated or unverified requests.
- All 5 required endpoints were implemented, registered into `backend/cmd/server/main.go`, and verified through unit, integration, and high-concurrency test suites.

---

## 2. Implemented Architecture & Modules

### 2.1 Codebase Structure (`backend/internal/prekey/`)

| File | Purpose and Responsibilities |
|---|---|
| [`model.go`](file:///H:/chat_app/backend/internal/prekey/model.go) | Data structures: `IdentityKeyBinding`, `SignedPrekey`, `OneTimePrekey`, `PrekeyBundle`, `ClaimRequest`, `ClaimResponse`, and domain error definitions. |
| [`encoding.go`](file:///H:/chat_app/backend/internal/prekey/encoding.go) | Canonical length-prefixed (TLV) binary attestation encodings (`NEXA_BIND_IK` with magic `"NXBI"`, `NEXA_SIGN_SPK` with magic `"NXSP"`), Ed25519 signature verification, and standard RFC 4122 UUIDv4 generation. |
| [`repository.go`](file:///H:/chat_app/backend/internal/prekey/repository.go) | `PrekeyRepository` interface defining upload, retrieval, atomic claim, and consumption lifecycle contracts. |
| [`postgres_repository.go`](file:///H:/chat_app/backend/internal/prekey/postgres_repository.go) | Authoritative PostgreSQL implementation utilizing `*pgxpool.Pool`, row locking (`FOR UPDATE SKIP LOCKED`), and ACID transactions. |
| [`memory_repository.go`](file:///H:/chat_app/backend/internal/prekey/memory_repository.go) | In-memory thread-safe (`sync.RWMutex`) implementation strictly mirroring all OPK security rules for fast, isolated testing. |
| [`handler.go`](file:///H:/chat_app/backend/internal/prekey/handler.go) | HTTP request handlers with Bearer token authentication, account/device validation, Ed25519 signature verification, rate limiting, and blocklist filtering. |

### 2.2 Server Integration (`backend/cmd/server/main.go`)
- Added `prekeyHandler` registration into `http.DefaultServeMux`.
- Wired `PrekeyRepository` (Postgres in production/development, Memory in non-production fallback).
- Connected mutual connection validator (`hub.SetConnectionValidator`) to drop requests between blocked strangers.
- Connected distributed rate limiter (`ratelimit.NewDistributedLimiter`).
- Added all prekey endpoints to the diagnostic root endpoint (`/`).

---

## 3. Mandatory OPK Security Rule Implementation

The repository and claim engine strictly enforce the user's security mandates:

```
[ POST /api/v1/prekeys/claim ]
       │
       ▼
Query prekey_claims for claim_id
       │
       ├── Found? ──► Validates ownership (initiator_nx_id + initiator_device_id + target_nx_id).
       │              ├── Mismatch? ──► 403 Forbidden (ErrClaimOwnershipViolation)
       │              ├── Expired?  ──► 410 Gone (ErrClaimExpired) [OPK status = 'BURNED']
       │              ├── Consumed? ──► 409 Conflict (ErrClaimAlreadyConsumed)
       │              └── Active?   ──► 200 OK: Idempotent return of the identical OPK bundle.
       │
       └── Not Found? (New Claim)
              │
              ▼
       SELECT id, key_id, public_key_hex FROM one_time_prekeys
       WHERE nx_id = target_nx_id AND device_id = target_device_id AND status = 'AVAILABLE'
       ORDER BY key_id ASC LIMIT 1 FOR UPDATE SKIP LOCKED;
              │
              ├── Found available OPK:
              │     - Mark OPK status = 'CLAIMED' (never returns to AVAILABLE).
              │     - Insert into prekey_claims with 5-minute expiration deadline.
              │     - Return 200 OK with disclosed OPK.
              │
              └── None available (Pool Exhausted):
                    - Insert into prekey_claims with opk_id = NULL.
                    - Return 200 OK with one_time_prekey = null (Graceful 3-way DH fallback).
```

---

## 4. Test Verification Summary

The test suite executed with 100% pass rate:

1. **`TestCanonicalIdentityBindingEncodingAndVerification`**:
   - Verifies TLV binary encoding (`"NXBI"`) and Ed25519 verification.
   - Proves that tampered timestamps or public keys fail verification.
2. **`TestCanonicalSignedPrekeyEncodingAndVerification`**:
   - Verifies TLV binary encoding (`"NXSP"`) and Ed25519 verification.
   - Proves that tampered SPK IDs fail verification.
3. **`TestMandatoryOpkSecurityRules`**:
   - Proves Rule 1: Available count decreases immediately upon claim.
   - Proves Rule 2: Idempotent retry returns identical OPK.
   - Proves Rule 3: A subsequent claim receives a different OPK.
   - Proves Rule 4: Claim ownership violations are rejected with 403 Forbidden.
   - Proves Rule 5: Final consumption transitions claim to consumed.
   - Proves Rule 6: Pool exhaustion safely falls back to null OPK.
4. **`TestConcurrentOpkClaimsGuarantee`**:
   - Spun up 50 concurrent routines claiming from a pool of 20 OPKs.
   - Result: Exactly 20 OPKs claimed with **ZERO duplicate disclosures**, and 30 exhausted fallbacks.
5. **`TestHandlerEndpoints`**:
   - Verifies full HTTP request/response pipeline across all 5 endpoints.
6. **Full Backend Suite (`go test -count=1 ./...`)**:
   - 100% PASS across all backend packages (`auth`, `connection`, `connections`, `database`, `identity`, `prekey`, `queue`, `ratelimit`, `wire`).
   - Server compilation (`go build -o nul ./cmd/server`) succeeded with code 0.
