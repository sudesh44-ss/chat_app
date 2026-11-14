# PHASE B — FINAL SECURITY VERIFICATION REPORT

**Execution Mode:** STRICT READ-ONLY AUDIT  
**Audit Scope:** NEXA Phase B (RFC 8032 Ed25519 Identity, Android KeyStore Master Vault, WebSocket Challenge-Response Authentication, Device Authorization, Proof of Possession)  
**Classification Standard:** `VERIFIED`, `VERIFIED WITH LIMITATIONS`, `NOT VERIFIED`, `FAILED`  

---

## 1. Ed25519 Identity & Cryptographic Derivation

### 1.1 RFC 8032 Public-Key Derivation
- **Classification:** `VERIFIED`
- **Code Inspection:** `app/src/main/java/com/nexa/app/core/crypto/Ed25519Signer.kt:47-51`
- **Details:** Uses BouncyCastle's `Ed25519PrivateKeyParameters(privateKeyBytes, 0).generatePublicKey().encoded`. Conforms strictly to RFC 8032 section 5.1.5 (SHA-512 scalar multiplication against basepoint $B$).
- **Contrast with Flawed Legacy:** Verified in `PhaseBIdentityAndWebSocketTests.kt` lines 28-51. The legacy SHA-256 bit-clamping derivation is completely invalidated and does not match the true RFC 8032 public key derived from the same seed.

### 1.2 Existing Seed Preservation
- **Classification:** `VERIFIED`
- **Code Inspection:** `app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt:105-136`
- **Details:** In `getOrCreateIdentityKeyPair`, existing stored encrypted private key seeds (`encBytes`) and initialization vectors (`ivBytes`) are preserved verbatim. The seed is decrypted in memory solely to re-derive the authoritative RFC 8032 public key; the master ciphertext is never corrupted or regenerated.

### 1.3 Legacy Key Migration
- **Classification:** `VERIFIED`
- **Code Inspection:** `CryptoKeystoreManager.kt:115-130`
- **Details:** Compares `truePubKeyHex` with stored `pub`. If mismatched, persists `truePubKeyHex` to `SecureStorage` without modifying the encrypted private key seed.

### 1.4 Public-Key Registration Consistency
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/connection/client.go:197-221`
- **Details:** The Go backend matches the client's challenge response signature against the authoritative `accounts.public_key_hex` or registered `devices.public_key_hex`.

### 1.5 QR Synchronization Compatibility
- **Classification:** `VERIFIED`
- **Details:** QR synchronization transfers the public key and device pairing tokens. Public key formatting remains standard 32-byte hex (64 hex characters).

### 1.6 X25519 Diffie-Hellman Key Independence
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/identity/store.go` & `CryptoKeystoreManager.kt`
- **Details:** Separate storage fields (`dh_key_hex` and `DhPublicKeyHex`) are maintained. Ed25519 identity keys are used strictly for signing and authentication; Double Ratchet X25519 ephemeral keys remain independent.

### 1.7 P-256 Hardware Keystore Independence
- **Classification:** `VERIFIED`
- **Details:** Android KeyStore master key `nexa_master_vault_key` uses AES-256-GCM hardware key storage (TEE/StrongBox) to encrypt the Ed25519 private seed at rest, ensuring separation of concerns between storage protection and identity signing.

---

## 2. Memory Handling & Secret Zeroing

### 2.1 Inspection of `withDecryptedPrivateKey()`
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Code Inspection:** `CryptoKeystoreManager.kt:158-168`
- **Implementation:**
```kotlin
val raw = decryptWithMasterKey(identityKeyPair.iv, identityKeyPair.encryptedPrivateKey)
return try {
    block(raw)
} finally {
    Arrays.fill(raw, 0.toByte())
}
```
- **Verification Analysis:**
  - `raw` buffer is allocated, used within the lambda, and guaranteed to be zeroed via `Arrays.fill(raw, 0)` inside the `finally` block even if exceptions occur.
- **JVM Limitations & Residual Memory:**
  - JCE `Cipher.doFinal()` allocates intermediate plaintext buffers on the heap that cannot be cleared via Java API.
  - BouncyCastle's `Ed25519PrivateKeyParameters` creates an internal defensive clone of the seed (`this.data = Arrays.clone(buf, off, 32)`). That internal copy remains in the garbage-collected JVM heap until GC reclaimed.
  - Claiming zero residual memory in a JVM/Android runtime without native C/JNI memory pinning is cryptographically unrealistic; however, NEXA implements the best possible mitigation available within pure Kotlin/Java.

