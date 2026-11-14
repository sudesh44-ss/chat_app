# NEXA — P3 Security & Regression Report

**Phase:** P3 Public Launch Readiness  
**Target Subsystems:** Mesh Cryptography, Cloud Media Vault, API Endpoint Security & Network Telemetry  
**Evaluation Date:** November 2026  
**Status:** **PASS / ALL CONTROLS VERIFIED**

---

## 1. Executive Summary

This security audit assesses the security properties, cryptographic invariants, and abuse resistance introduced during the P3 Public Launch Readiness phase across three key areas:
1. **P3-A Nearby Mesh:** Cryptographic integrity of `DtnEnvelope`, Ed25519 canonical signature binding, relay metadata separation, and DoS resilience via FIFO storage pruning.
2. **P3-B Cloudflare R2 Media Fallback:** Zero-knowledge client-side encryption (`BlindMediaVaultEngine`), IDOR prevention, credential sanitization in logs, and immediate purge ACK guarantees.
3. **P3-C Network Classification:** Accurate metered status detection preventing unauthorized cellular bandwidth consumption behind VPN tunnels.

All 119 backend security tests and all client-side unit test suites passed without regressions.

---

## 2. Cryptographic Security & E2EE Verification

### 2.1 Media Encryption & Zero-Plaintext Cloud Storage
- **Algorithm:** AES-256-GCM (`AES/GCM/NoPadding`) with a 128-bit authentication tag and 96-bit random IV.
- **Key Transport:** 256-bit media keys and 96-bit IVs are **never** transmitted to Cloudflare R2, AWS S3, or the NEXA Go backend. They are transported exclusively within Double Ratchet end-to-end encrypted message payloads over `NexaWirePacket`.
- **Ciphertext Hash Binding:** Before requesting an upload ticket, the client computes the SHA-256 hash of the `.enc` ciphertext. This hash is embedded into the upload ticket and verified by the recipient before decryption, preventing ciphertext tampering or substitution attacks.
- **Tamper Rejection:** Verified by `R2MediaFallbackUnitTests.testEndToEndBlindMediaEncryptionAndIntegrityVerification()`: single-bit alterations to ciphertext fail the GCM tag authentication check, preventing ciphertext malleability.

### 2.2 Delay-Tolerant Envelope Signature & Relay Protection (`DtnEnvelope.kt`)
- **Ed25519 Signature Scope:** The sender signs the 124-byte Canonical Signing Data:
  `Magic (4B) + Version (1B) + EnvelopeType (1B) + AlgorithmSuite (1B) + Flags (1B) + MessageUuid (16B) + SenderPublicKey (32B) + BlindToken (16B) + CreatedAtEpochSec (8B) + ExpiresAtEpochSec (8B) + CiphertextLength (4B) + CiphertextSha256 (32B)`.
- **Mutable Relay Metadata Separation:** Mutable hop metadata (`hopCount`, `hopLimit`, `relayTimestamp`, `relayFlags`) is explicitly excluded from the canonical signature input. Relays can increment hops and decrement hop limits without invalidating the sender's cryptographic signature.
- **Loop Mitigation:** Envelopes with `hopLimit <= 0` cannot be decremented further (`withDecrementedHop() == null`) and are immediately dropped by relays, preventing infinite mesh forwarding loops.

---

## 3. Abuse Prevention & DoS Hardening

### 3.1 IDOR Prevention on Upload Tickets
- In `backend/cmd/server/main.go` lines 385–390:
  ```go
  if req.NxId != "" && req.NxId != sess.NxID {
      http.Error(w, "Forbidden: cannot request ticket for another NX-ID", http.StatusForbidden)
      return
  }
  req.NxId = sess.NxID
  ```
  The requested `NxId` is strictly bound to the authenticated caller session token (`sess.NxID`), preventing an attacker from requesting upload tickets or generating object keys under another user's NX-ID.

### 3.2 Maximum Size Ceiling (100 MB Fallback Gate)
- Cloud fallback strictly enforces a 100 MB ceiling (`100 * 1024 * 1024` bytes) both on the client (`R2MediaFallbackServiceImpl.MAX_ALLOWED_BYTES`) and on the backend (`maxCloudBytes`).
- Attempting to upload files > 100 MB over R2 fails immediately with HTTP 400 (`Direct P2P transfer required`), protecting cloud storage budgets from abusive bulk storage dumps.

