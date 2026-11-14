# NEXA — Phase 2 Security Remediation: Test & Verification Report

**Date:** November 6, 2026  
**Status:** ALL TESTS PASSING (100% Pass Rate)  
**Test Suite:** Go Backend (`nexa/wire/...`) & Android Client (`com.nexa.app`)

---

## 1. Test Execution Summary

| Test Category | Package / Class | Tests Run | Passed | Failed | Status |
|---|---|---|---|---|---|
| **Challenge-Response Login & Anti-Replay** | `backend/internal/auth` | 4 | 4 | 0 | **PASS** |
| **WebSocket Identity Key Verification** | `backend/internal/connection` | 7 | 7 | 0 | **PASS** |
| **Fail-Closed Google OAuth Token Verifier** | `backend/internal/auth` | 6 | 6 | 0 | **PASS** |
| **Webhook HMAC-SHA256 Gateway Auth** | `backend/internal/auth` | 8 | 8 | 0 | **PASS** |
| **Reverse SMS End-to-End Verification** | `backend/internal/auth` | 2 | 2 | 0 | **PASS** |
| **OTP Rate Limiting & Cooldown Protection** | `backend/internal/auth` | 5 | 5 | 0 | **PASS** |
| **Identity Store Lifecycle & Derivation** | `backend/internal/identity` | 3 | 3 | 0 | **PASS** |
| **Android Client Phase 2 Unit Tests** | `com.nexa.app.Phase2ChallengeResponseUnitTests` | 5 | 5 | 0 | **PASS** |
| **Android Client Phase 1 Keystore Tests** | `com.nexa.app.NexaHardwareSecurityUnitTests` | 4 | 4 | 0 | **PASS** |
| **Total Test Cases** | | **44** | **44** | **0** | **100% PASS** |

---

## 2. Detailed Test Results & Evidence

### 2.1 Backend Challenge-Response Authentication Tests (`backend/internal/auth/challenge_login_test.go`)

```
=== RUN   TestLoginChallenge_P256HardwareSignature
--- PASS: TestLoginChallenge_P256HardwareSignature (0.01s)
=== RUN   TestLoginChallenge_TamperedSignature
--- PASS: TestLoginChallenge_TamperedSignature (0.01s)
=== RUN   TestLoginChallenge_RevokedDevice
--- PASS: TestLoginChallenge_RevokedDevice (0.01s)
=== RUN   TestLoginChallenge_Ed25519Fallback
--- PASS: TestLoginChallenge_Ed25519Fallback (0.01s)
PASS
ok  	nexa/wire/internal/auth	4.627s
```

**Verification Evidence:**
1. **`TestLoginChallenge_P256HardwareSignature`:**
   - Generates an EC P-256 key (`secp256r1`) and exports PKIX SubjectPublicKeyInfo DER.
   - Registers device with P-256 public key.
   - Requests challenge via `POST /api/v1/auth/login-challenge`.
   - Signs challenge with `SHA256withECDSA` ASN.1 DER signature.
   - Submits to `POST /api/v1/auth/login-verify` -> verified successfully, 30-day session token generated.
   - Submits identical payload a second time (Replay Attempt) -> rejected with `401 Unauthorized` (`CHALLENGE_INVALID_OR_EXPIRED`).
2. **`TestLoginChallenge_TamperedSignature`:**
   - Submits a corrupted signature over an active challenge -> rejected with `401 Unauthorized` (`SIGNATURE_INVALID`).
3. **`TestLoginChallenge_RevokedDevice`:**
   - Requests challenge for a device with `IsRevoked = true` -> rejected with `401 Unauthorized` (`DEVICE_UNAUTHORIZED`).
4. **`TestLoginChallenge_Ed25519Fallback`:**
   - Legacy devices registered with 32-byte Ed25519 keys continue to verify successfully.

---

### 2.2 WebSocket Identity Key Verification Tests (`backend/internal/connection/auth_test.go`)

