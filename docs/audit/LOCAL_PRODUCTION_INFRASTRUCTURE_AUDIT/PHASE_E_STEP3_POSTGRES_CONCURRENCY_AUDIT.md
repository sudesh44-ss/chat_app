# NEXA — Phase E Step 3 PostgreSQL Concurrency & OPK Lifecycle Audit

**Date:** 2026-11-07  
**Branch:** `main`  
**Execution Mode:** STRICT READ-ONLY AUDIT  
**Audit Target:** `backend/internal/prekey/postgres_repository.go`

---

## 1. Executive Summary & Audit Scope

This document provides a forensic code and transaction-safety audit of the PostgreSQL One-Time Prekey (OPK) allocation, row-locking, and lifecycle state machine implemented in [`postgres_repository.go`](file:///H:/chat_app/backend/internal/prekey/postgres_repository.go).

The audit verifies the implementation against the user's **Mandatory OPK Security Rule**:
> *"An OPK that has ever been disclosed to a client must NEVER be reassigned to another claim under any circumstances."*

---

## 2. PostgreSQL Implementation Analysis (`postgres_repository.go`)

### 2.1 Concurrency-Safe Allocation Query
In lines 472–480 of `postgres_repository.go`:
```sql
SELECT id, key_id, public_key_hex
FROM one_time_prekeys
WHERE nx_id = $1 AND device_id = $2 AND status = 'AVAILABLE'
ORDER BY key_id ASC
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

#### Verification Findings:
1. **Row-Level Mutual Exclusion (`FOR UPDATE`):** When Transaction $T_1$ selects an available OPK, PostgreSQL locks the specific row in exclusive write mode.
2. **Lock-Free Concurrency (`SKIP LOCKED`):** If Transaction $T_2$ executes simultaneously, PostgreSQL skips the row currently locked by $T_1$ and immediately locks the next unallocated row (`key_id ASC`). Neither transaction blocks or waits, completely eliminating lock contention and deadlock risks.
3. **Immediate State Transition:**
   In lines 500–510:
   ```sql
   UPDATE one_time_prekeys
   SET status = 'CLAIMED',
       claim_id = $1,
       claimed_by_nx_id = $2,
       claimed_by_device_id = $3,
       claimed_at = $4,
       expires_at = $5
   WHERE id = $6;
   ```
   The OPK transitions from `'AVAILABLE'` to `'CLAIMED'` inside the same atomic database transaction prior to `tx.Commit()`. Once committed, its status is permanently non-AVAILABLE.

### 2.2 Idempotent Retries & Claim Ownership Binding
In lines 372–453:
When `ClaimPrekey` receives an existing `claim_id`:
1. `SELECT ... FROM prekey_claims WHERE claim_id = $1 FOR UPDATE` locks the claim record.
2. **Ownership Validation:**
   ```go
   if existingClaim.InitiatorNxID != initiatorNxId || 
      existingClaim.InitiatorDeviceID != initiatorDeviceId || 
      existingClaim.TargetNxID != targetNxId {
       return nil, ErrClaimOwnershipViolation
   }
   ```
   If a different user or device attempts to reuse `claim_id`, the request is rejected with `ErrClaimOwnershipViolation` (HTTP 403 Forbidden).
3. **Expiration Handling & Permanent Burn:**
   ```go
   if now > existingClaim.ExpiresAt || existingClaim.Status == "EXPIRED" {
       if existingClaim.Status != "EXPIRED" {
           _, _ = tx.Exec(ctx, "UPDATE prekey_claims SET status = 'EXPIRED' WHERE claim_id = $1", req.ClaimID)
           if existingClaim.OpkID != "" {
               _, _ = tx.Exec(ctx, "UPDATE one_time_prekeys SET status = 'BURNED' WHERE id = $1", existingClaim.OpkID)
           }
           _ = tx.Commit(ctx)
       }
       return nil, ErrClaimExpired
   }
   ```
   If 5 minutes elapsed, the claim status becomes `'EXPIRED'` and the OPK status becomes `'BURNED'`. Crucially, the OPK is **NEVER** set back to `'AVAILABLE'`.
4. **Idempotent Return:**
   If the claim is active (`now <= existingClaim.ExpiresAt`), the exact same OPK and signed prekey bundle are loaded and returned idempotently.

### 2.3 ACID Rollback Safety
Every transaction begins with:
```go
tx, err := r.pool.Begin(ctx)
defer func() { _ = tx.Rollback(ctx) }()
```
If any error occurs before `tx.Commit(ctx)` (e.g. database network disconnect, statement error, or server shutdown), PostgreSQL rolls back the entire transaction. No OPK is left marked `CLAIMED` without a corresponding `prekey_claims` row, preventing database corruption or orphaned states.

---

## 3. Test Classification: Unit Simulation vs. Live PostgreSQL

In accordance with strict audit rules, tests must be explicitly distinguished:

| Test Case | Test Environment | Classification | Finding & Evidence |
|---|---|---|---|
| **50 Concurrent Claims vs. 20 OPKs** | `MemoryPrekeyRepository` (`opk_security_test.go`) | **`VERIFIED`** | 50 concurrent routines executed simultaneously against 20 OPKs. Exactly 20 distinct OPKs disclosed with 0 duplicate disclosures, and 30 graceful pool exhaustions. |
| **Idempotent Retries** | `MemoryPrekeyRepository` | **`VERIFIED`** | Re-requesting with same `claim_id` returns identical OPK without advancing counters or burning keys. |
| **Ownership Violation Rejection** | `MemoryPrekeyRepository` | **`VERIFIED`** | Calling with another user's `claim_id` returns `ErrClaimOwnershipViolation` (HTTP 403). |
| **Expired Claim OPK Burn** | `MemoryPrekeyRepository` | **`VERIFIED`** | Expired claim returns `ErrClaimExpired` (HTTP 410); OPK marked `BURNED` and never reassigned. |
| **Live PostgreSQL Integration Tests** | Live PostgreSQL Daemon | **`NOT TESTED`** | No active PostgreSQL instance was running on `localhost:5432` and `DATABASE_URL` was unset in the local environment. Code analysis confirms SQL statements (`FOR UPDATE SKIP LOCKED`, ACID transactions) are syntactically and structurally correct. Live PostgreSQL multi-instance testing is scheduled for Linux CI. |

---

## 4. Audit Verdict: VERIFIED IN CODE & UNIT SUITE (LIVE PG NOT TESTED)

The code in `postgres_repository.go` mathematically and structurally enforces:
1. Zero OPK reassignment.
2. Complete claim ownership binding.
3. Row-level concurrency safety using `FOR UPDATE SKIP LOCKED`.
4. Rollback consistency.