### 3.3 HTTP Request Body DOS Protection
- Both `/api/v1/media/upload-ticket` and `/api/v1/media/purge-ack` employ `http.MaxBytesReader(w, r.Body, 64*1024)` (lines 373 and 446), mitigating JSON bomb and slowloris payload injection attacks.

### 3.4 Local Flash Storage Exhaustion Mitigation (FIFO Pruning)
- In `NearbyMeshManager.kt` lines 259–264:
  When envelope storage reaches `DtnConstants.MAX_CACHE_ENVELOPE_COUNT` (500 envelopes), the node automatically triggers `envelopeDao.pruneOldest(DtnConstants.PRUNE_BATCH_SIZE)` (50 oldest envelopes).
- Verified by `ThreeDeviceMeshSimulationTests.testScenario6_FifoQueuePruningAtCapacity()`: total cache count never exceeds 500 items.

---

## 4. Credential & Privacy Leakage Prevention

### 4.1 Presigned URL Sanitization in Logs
- Presigned URLs for Cloudflare R2 contain authorization query parameters (`ticket`, `token`, `signature`, `hash`, `X-Amz-...`).
- `R2MediaFallbackServiceImpl.sanitizeUrlForLog()` sanitizes these parameters using regex redaction before passing to Android Logcat.
- Verified by `R2MediaFallbackUnitTests.testPresignedUrlCredentialRedactionInLogs()`: zero raw tickets or hashes escape into log outputs.

### 4.2 Metered Cellular Protection Behind VPNs
- By decoupling `hasWifi`, `hasCellular`, and `isMetered` from `isVpnActive`, `NetworkMonitor.kt` prevents background file transfers or backup jobs from silently consuming metered cellular data when a user has a VPN active.

---

## 5. Security Regression Verification Matrix

| Security Control | Implementation Location | Test Evidence | Verdict |
| :--- | :--- | :--- | :--- |
| **Zero Plaintext on R2** | `BlindMediaVaultEngine.kt` | `BlindMediaEncryptionUnitTests` & `R2MediaFallbackUnitTests` | **PASS** |
| **E2EE Key Segregation** | `BlindMediaVaultEngine.kt` | Media keys travel strictly in Double Ratchet envelope | **PASS** |
| **Ciphertext Tamper Rejection** | `BlindMediaVaultEngine.kt` | GCM tag verification fails on altered bytes | **PASS** |
| **DTN Ed25519 Canonical Signature** | `DtnEnvelope.kt` | `ThreeDeviceMeshSimulationTests.testScenario1` | **PASS** |
| **DTN Hop Loop Mitigation** | `DtnEnvelope.kt` | `ThreeDeviceMeshSimulationTests.testScenario7` | **PASS** |
| **Bloom Redundant Mule Suppression** | `NearbyMeshManager.kt` | `ThreeDeviceMeshSimulationTests.testScenario3` | **PASS** |
| **FIFO Storage Quota Enforcement** | `NearbyMeshManager.kt` | `ThreeDeviceMeshSimulationTests.testScenario6` | **PASS** |
| **Upload Ticket IDOR Prevention** | `backend/cmd/server/main.go` | `sess.NxID` binding & verification | **PASS** |
| **100 MB Cloud Ceiling Gate** | Client & Backend Server | `testCloudFallbackMaxCeilingEnforcement` | **PASS** |
| **Presigned URL Redaction** | `R2MediaFallbackService.kt` | `testPresignedUrlCredentialRedactionInLogs` | **PASS** |
| **Immediate Purge ACK (0 Retention)**| Client & Backend Server | `testImmediatePurgeAckSemantics` | **PASS** |
| **Concurrent VPN Metered Telemetry** | `NetworkMonitor.kt` | `NetworkClassificationUnitTests` | **PASS** |

---

## 6. Final Security Verdict

**VERDICT: PASS.** No security regressions identified. All cryptographic guarantees, IDOR protections, and abuse mitigation mechanisms operate according to specification.
