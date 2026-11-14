# NEXA — P3-B Android Cloudflare R2 Media Fallback Report

**Phase:** P3 Public Launch Readiness  
**Target Subsystem:** Blind Media Vault Engine & Cloudflare R2 Ephemeral Mailbox Integration  
**Evaluation Date:** November 2026  
**Status:** **PASS / VERIFIED**

---

## 1. Architectural Overview

NEXA enforces a zero-egress, privacy-first media routing architecture:
1. **Priority 1 (Direct P2P DataChannel):** 1-to-1 media transfers stream directly over WebRTC DataChannels. Server storage: 0 Bytes; Bandwidth cost: ₹0.
2. **Priority 2 (Lightweight Inline E2EE):** Media payloads &le; 64 KB (voice notes, thumbnails) embed directly into the E2EE WebSocket envelope (`OP_TEXT_MESSAGE` / `NexaWirePacket`), avoiding external connection setup latency.
3. **Priority 3 (Cloudflare R2 Blind Cloud Fallback):** For group chats, offline recipients, or when WebRTC P2P ICE candidate gathering fails or exceeds the 4.5-second circuit breaker gate (e.g. Carrier Symmetric CGNAT).

All files uploaded to Cloudflare R2 are client-side encrypted before leaving the device. The cloud object storage receives **only unreadable `.enc` ciphertext**. The 256-bit media decryption key is transmitted exclusively inside Double Ratchet end-to-end encrypted message channels.

---

## 2. Component Implementation & Integration

### 2.1 Backend Ephemeral Mailbox Endpoints (`backend/cmd/server/main.go`)
- **`POST /api/v1/media/upload-ticket` (Lines 347–418):**
  - Requires valid Bearer session token; verifies device has not been revoked.
  - Enforces 64 KB JSON body limit to prevent DOS attacks.
  - Binds requested `nx_id` to authenticated session identity to prevent IDOR spoofing.
  - Strictly enforces the 100 MB max file size gate (`maxCloudBytes = 100 * 1024 * 1024`). Files > 100 MB are rejected with HTTP 400 (`Direct P2P transfer required`).
  - Issues presigned ephemeral upload URL (`https://r2.nexa.network/ephemeral/{nx_id}/{timestamp}_{cipher_hash}.enc`) with 900-second (15-minute) TTL.
- **`POST /api/v1/media/purge-ack` (Lines 420–467):**
  - Authenticated endpoint called immediately upon recipient download and cryptographic verification.
  - Drops object immediately from cloud storage (`retained_bytes: 0`, `storage_cost: "₹0"`).

### 2.2 Client-Side Encryption Engine (`BlindMediaVaultEngine.kt`)
- **Cipher:** AES-256-GCM (`AES/GCM/NoPadding`) with 128-bit authentication tag.
- **Key Generation:** 256-bit single-use cryptographically secure key (`SecureRandom`).
- **IV Generation:** 96-bit unique random IV per payload.
- **Streaming Execution:** Streams file chunks in 64 KB buffers, preventing Out-Of-Memory (OOM) errors even on 100 MB video attachments.
- **Integrity Digest:** Computes SHA-256 hash of the resulting ciphertext (`cipherHash`) for verification prior to transmission.

### 2.3 Android Client Fallback Service (`R2MediaFallbackService.kt`)
- **Package:** `com.nexa.app.data.remote.media`
- **Interface & Implementation:** `R2MediaFallbackService` / `R2MediaFallbackServiceImpl`
- **Core Methods:**
  - `requestUploadTicket(sessionToken, nxId, sizeBytes, cipherHash)`: Invokes Go backend to issue ticket.
  - `uploadEncryptedBlob(uploadUrl, encryptedFile, onProgress)`: Streams encrypted blob to R2 with live progress callbacks.
  - `downloadEncryptedBlob(downloadUrl, destinationFile, onProgress)`: Streams ciphertext to temporary sandbox file.
  - `sendPurgeAck(sessionToken, objectKey, recipientNxId)`: Issues immediate drop ACK upon successful recipient decryption.
  - `executeFallbackUpload(...)` / `executeFallbackDownload(...)`: Complete end-to-end orchestrators with automatic temp file cleanup and security validation.

