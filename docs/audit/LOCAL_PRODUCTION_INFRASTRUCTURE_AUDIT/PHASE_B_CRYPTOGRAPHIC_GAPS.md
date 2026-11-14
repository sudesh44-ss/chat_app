# PHASE B — CRYPTOGRAPHIC GAPS & THREAT ANALYSIS

## 1. Optional Proof of Possession During Account Registration
- **Severity:** HIGH
- **Component:** `backend/internal/auth/handler.go:421-435`
- **Impact:** Public key substitution attack during account creation.
- **Description:** `handleRegister` treats `SignatureHex` as optional. If a malicious party intercepts a user's `AuthProofTicket` (e.g. from an unencrypted transport or compromised intermediary), they can register an arbitrary public key without possessing the corresponding private key.
- **Remediation:** Make `SignatureHex` mandatory on `RegisterRequest` and reject any registration attempt where `req.SignatureHex == ""`.

---

## 2. Lack of Real-Time Active WebSocket Eviction on Device Revocation
- **Severity:** MEDIUM
- **Component:** `backend/internal/connection/client.go` & `backend/internal/connection/hub.go`
- **Impact:** A revoked device can continue sending and receiving messages until WebSocket disconnection.
- **Description:** Device and session authorization checks occur strictly during the initial challenge-response handshake. Once authenticated, the connection loop does not periodically re-verify whether the device has been revoked in the database.
- **Remediation:** Introduce a heartbeat re-validation every 5 minutes or subscribe the Hub to a Redis `device_revoked` pub/sub topic that immediately calls `session.CloseWithUnauthorized("Device revoked")`.

---

## 3. JVM Heap Residuals in Cryptographic Secret Handling
- **Severity:** LOW (Inherent Runtime Limitation)
- **Component:** `app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt:158-168`
- **Impact:** Plaintext private key seed persists in uncollected JVM heap memory until GC collection.
- **Description:** While `withDecryptedPrivateKey` zeroes out the `raw` ByteArray, intermediate allocations within JCE `Cipher.doFinal()` and BouncyCastle `Ed25519PrivateKeyParameters` cannot be zeroed via standard Java/Kotlin methods.
- **Remediation:** For defense-in-depth in future phases, implement JNI/NDK native memory wrappers using `mlock()` and `explicit_bzero()` for Ed25519 signing.
