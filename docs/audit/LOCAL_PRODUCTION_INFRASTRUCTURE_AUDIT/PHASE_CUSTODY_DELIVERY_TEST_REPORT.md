# NEXA — Phase Verification Test Report: Delivery State & Custody ACK Separation

**Date:** 2026-11-09  
**Repository:** `H:\chat_app`  
**Execution Environment:** Windows PowerShell / OpenJDK 25 / Go 1.22+  
**Status:** 100% PASSING (Zero Regressions)  

---

## 1. Executive Summary

This test report documents the full implementation, security verification, and regression testing for the **Delivery State and Custody ACK Separation Architecture** across the Go Cloud Gateway and Android client.

### Key Invariants Formally Verified
1. **Relay Custody $\neq$ Delivery:** Intermediate relay nodes accepting and storing DTN envelopes transition messages to `RELAYED`, never `DELIVERED`.
2. **Cloud Queue $\neq$ Delivery:** Cloud Gateway accepting and buffering offline messages transitions messages to `QUEUED`, never `DELIVERED`.
3. **Delivery ACK Preconditions:** A Delivery ACK (`ACK_DEVICE_DELIVERED`) is emitted **strictly post-decryption and post-persistence** in SQLCipher Room DB. Pre-decryption ACKs and persistence failure ACKs are completely eliminated.
4. **Offline Queue Protection:** Custody ACKs are strictly RAM-routed in the Cloud Gateway and never call `h.buffer.Acknowledge`, preventing offline queue deletion by rogue mules.
5. **Multi-Hop Store-and-Forward:** Verified across an end-to-end 4-hop chain (`Sender -> Relay A -> Relay B -> Relay C -> Recipient`).

---

## 2. Go Backend Verification Suite

### 2.1 Wire Encoding & Tag Verification (`internal/wire`)

| Test Name | File | Result | Duration | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `TestNexaWirePacket_DeliveryAck_Extended_EncodeDecode` | `wire_test.go` | **PASS** | 0.00s | Verifies tag 7 extended fields (`recipient_nx_id`, `device_id`, `timestamp`, `signature`). |
| `TestNexaWirePacket_CustodyAck_EncodeDecode` | `wire_test.go` | **PASS** | 0.00s | Verifies tag 15 (`CustodyAckPayload`, `CustodyStatus` enum). |

### 2.2 Hub Delivery & Custody Isolation Suite (`internal/connection/delivery_custody_test.go`)

| Test Name | Result | Duration | Verified Security Property |
| :--- | :--- | :--- | :--- |
| `TestCloudQueueAcceptance_DoesNotImplyDelivery` | **PASS** | 0.09s | Message buffered in Redis; sender receives NO premature delivery ACK; message stays in offline queue. |
| `TestCustodyAck_DoesNotImplyDelivery` | **PASS** | 0.08s | Relay sends `PACKET_CUSTODY_ACK`; routed in RAM only; recipient's Redis offline queue remains intact. |
| `TestValidRecipientDeliveryAck_ReconcilesQueue` | **PASS** | 0.11s | Authenticated recipient emits `ACK_DEVICE_DELIVERED`; Redis offline queue properly reconciled and cleared. |
| `TestDeliveryAck_AntiSpoofingAndMismatchedRecipient` | **PASS** | 0.09s | Attacker session attempting to acknowledge another user's queue is rejected; queue remains safe. |
| `TestDeliveryAck_AckTypeConfusionRejected` | **PASS** | 0.08s | Malicious packet spoofing ACK type is rejected; no queue tampering occurs. |
| `TestDeliveryAck_IdempotentDuplicateProcessing` | **PASS** | 0.12s | Redundant delivery ACKs are processed idempotently without error or state corruption. |

---

## 3. Android Verification Suite

### 3.1 Delivery State & Custody Integration Suite (`DeliveryStateAndCustodyTests.kt`)

| Test Name | Result | Duration | Verified Behavior |
| :--- | :--- | :--- | :--- |
| `testRelayPersistsEnvelopeBeforeCustodyAck` | **PASS** | 0.061s | Relay persists envelope to `dtn_envelopes` with `CUSTODY_ACCEPTED` before dispatching `PACKET_CUSTODY_ACK`. |
| `testPersistenceFailurePreventsCustodyAck` | **PASS** | 0.008s | Disk write failure suppresses Custody ACK dispatch (fails-closed). |
| `testCustodyAckDoesNotChangeDeliveryStateToDelivered` | **PASS** | 0.005s | Ingestion of Custody ACK updates sender message to `RELAYED`, strictly asserting `status != DELIVERED`. |
| `testRecipientPersistenceTriggersDeliveryAck` | **PASS** | 0.009s | Recipient decrypts and writes to SQLite messages table, triggering signed Delivery ACK. |
| `testInvalidDeliveryAckIsRejected` | **PASS** | 0.005s | Delivery ACK claiming mismatched recipient NX-ID is rejected; status remains `QUEUED`. |
| `testInvalidCustodyAckIsRejected` | **PASS** | 0.008s | Custody ACK with corrupted digital signature is rejected; status remains `CREATED`. |
| `testDuplicateCustodyAckIsIdempotent` | **PASS** | 0.070s | Repeated Custody ACKs process idempotently without corrupting state. |
| `testDuplicateDeliveryAckIsIdempotent` | **PASS** | 0.009s | Repeated Delivery ACKs process idempotently without duplicate notifications. |
| `testRelayRestartRecoversCustody` | **PASS** | 0.304s | Simulated process restart queries active custody envelopes (`expiresAt > nowSec`), recovering forwarding queue. |
| `testExpiredEnvelopeIsNotDelivered` | **PASS** | 0.029s | Envelope with `expiresAt <= nowSec` is dropped immediately and rejected from vault cache. |
| `testMultiHopSimulation_FullCustodyAndDeliveryLifecycle` | **PASS** | 0.095s | Complete 4-hop simulation: Alice $\to$ Relay A $\to$ Relay B $\to$ Relay C $\to$ Bob, verifying full custody handovers and final Delivery ACK return path. |

**Suite Result:** 11 / 11 tests passed (100%).

---

## 4. Full Regression Verification

### Go Backend Suite
```
ok  	nexa/wire/internal/wire		(cached)
ok  	nexa/wire/internal/connection	2.672s
```
**Result:** 100% PASSING.

### Full Android Suite (`.\gradlew testDebugUnitTest`)
- **Total Tests Executed:** 395
- **Failures:** 0
- **Skipped:** 0
- **Duration:** 4.882s
- **Success Rate:** 100%

---

## 5. Exact Reproduction Commands

### Run Go Wire & Hub Tests
```powershell
cd H:\chat_app\backend
go test -v ./internal/wire/... ./internal/connection/...
```

### Run Android Delivery & Custody Test Suite
```powershell
cd H:\chat_app
.\gradlew testDebugUnitTest --tests "com.nexa.app.DeliveryStateAndCustodyTests"
```

### Run Full Android Unit Test Suite
```powershell
cd H:\chat_app
.\gradlew testDebugUnitTest
```