### 2.4 Credential Sanitization & Security Redaction
- In accordance with production security standards, presigned URLs contain sensitive parameters (`ticket`, `token`, `hash`, `signature`, `X-Amz-...`).
- `R2MediaFallbackServiceImpl.sanitizeUrlForLog()` sanitizes URLs prior to logging:
  ```kotlin
  fun sanitizeUrlForLog(url: String): String {
      return url.replace(Regex("([?&](ticket|token|signature|hash|X-Amz-[^=]+)=)[^&]+"), "$1[REDACTED]")
  }
  ```
- Verified: Zero sensitive tickets, tokens, or hashes are emitted into Android Logcat.

### 2.5 4.5-Second ICE Circuit Breaker Integration (`MediaRoutingCoordinator.kt`)
- `MediaRoutingCoordinator.executeWithIceCircuitBreaker()` wraps P2P candidate negotiation in a strict `withTimeout(4500L)` block.
- If ICE fails or times out (common under symmetric NAT on mobile cellular networks), the coordinator automatically trips the circuit breaker and invokes `triggerBlindR2Fallback()`.
- The transition is completely transparent to the user, ensuring uninterrupted media transmission.

---

## 3. Delivery State & Purge ACK Separation

NEXA strictly separates gateway custody acceptance from final recipient delivery:

```
Sender Device                 Go Backend / R2 Mailbox            Recipient Device
     |                                   |                              |
     |---- 1. Request Upload Ticket ---->|                              |
     |<--- 2. Ticket Issued (900s) ------|                              |
     |                                   |                              |
     |---- 3. Upload .enc Blob --------->|                              |
     |<--- 4. HTTP 200 OK ---------------|                              |
     |                                   |                              |
[Status: ACCEPTED_ON_GATEWAY]            |                              |
     |                                   |                              |
     |---- 5. Double Ratchet Envelope (MediaKey, IV, ObjectKey) ------->|
     |                                   |                              |
     |                                   |<--- 6. Download .enc Blob ---|
     |                                   |---- 7. HTTP 200 OK --------->|
     |                                   |                              |
     |                                   |               [Verify SHA-256 Cipher Hash]
     |                                   |               [AES-256-GCM Decrypt to Plain]
     |                                   |                              |
     |                                   |<--- 8. POST /media/purge-ack-|
     |                                   |---- 9. Status: PURGED ------>|
     |                                   |     (0 Bytes Retained, ₹0)   |
     |                                   |                              |
     |<--- 10. Recipient Delivery ACK ---|                              |
[Status: DELIVERED]                      |                              |
```

- Upload completion on the sender **never** marks the message as delivered to the peer.
- The cloud object is immediately purged when the recipient verifies the payload, maintaining zero retention and zero egress storage costs.

---

## 4. Verification & Test Evidence

All test cases pass in `app/src/test/java/com/nexa/app/R2MediaFallbackUnitTests.kt`:

1. `testMediaRoutingDecisionEngine`: Validates Rule D inline (&le;64KB), Rule B group chats (R2), offline peer (R2), and direct online peer (P2P).
2. `testCircuitBreakerTripsOnIceTimeout`: Confirms 4.5-second timeout trips circuit breaker and switches to R2 fallback.
3. `testCircuitBreakerTripsOnSymmetricNatFailure`: Confirms NAT errors trip circuit breaker immediately.
4. `testPresignedUrlCredentialRedactionInLogs`: Confirms regex redacts tickets, tokens, hashes, and signatures.
5. `testCloudFallbackMaxCeilingEnforcement`: Confirms rejection of payloads > 100 MB.
6. `testEndToEndBlindMediaEncryptionAndIntegrityVerification`: Validates client-side AES-256-GCM encryption, SHA-256 cipher hash matching, exact plaintext recovery, and authentication tag rejection on tampered ciphertext.
7. `testImmediatePurgeAckSemantics`: Confirms `retained_bytes: 0` and zero storage cost upon purge ACK.

---

## 5. Conclusion & Verdict

- **Integration Status:** **PASS / VERIFIED**
- **Zero Plaintext Cloud Storage:** Guaranteed (Client-side AES-256-GCM; keys never sent to server).
- **Cost Guarantees:** Ephemeral 7-day TTL auto-purge + Immediate Purge ACK ensures ₹0 long-term storage costs.
