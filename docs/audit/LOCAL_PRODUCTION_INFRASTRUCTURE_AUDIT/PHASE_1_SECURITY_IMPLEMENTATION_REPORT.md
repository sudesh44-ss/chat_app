# NEXA — Phase 1 Security Remediation Implementation Report
**Audit & Remediation Phase:** Phase 1 — Security Foundation  
**Date:** March 2026  
**Status:** COMPLETED & VERIFIED  

---

## 1. Executive Summary

Phase 1 Security Remediation establishes the fundamental cryptographic and perimeter defenses of NEXA without disrupting existing user identity, local database storage, or end-to-end messaging encryption (E2EE).

### Summary of Completed Objectives:
1. **Dedicated Android Keystore EC P-256 Keypair:** Implemented [`NexaHardwareSecurity`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/keystore/NexaHardwareSecurity.kt) inside the Android Keystore. Private keys are non-exportable, stay within the device hardware boundary (TEE / StrongBox), and are used exclusively for device identity and challenge signing via `SHA256withECDSA`.
2. **E2EE Messaging Identity Protection:** Kept existing Double Ratchet, Ed25519, X25519, and AES database wrapping keys intact. P-256 is strictly quarantined to device authentication.
3. **Fail-Closed Reverse SMS Webhook Authentication:** Implemented [`VerifyGatewaySignature`](file:///H:/chat_app/backend/internal/auth/webhook_auth.go) enforcing HMAC-SHA256 verification against the exact raw HTTP request body with constant-time comparison (`hmac.Equal`), timestamp validation, and fail-closed behavior when `GATEWAY_WEBHOOK_SECRET` is unset.
4. **Fail-Closed Google OAuth Verification:** Refactored [`GoogleVerifier`](file:///H:/chat_app/backend/internal/auth/google_verifier.go) to strictly verify RS256 cryptographic signatures against Google JWKS public keys. Bypasses and mock paths have been eliminated; if `GOOGLE_CLIENT_ID` is unconfigured, authentication fails closed immediately.
5. **Targeted Android Manifest Cleanup:** Safely removed `android.permission.RECEIVE_SMS` and the exported `NexaDataSmsReceiver` declaration from [`AndroidManifest.xml`](file:///H:/chat_app/app/src/main/AndroidManifest.xml), eliminating the Google Play Protect financial fraud trigger while preserving `SEND_SMS` and location permissions.

---

## 2. Task 1: Android Keystore P-256 Signing Key

### 2.1 Original Vulnerability & Root Cause
* **Vulnerability:** The application claimed to use hardware-backed authentication, but [`CryptoKeystoreManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt) only generated an AES symmetric key in Keystore to encrypt raw software byte arrays. Identity private keys were decrypted into plaintext memory (`ByteArray(32)`) whenever needed. Furthermore, public key derivation was mathematically broken (`SHA-256` hash clamping instead of scalar basepoint multiplication).
* **Root Cause:** Conflating symmetric data-at-rest encryption with hardware-bound asymmetric identity.

### 2.2 Implementation Details
* **New File Created:** [`app/src/main/java/com/nexa/app/core/keystore/NexaHardwareSecurity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/keystore/NexaHardwareSecurity.kt)
* **Architecture:**
  - Key Alias: `nexa_device_identity_key`
  - Algorithm: `KeyProperties.KEY_ALGORITHM_EC` (`secp256r1` / NIST P-256)
  - Purpose: `PURPOSE_SIGN or PURPOSE_VERIFY`
  - Digest: `DIGEST_SHA256`
  - Signature Algorithm: `SHA256withECDSA`
  - Hardware Isolation: Enforces non-exportable private key inside Android Keystore.
  - StrongBox Auto-Detection: On Android 9+ (API 28+), inspects `PackageManager.FEATURE_STRONGBOX_KEYSTORE`. If supported, attempts `setIsStrongBoxBacked(true)` with graceful fallback to standard TEE.
  - Hardware Boundary Inspection: Uses `KeyFactory` and `KeyInfo` to query whether the key is `STRONGBOX`, `TEE`, or `SOFTWARE` without making false security claims.
  - Public Key Export: Exports standard X.509 SubjectPublicKeyInfo DER encoded bytes via `certificate.publicKey.encoded`.
  - Challenge Signing: `signChallenge(challengeNonce: ByteArray): ByteArray` signs nonces directly in hardware.
* **Separation of Concerns:**
  - `NexaHardwareSecurity` manages P-256 hardware device authentication.
  - `CryptoKeystoreManager` and `HardwareKeystoreVault` remain completely untouched to preserve AES master keys for database encryption and existing Double Ratchet states.

---

## 3. Task 2: Reverse SMS Webhook Authentication

### 3.1 Original Vulnerability & Root Cause
* **Vulnerability:** Endpoints `/api/v1/webhooks/sms-inbound` and `/api/v1/webhooks/sms-received` in [`backend/internal/auth/handler.go`](file:///H:/chat_app/backend/internal/auth/handler.go) accepted unauthenticated POST requests. Anyone knowing a victim's phone number and active nonce could forge an inbound SMS webhook via `curl`, bypassing physical SIM verification.
* **Root Cause:** Missing ingress authentication middleware and absence of signature validation.

### 3.2 Implementation Details
* **New File Created:** [`backend/internal/auth/webhook_auth.go`](file:///H:/chat_app/backend/internal/auth/webhook_auth.go)
* **Modified File:** [`backend/internal/auth/handler.go`](file:///H:/chat_app/backend/internal/auth/handler.go)
* **Architecture:**
  - Reads the entire raw request body via `io.ReadAll(r.Body)` before parsing.
  - Loads secret from `GATEWAY_WEBHOOK_SECRET`.
  - **Fail-Closed:** If `GATEWAY_WEBHOOK_SECRET` is unset or empty, requests are rejected with `401 Unauthorized` (`ErrMissingWebhookSecret`).
  - **Header Resolution:** Inspects `X-Gateway-Signature`, `X-Signature`, or `X-Hub-Signature-256`.
  - **HMAC-SHA256 Calculation:** Computes `hmac.New(sha256.New, secret).Write(rawBody)`.
  - **Timing-Attack Resistance:** Enforces constant-time comparison via `hmac.Equal()`.
  - **Replay Protection:** Inspects `X-Gateway-Timestamp` / `X-Timestamp`. Rejects requests older than 5 minutes or skewed into the future by > 60 seconds.
  - **Safe Parsing:** Decodes JSON or form-urlencoded parameters only from the validated raw body.

---

## 4. Task 3: Google OAuth Fail-Closed Verification

### 4.1 Original Vulnerability & Root Cause
* **Vulnerability:** [`backend/internal/auth/google_verifier.go`](file:///H:/chat_app/backend/internal/auth/google_verifier.go) skipped audience and RS256 signature verification if `GOOGLE_CLIENT_ID` was empty. Furthermore, `verifySignature()` contained placeholder code returning `nil` without verifying the RSA signature against Google's public keys.
* **Root Cause:** Incomplete developer stub that accepted unverified JWT claims.

### 4.2 Implementation Details
* **Modified File:** [`backend/internal/auth/google_verifier.go`](file:///H:/chat_app/backend/internal/auth/google_verifier.go)
* **Architecture:**
  - **Fail-Closed on Unset Config:** If `v.clientID == ""`, `VerifyIdToken` rejects with `ErrMissingGoogleClientID`.
  - **Strict Algorithm Check:** Enforces `header.Algorithm == "RS256"`.
  - **Mandatory Signature Verification:** Every token signature is verified using `rsa.VerifyPKCS1v15(pubKey, crypto.SHA256, hashed, sigBytes)`.
  - **JWKS Key Refresh:** Dynamically fetches and caches Google public RSA certs from `https://www.googleapis.com/oauth2/v3/certs` with a 6-hour TTL.
  - **Deterministic Testing API:** Added `SetClientIDForTesting()` and `SetKeysForTesting()` to enable hermetic unit testing without network dependencies.

---

## 5. Task 4: Android Manifest Audit and Cleanup

### 5.1 Original Vulnerability & Root Cause
* **Vulnerability:** `AndroidManifest.xml` declared `RECEIVE_SMS` and registered an exported `NexaDataSmsReceiver` protected by `BROADCAST_SMS`. This triggered Google Play Protect's "identity theft or financial fraud" sideloading quarantine block on Android 15.
* **Root Cause:** Legacy port 8901 binary SMS chat feature that was non-functional on consumer carrier networks.

### 5.2 Implementation Details
* **Modified File:** [`app/src/main/AndroidManifest.xml`](file:///H:/chat_app/app/src/main/AndroidManifest.xml)
  - Removed `<uses-permission android:name="android.permission.RECEIVE_SMS" />`.
  - Removed `<receiver android:name=".data.remote.sms.NexaDataSmsReceiver" ... />`.
  - Preserved `SEND_SMS` (required for automated reverse verification beacon).
  - Preserved `READ_PHONE_STATE` (required for dual-SIM slot selection).
  - Preserved all location permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) without adding restrictive `maxSdkVersion="30"`, ensuring Bluetooth discovery operates unimpeded on Android 12+.
* **Modified File:** [`app/src/main/java/com/nexa/app/core/permission/PermissionHelper.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/permission/PermissionHelper.kt)
  - Removed `RECEIVE_SMS` from `getRequiredPermissions()` and `getSmsPermissions()`.
  - Updated `hasSmsPermissions()` to check only `SEND_SMS`.
  - Updated permission display UI copy.
* **Modified File:** [`app/src/main/java/com/nexa/app/data/remote/sms/NexaDataSmsReceiver.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/sms/NexaDataSmsReceiver.kt)
  - Marked with `@Deprecated` and documented deactivation reasons.

---

## 6. Files Changed in Phase 1

| File Path | Type | Action | Description |
|---|---|---|---|
| `app/src/main/java/com/nexa/app/core/keystore/NexaHardwareSecurity.kt` | Kotlin | **Created** | EC P-256 hardware Keystore manager with StrongBox/TEE detection and challenge signing. |
| `app/src/test/java/com/nexa/app/NexaHardwareSecurityUnitTests.kt` | Kotlin Test | **Created** | Unit test suite for P-256 key generation, DER export, and ECDSA signature verification. |
| `backend/internal/auth/webhook_auth.go` | Go | **Created** | Fail-closed HMAC-SHA256 webhook signature validator with replay prevention. |
| `backend/internal/auth/webhook_auth_test.go` | Go Test | **Created** | Comprehensive unit tests for webhook signature validation. |
| `backend/internal/auth/handler.go` | Go | **Modified** | Integrated `VerifyGatewaySignature` on raw request body in `handleInboundSmsWebhook`. |
| `backend/internal/auth/google_verifier.go` | Go | **Modified** | Enforced fail-closed `GOOGLE_CLIENT_ID` check and mandatory RS256 signature verification. |
| `backend/internal/auth/google_verifier_test.go` | Go Test | **Created** | Complete unit test suite for Google OAuth verification. |
| `app/src/main/AndroidManifest.xml` | XML | **Modified** | Removed `RECEIVE_SMS` permission and `NexaDataSmsReceiver` declaration. |
| `app/src/main/java/com/nexa/app/core/permission/PermissionHelper.kt` | Kotlin | **Modified** | Removed `RECEIVE_SMS` from runtime permission request lists. |
| `app/src/main/java/com/nexa/app/data/remote/sms/NexaDataSmsReceiver.kt` | Kotlin | **Modified** | Added deprecation notice and deactivation documentation. |

---

> **Phase 1 Verification Complete:** All tests compiled and passed cleanly. Zero breaking changes introduced to existing database or messaging layers.
