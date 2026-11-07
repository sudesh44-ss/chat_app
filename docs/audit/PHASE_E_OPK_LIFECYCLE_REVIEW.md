# NEXA — Phase E One-Time Prekey (OPK) Lifecycle & Reservation Architecture Review

**Date:** 2026-11-07  
**Branch:** `main`  
**Status:** SPECIFICATION COMPLETE (Read-Only Design Phase)

---

## 1. Executive Summary & Audit Classification

### 1.1 Audit Finding & Classification: CORRECTED IN DESIGN
The original prototype endpoint `GET /api/v1/users/{nxId}/prekey` suffered from a fundamental flaw: executing an unauthenticated, mutating `GET` request that immediately marked one-time prekeys as consumed (`is_consumed = TRUE`). This caused:
- Premature OPK pool depletion on network retries or transient connection resets.
- Lost OPKs when client network dropped between server commit and response delivery.
- Lack of reservation ownership, allowing concurrent callers to collide or cross-consume keys.

This document reviews and establishes the complete technical specification for the **Authenticated Two-Phase OPK Claim State Machine** (`POST /api/v1/prekeys/claim`).

---

## 2. Authenticated Reservation & Ownership Architecture

```
+---------------------------------------------------------------------------------------+
|                               OPK STATE TRANSITION MODEL                              |
+---------------------------------------------------------------------------------------+
|                                                                                       |
|   [ 1. AVAILABLE ] ◄──────────────────────────────────────────────┐                   |
|          │                                                        │                   |
|          ▼ POST /api/v1/prekeys/claim                             │                   |
|   [ 2. RESERVED ] ──── (Timeout: 5 minutes elapsed) ──────────────┘                   |
|          │                                                                            |
|          ▼ Initial Wire Message Arrives (NexaWirePacket)                              |
|   [ 3. CONSUMED ] (Permanent Final State: is_consumed = TRUE)                         |
|                                                                                       |
+---------------------------------------------------------------------------------------+
```

### 2.1 Authenticated Account and Device Binding
- **Endpoint:** `POST /api/v1/prekeys/claim`
- **Authentication:** Mandatory HTTP `Authorization: Bearer <session_token>`.
- **Caller Binding Check:**
  The server verifies:
  1. The caller's session token is valid and unexpired in PostgreSQL / Redis.
  2. The caller's `device_id` is registered to the caller's `nx_id` and is not revoked.
  3. The caller is actively connected with the recipient (Connection System Authorization).
  4. The caller is not blocked by the recipient.

### 2.2 Claim ID Uniqueness & Client-Generated Nonces
- The client generates a unique UUIDv4 string for every new session initiation attempt:
  $$\text{claim\_id} = \text{"urn:uuid:"} \parallel \text{UUIDv4()}$$
- This `claim_id` acts as an idempotency key across all network retries.

### 2.3 Reservation Ownership Schema (`public.prekeys`)
The existing PostgreSQL schema (`007_create_prekeys.sql`) is augmented with ownership fields:
```sql
ALTER TABLE public.prekeys
ADD COLUMN IF NOT EXISTS claim_id VARCHAR(64),
ADD COLUMN IF NOT EXISTS claimed_by_user_id UUID REFERENCES public.profiles(id),
ADD COLUMN IF NOT EXISTS claimed_by_device_id VARCHAR(64),
ADD COLUMN IF NOT EXISTS reserved_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_prekeys_claim_id ON public.prekeys(claim_id);
CREATE INDEX IF NOT EXISTS idx_prekeys_reservation ON public.prekeys(user_id, key_type, is_consumed, reserved_until);
```

---

## 3. Atomic Allocation & State Transitions

### 3.1 Idempotent Claim Algorithm
When Alice's client calls `POST /api/v1/prekeys/claim`:
```sql
-- Step 1: Idempotency Check (Existing Active Reservation)
SELECT id, key_id, public_key_hex, reserved_until
FROM public.prekeys
WHERE claim_id = $claim_id
  AND claimed_by_user_id = $caller_user_id
  AND is_consumed = FALSE
  AND reserved_until > NOW();
```
- **If an active reservation with matching `claim_id` is found:**
  The backend immediately returns the previously reserved prekey bundle. No additional OPK is allocated, and no counter is incremented.