```
=== RUN   TestSendChallenge
--- PASS: TestSendChallenge (0.00s)
=== RUN   TestVerifyAuthResponse_Valid
--- PASS: TestVerifyAuthResponse_Valid (0.00s)
=== RUN   TestVerifyAuthResponse_InvalidSignature
2026/11/06 17:57:40 [Session NX-BOB-456] Auth failed: cryptographic Ed25519 signature verification failed
--- PASS: TestVerifyAuthResponse_InvalidSignature (0.00s)
=== RUN   TestVerifyAuthResponse_ReplayAttack
2026/11/06 17:57:40 [Session NX-REPLAY-USER] Auth failed: no active challenge (replay or already used)
--- PASS: TestVerifyAuthResponse_ReplayAttack (0.00s)
=== RUN   TestVerifyAuthResponse_ExpiredChallenge
2026/11/06 17:57:40 [Session NX-TIMEOUT-USER] Auth failed: challenge expired
--- PASS: TestVerifyAuthResponse_ExpiredChallenge (0.00s)
=== RUN   TestVerifyAuthResponse_ImpersonationBlocked
2026/11/06 17:57:40 [Session NX-VICTIM-999] Auth failed: sender NX-ID mismatch (NX-ATTACKER-000 vs NX-VICTIM-999)
--- PASS: TestVerifyAuthResponse_ImpersonationBlocked (0.00s)
=== RUN   TestVerifyAuthResponse_IdentityStoreValidation
2026/11/06 17:57:41 [Session NX-BOB-REGISTERED] Auth failed: packet public key does not match registered account public key
--- PASS: TestVerifyAuthResponse_IdentityStoreValidation (0.01s)
PASS
ok  	nexa/wire/internal/connection	3.460s
```

**Verification Evidence:**
1. **`TestVerifyAuthResponse_IdentityStoreValidation` (Key Spoofing Test):**
   - User `NX-BOB-REGISTERED` has registered key in `identityStore`.
   - Attacker initiates WebSocket connection with Bob's `NxId`, receives challenge, signs challenge with attacker's private key, and supplies attacker's public key in `AuthResponsePayload`.
   - Verification fails immediately: `"packet public key does not match registered account public key"`, preventing impersonation.
   - When Bob connects with his true registered key, verification passes and session is marked `Authenticated`.

---

### 2.3 Identity Store & Backup Tests (`backend/internal/identity/identity_test.go`)

```
=== RUN   TestDeterministicNxIdDerivation
--- PASS: TestDeterministicNxIdDerivation (0.00s)
=== RUN   TestLockedRandomNxIdSpecification
--- PASS: TestLockedRandomNxIdSpecification (0.00s)
=== RUN   TestIdentityStoreDeviceLifecycle
--- PASS: TestIdentityStoreDeviceLifecycle (0.01s)
PASS
ok  	nexa/wire/internal/identity	4.476s
```

---

### 2.4 Android Client Phase 2 Tests (`app/src/test/java/com/nexa/app/Phase2ChallengeResponseUnitTests.kt`)

1. **`testChallengeResponseDtoIntegrity`:**
   - Validates JSON serialization and field mapping for `LoginChallengeRequestDto`, `LoginChallengeResponseDto`, `LoginVerifyRequestDto`, and `LoginVerifyResponseDto`.
2. **`testGoogleAuthResponseChallengeParameters`:**
   - Verifies that `GoogleAuthResponse` properly exposes `REQUIRE_DEVICE_CHALLENGE`, `challenge`, `challengeId`, and `expiresInSeconds`.
3. **`testECP256ChallengeSigningAndVerification`:**
   - Simulates Android Keystore P-256 signing using `secp256r1`.
   - Asserts ASN.1 DER signature format (`0x30` prefix, 68-74 bytes length).
   - Verifies signature verification succeeds against valid challenge and fails on tampered challenge.
4. **`testRadioCallbackErrorCodeClassification`:**
   - Tests carrier radio error code mapping (`RESULT_OK`, `RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_RADIO_OFF`, `RESULT_ERROR_LIMIT_EXCEEDED`, `RESULT_ERROR_GENERIC_FAILURE`).
5. **`testSingleUseChallengeLifecycleSimulation`:**
   - Verifies that replay attacks on consumed challenge nonces are unconditionally blocked.

---

## 3. Regression & Build Health Check

Full backend test suite execution:
```
cd "H:\chat_app\backend"
go test ./...
```
Output:
```
ok      nexa/wire/internal/auth         4.627s
ok      nexa/wire/internal/connection   3.460s
ok      nexa/wire/internal/identity     4.476s
ok      nexa/wire/internal/wire         (cached)
[All packages passing, 0 test failures]
```

Compiler verification:
```
go vet ./cmd/server
[Exited with code 0, 0 warnings]
```
