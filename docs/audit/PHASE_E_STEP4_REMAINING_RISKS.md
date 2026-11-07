# NEXA Phase E Step 4 — Remaining Risks & Pre-Step 5 Verification

**Audit Date:** 2026-11-07  
**Scope:** Android E2EE Integration, Backend Synchronization, Multi-Device Scenarios  
**Mode:** Development / Staging Only

---

## 1. Residual Cryptographic & Protocol Risks

### 1.1 One-Time Prekey Pool Replenishment (Low / Operational)
- **Risk:** If a popular recipient receives many initial messages in a short timeframe, their active OPK pool on the backend can drop below the minimum threshold (e.g., < 20 OPKs).
- **Mitigation Status:** The backend returns remaining OPK count in the prekey bundle response (`remaining_opks`). The Android client needs a background replenishment worker (WorkManager) that triggers whenever `remaining_opks` falls below 20 or when receiving a push notification from the backend.
- **Recommended Action for Step 5:** Implement automated background replenishment in `PrekeyReplenishmentWorker`.

---

### 1.2 Multi-Device Session Fan-Out (Medium / Architectural)
- **Risk:** When an account has multiple registered devices (e.g. phone + tablet), sending a message requires establishing an independent Double Ratchet session with EACH registered device of the contact, as well as the sender's own secondary devices (self-fanout for cross-device message synchronization).
- **Mitigation Status:** Step 3 backend stores prekeys indexed by `(account_id, device_id)` and returns all active device IDs in `/api/v1/users/{nxId}/devices`. Step 4 Android client supports `remoteDeviceId` in `RatchetStateEntity`.
- **Recommended Action for Step 5:** Implement multi-device pairwise session management in `ChatRepositoryImpl` to fan out encrypted packets to all recipient devices.

---

### 1.3 Offline Blind Packet Queue Depth & Expiration (Low)
- **Risk:** Long-term offline clients accumulating thousands of queued encrypted packets could exceed storage or cause excessive ratchet lookahead upon reconnection.
- **Mitigation Status:** The ratchet engine implements a strict lookahead boundary (`maxLookahead = 2000`) and the database enforces a 14-day TTL on skipped keys. The backend offline queue also imposes limits.
- **Recommended Action for Step 5:** Verify offline queue compaction and pagination on client reconnection.

---

## 2. Gate Verification Checklist for Step 5

| Verification Requirement | Status | Evidence |
|---|---|---|
| All 262 Android unit & crypto tests passing | **YES** | `:app:testDebugUnitTest` BUILD SUCCESSFUL |
| Android Debug APK assemble & package | **YES** | `:app:assembleDebug` BUILD SUCCESSFUL |
| Go backend tests passing | **YES** | `go test -count=1 ./...` PASS |
| Go backend vet passing | **YES** | `go vet ./...` PASS |
| Official Signal X3DH KAT verification | **YES** | Vector A & Vector B exact match |
| Official Signal Double Ratchet KAT verification | **YES** | $DH_{init}, RK_1, CK_1, MK_0$ exact match |
| Zero backend knowledge of plaintext or private keys | **YES** | Cryptographic audit confirmed |
| Production deployment halted | **YES** | Strictly development/staging environment |

---

## 3. Mandatory Stop Condition

Phase E Step 4 is complete. All 6 required audit and verification reports are documented in `docs/audit/`.

**Do NOT proceed to Step 5 (Multi-Device Routing & Offline Queue Hardening) without explicit user authorization.**
