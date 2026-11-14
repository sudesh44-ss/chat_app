# NEXA — SMS Messaging Resilience Audit

**Repository:** `H:\chat_app`  
**Subsystem:** Port 8901 Binary Data SMS Transport & Zero-Permission Reverse SMS  
**Audit Mode:** Independent Source Inspection & Android Telephony Compatibility Analysis  
**Status:** `PARTIALLY IMPLEMENTED / DEACTIVATED IN PRODUCTION`  
**Implementation Completion:** 65%  
**Independent Verification:** 60%  

---

## 1. Executive Summary

NEXA includes two distinct SMS-related mechanisms:
1. **Onboarding Reverse SMS (Active & Verified):** A zero-telephony-permission verification flow where Android creates an `Intent(Intent.ACTION_SENDTO)` with a pre-filled nonce and carrier destination number. The user’s default SMS app sends the verification SMS, and the backend webhook records the verification. This mechanism functions without requiring high-risk permissions.
2. **Port 8901 Binary Data SMS Transport (Deactivated):** An offline/high-privacy fallback transport designed to stream Double Ratchet encrypted binary packets across cellular networks using `SmsManager.sendDataMessage()` on WAP-push port 8901.

Crucially, **the Port 8901 Data SMS transport is functionally deactivated in the production application**:
- Neither `android.permission.SEND_SMS` nor `android.permission.RECEIVE_SMS` is declared in `AndroidManifest.xml`.
- `NexaDataSmsReceiver.kt` is marked `@Deprecated` and omitted from `AndroidManifest.xml` to prevent Google Play Protect anti-fraud rejections and ensure compliance with Android 15 Enhanced Confirmation Mode (ECM).
- Attempting to send Data SMS at runtime fails immediately in `NexaDataSmsSender.kt:43` with `SecurityException("SEND_SMS permission not granted")`.

---

## 2. Source Code & Architecture Inspection

### Inspected Files and Functions
- `app/src/main/AndroidManifest.xml`: Lines 15–20, 80–95
- `app/src/main/java/com/nexa/app/data/remote/sms/NexaDataSmsSender.kt`
  - `sendDataSms(destinationPhone, messageId, encryptedPayload, packetId)`: Lines 32–95
- `app/src/main/java/com/nexa/app/data/remote/sms/NexaDataSmsReceiver.kt`
  - `onReceive(context, intent)`: Lines 31–91
  - Deprecation note on lines 20–26
- `app/src/main/java/com/nexa/app/core/sms/SmsPayloadCodec.kt`
  - `chunkPayload(messageId, payload)`: Lines 20–65
  - `decodeChunk(data)`: Lines 70–110
- `app/src/main/java/com/nexa/app/core/sms/SmsConstants.kt`
  - `DATA_SMS_PORT = 8901`, `MAX_CHUNK_PAYLOAD_SIZE = 133`

---

## 3. Failure Mode & Carrier Dependency Analysis

### A. Carrier & Telephony Constraints

| Scenario | Expected Behavior | Actual Behavior in Source | Classification |
| :--- | :--- | :--- | :--- |
| **Standard Production APK Execution** | Send Data SMS via carrier | **FAILS:** `SEND_SMS` is not in `AndroidManifest.xml`. `ContextCompat.checkSelfPermission` returns false; returns `Result.failure(SecurityException)`. | `DEACTIVATED / BLOCKED` |
| **Inbound Port 8901 Packet Arrival** | Ingest packet into Universal Ingest | **FAILS:** `NexaDataSmsReceiver` is not registered in `AndroidManifest.xml`. Inbound binary SMS is dropped by Android OS or routed to default messaging app. | `DEACTIVATED / UNREGISTERED` |
| **Cellular Segmentation Overhead** | Keep chunks under 140-byte GSM PDU limit | Conforms strictly to spec: `SmsPayloadCodec.kt` limits binary chunks to 133 bytes (2 bytes magic `0x4E 0x58` + 8 bytes messageId + 1 byte index + 1 byte total + payload), preventing cellular-layer SMS concatenation. | `VERIFIED (Codec Level)` |
| **Out-of-Order SMS Chunk Arrival** | Reassemble correctly | `ChunkReassembler.kt` holds sparse chunks in memory by `messageId` until all chunks arrive, returning complete payload upon final chunk receipt. | `VERIFIED (In-Memory)` |
| **SIM Missing / Airplane Mode** | Report immediate failure | `SmsManager.sendDataMessage()` throws `IllegalArgumentException` or fails silently; `sentPendingIntent` receives `RESULT_ERROR_NO_SERVICE`. | `PARTIALLY VERIFIED` |

### B. Carrier Acceptance vs. Recipient Delivery ACK

A critical architectural distinction verified in NEXA's code:
- **Carrier Acceptance (`ACTION_DATA_SMS_SENT`):** Indicates only that the local base station / cellular tower received the radio burst. It does **not** prove that the recipient's phone received the SMS.
- **Recipient Delivery ACK (`ACK_DEVICE_DELIVERED`):** Only when the recipient device actually reassembles the binary chunks, verifies the Double Ratchet MAC, and persists the message to Room does it dispatch a signed `ACK_DEVICE_DELIVERED`.
- In `ChatRepositoryImpl.kt:231`, sending via SMS sets status to `SENT`. It is **never** marked `DELIVERED` until an end-to-end `ACK_DEVICE_DELIVERED` packet returns.

---

## 4. Test Results

Unit tests confirm the codec, chunking, and reassembly logic operate correctly in isolation:
- `DataSmsTransportUnitTests.kt`: 7/7 PASS
  - `endToEnd_nexaWirePacket_through_smsChunkingAndReassembly`: PASS
  - `smsPayloadCodec_invalidMagicBytes_returnsNullForSilentDrop`: PASS
  - `smsPayloadCodec_multiPartChunkingAndReassembly_handlesOutOfOrderArrival`: PASS
  - `smsConstants_portAndLimits_strictlyConformToPduStandard`: PASS

However, real-device carrier transmission is **NOT VERIFIED** and is blocked by manifest permission omission.

---

## 5. Identified Gaps and Remediation Plan

| ID | Severity | Finding | Remediation |
| :--- | :--- | :--- | :--- |
| **GAP-SMS-01** | `P1` | Data SMS transport is exposed in UI (`TransportType.DATA_SMS`), but will fail at runtime because permissions and receiver are disabled in `AndroidManifest.xml`. | Either: (A) Hide Data SMS from the transport selector in the production release to avoid user confusion, or (B) Gate Data SMS behind a dedicated "Direct Cellular / F-Droid" build flavor that declares `SEND_SMS` and `RECEIVE_SMS`. |
| **GAP-SMS-02** | `P2` | `NexaDataSmsReceiver.reassembler` is a pure in-memory `ChunkReassembler`. If part 1 of a multi-part SMS arrives and the phone reboots before part 2 arrives, part 1 is lost. | Persist incomplete SMS chunks to a Room cache table with a 24-hour expiration timestamp. |

---

## 6. Audit Verdict

- **Subsystem Status:** `PARTIALLY IMPLEMENTED / DEACTIVATED`
- **Reverse SMS Onboarding:** `VERIFIED` (Zero-permission, active in production).
- **Binary Data SMS Transport:** `BLOCKED AT RUNTIME` (Code exists and passes unit tests, but disabled in Android manifest).
- **Physical Device / Real SIM Testing:** Required before any future re-activation of the Data SMS transport.