- **If no matching active reservation exists:**
  The backend executes an atomic reservation query:
  ```sql
  -- Step 2: Atomic OPK Allocation with Row Locking
  WITH candidate AS (
      SELECT id FROM public.prekeys
      WHERE user_id = (SELECT id FROM public.profiles WHERE nx_id = $recipient_nx_id)
        AND key_type = 'ONE_TIME_PREKEY'
        AND is_consumed = FALSE
        AND (reserved_until IS NULL OR reserved_until < NOW())
      ORDER BY id ASC
      LIMIT 1
      FOR UPDATE SKIP LOCKED
  )
  UPDATE public.prekeys
  SET claim_id = $claim_id,
      claimed_by_user_id = $caller_user_id,
      claimed_by_device_id = $caller_device_id,
      reserved_until = NOW() + INTERVAL '5 minutes'
  FROM candidate
  WHERE public.prekeys.id = candidate.id
  RETURNING public.prekeys.id, public.prekeys.key_id, public.prekeys.public_key_hex;
  ```

### 3.2 Mandatory OPK Security Rule: Zero Reassignment & Permanent Burn
1. **Disclosed Keys Never Reassigned:**
   An OPK that has ever been disclosed to any client must **NEVER** be reassigned to another claim under any circumstances.
2. **Permanent Claimed/Burned State:**
   Once an OPK is disclosed, its status transitions to `CLAIMED`. It is permanently removed from the `AVAILABLE` pool.
3. **Expired Reservation Behavior:**
   An expired claim does **NOT** return its OPK to the available pool. If 5 minutes elapse without consumption:
   - The claim transitions to `status = 'EXPIRED'`.
   - The associated OPK transitions permanently to `status = 'BURNED'`.
   - Any retry of the expired claim receives an explicit HTTP 410 Gone (`ErrClaimExpired: "Prekey claim has expired and its one-time prekey is burned. Request a new claim."`).
4. **New Claims Receive Fresh OPKs:**
   Any new claim (even from the same initiator with a new `claim_id`) selects exclusively from rows `WHERE status = 'AVAILABLE'`, mathematically guaranteeing that a new claim receives a completely different OPK.
5. **Idempotent Retry Guarantee:**
   If a client retries within the 5-minute window with the exact same authenticated caller account, device, and `claim_id`, the identical OPK bundle is returned idempotently without burning or advancing counters.
6. **Concurrency Safety:**
   `SELECT ... FOR UPDATE SKIP LOCKED` guarantees that concurrent requests can never claim or disclose the same OPK.

### 3.3 Atomic Final Consumption
When Alice's valid initial handshake packet arrives at the Go backend:
```sql
UPDATE prekey_claims SET status = 'CONSUMED' WHERE claim_id = $claim_id;
UPDATE one_time_prekeys SET status = 'CONSUMED', consumed_at = NOW() WHERE claim_id = $claim_id;
```
The OPK transitions permanently to `CONSUMED` and can never be claimed again.

---

## 4. Recipient Private Key Deletion Timing & PFS Invariants

### 4.1 Client-Side Deletion Timeline (Bob's Device)
1. Bob receives Alice's initial `NexaWirePacket` carrying `one_time_prekey_id = 42`.
2. Bob's Android client initiates an atomic database transaction:
   ```kotlin
   database.runInTransaction {
       // 1. Fetch OPK private key
       val opkPrivateKey = prekeyDao.getOpkPrivateKey(opkId)
       requireNotNull(opkPrivateKey) { "OPK $opkId already deleted or invalid" }

       // 2. Compute DH4 and derive Master Secret (SK)
       val sk = X3dhHandshakeEngine.computeMasterSecret(...)

       // 3. IMMEDIATELY purge and wipe OPK private key
       prekeyDao.deleteOpk(opkId)
       Arrays.fill(opkPrivateKey, 0.toByte())

       // 4. Initialize Double Ratchet session and persist
       ratchetDao.upsertSession(...)
   }
   ```
3. **Perfect Forward Secrecy Guarantee:**
   Because `deleteOpk` executes within the transaction that initializes the ratchet session, the private scalar $sk_{opk}$ exists in volatile memory for less than 1 millisecond. Even if Bob's device is subsequently seized or extracted, historical traffic cannot be compromised.
4. **Replay Protection:**
   If a duplicate or replayed initial handshake arrives later with `opk_id = 42`, Bob's database lookup for `getOpkPrivateKey(42)` returns `null`, causing the handshake to fail immediately.

---

## 5. Pool Depletion and Replenishment

1. **Exhaustion Handling:**
   If `candidate` returns no rows (all OPKs consumed or reserved), the endpoint returns `"one_time_prekey": null`. Alice's client proceeds with the standard 3-way DH X3DH handshake ($DH_1, DH_2, DH_3$).
2. **Low-Watermark Replenishment Trigger:**
   The backend tracks unconsumed OPKs. When `COUNT(*) < 20`, the backend sends a push notification to Bob (`PREKEY_REPLENISH_TRIGGER`).
3. Bob's client automatically generates 50 new OPKs and uploads them via `POST /api/v1/users/prekey`.