---

## 3. WebSocket Challenge Authentication (`client.go`)

### 3.1 32-Byte Cryptographic Challenge
- **Classification:** `VERIFIED`
- **Code Inspection:** `backend/internal/connection/client.go:68-80`
- **Details:** Uses `crypto/rand.Read` to generate 32 bytes of high-entropy cryptographic nonce.

### 3.2 Connection-Bound Storage
- **Classification:** `VERIFIED`
- **Details:** Stored on the specific `ClientSession` instance (`c.ChallengeNonce`).

### 3.3 NX-ID and Device-ID Binding
- **Classification:** `VERIFIED`
- **Code Inspection:** `client.go:138-142, 171-186`
- **Details:** Sender NX-ID in response packet must match `c.NxId`. Device ID is checked against registered account devices.

### 3.4 Challenge Expiry
- **Classification:** `VERIFIED`
- **Code Inspection:** `client.go:75, 131-135`
- **Details:** Enforces a 60-second authentication timeout. If `now > c.ChallengeExpiry`, challenge is cleared and authentication fails.

### 3.5 Atomic Single-Use Consumption
- **Classification:** `VERIFIED`
- **Code Inspection:** `client.go:224-226`
- **Details:** The challenge nonce is copied to a local variable and `c.ChallengeNonce` is immediately set to `nil` before verifying the Ed25519 signature, preventing replay within the same session.

### 3.6 Signature Payload Canonicalization
- **Classification:** `VERIFIED`
- **Code Inspection:** `client.go:228-235`
- **Details:** Verifies against canonical formats:
  - Format 1: `nonceCopy + ":<nxId>:<timestamp>"`
  - Format 2: `hex(nonceCopy) + ":<nxId>:<timestamp>"`
  - Format 3: `nonceCopy + ":<nxId>:<deviceId>:<timestamp>"`

### 3.7 Timestamp Validation & Replay Prevention
- **Classification:** `VERIFIED`
- **Details:** Timeouts enforce fresh nonces per connection attempt.

### 3.8 Unauthenticated Packet Rejection
- **Classification:** `VERIFIED`
- **Code Inspection:** `client.go:107-111`
- **Details:** If unauthenticated within 60s, connection is terminated with `CloseWithUnauthorized`.

---

## 4. Device Authorization & Fail-Closed Scenarios

| Scenario | Expected Behavior | Code Implementation | Status |
| :--- | :--- | :--- | :---: |
| **Unknown Device** | Reject connection if account has registered devices | `client.go:187-194` | **VERIFIED** |
| **Revoked Device** | Reject connection immediately | `client.go:180-185` | **VERIFIED** |
| **Cross-Account Device** | Reject impersonation attempt | `client.go:174-179` | **VERIFIED** |
| **Account with 0 Registered Devices** | Fallback to primary account public key | `client.go:197-221` | **VERIFIED** |
| **Public-Key Mismatch** | Reject challenge response | `client.go:217-220` | **VERIFIED** |
| **Active Session Revocation** | Terminate active connection immediately | *Not checked post-handshake* | **FAILED** |

### Critical Finding: Session Revocation Post-Handshake
Once a WebSocket connection completes `VerifyAuthResponse`, it is flagged as `c.Authenticated = true`. If the device or session is subsequently revoked in PostgreSQL or Redis, the active WebSocket remains connected until the TCP socket closes. There is no active session watcher.

---

## 5. Registration Proof of Possession (`handler.go`)

### 5.1 Verification Status
- **Classification:** `VERIFIED WITH LIMITATIONS`
- **Code Inspection:** `backend/internal/auth/handler.go:421-435`
- **Details:** In `handleRegister`:
```go
if req.SignatureHex != "" {
    // Verifies boundData: NEXA_REGISTER:<ticket>:<publicKeyHex>:<deviceID>
}
```
- **Security Limitation:** The check is wrapped in `if req.SignatureHex != ""`. If an attacker possesses a valid `AuthProofTicket` and submits a registration request *without* `SignatureHex`, registration is accepted without proving possession of the private key corresponding to `PublicKeyHex`.

---

## 6. Migration Safety & Access Continuity

- **Seed Preservation:** Crash-safe; no write occurs to encrypted private key files during public key recomputation.
- **Rollback:** If public key recomputation fails, `CryptoKeystoreManager` catches exceptions and falls back to existing stored strings (`CryptoKeystoreManager.kt:120-122`).
- **Access Loss:** Zero risk of user lock-out from existing private key seeds.
