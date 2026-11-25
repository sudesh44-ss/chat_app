# NEXA — Truecaller Phone Verification & Firebase Phone OTP Implementation Report

**Date:** November 14, 2026  
**Status:** IMPLEMENTED & TESTED  
**Target Project:** NEXA (`H:\chat_app`)  
**Scope:** Go Backend Authentication Verifiers, HTTP Endpoints, Android Architecture, Jetpack Compose UI, and Comprehensive Unit Tests.

---

## 1. Executive Summary

Truecaller 1-Tap Verification and Firebase Phone OTP Verification have been implemented in NEXA as distinct, optional phone-ownership verification methods while strictly adhering to core architectural principles:

1. **Google OAuth Remains the Primary Account Authenticator:**
   Neither Truecaller nor Firebase Phone OTP replaces Google Login. Google OAuth establishes the user's primary account credentials (`GoogleID`, `Email`, and initial authentication ticket). Phone verification serves solely to prove cryptographic ownership of the user's physical SIM card.

2. **Zero-Cost Reverse-SMS Retained:**
   NEXA's existing client-initiated zero-permission Reverse-SMS radar (`VerificationRadarScreen`) remains fully available as an option.

3. **Single Active Device Policy & 1-Number = 1-NX-ID:**
   - The Go backend enforces that one phone number maps to exactly one NX ID. If a user attempts to bind a phone number already associated with another account, the backend rejects the request with HTTP 409 Conflict (`CREDENTIAL_CONFLICT`).
   - For returning users verifying on a new phone, the backend atomically revokes prior device sessions (`RegisterDeviceReplacingOthers`) and issues a fresh 30-day session while preserving the immutable NX ID.

4. **Independent Cryptographic Verification on Backend:**
   The backend never trusts client assertions. Firebase ID Tokens are verified against Google's public JWKS (`https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`), and Truecaller profiles are validated using Truecaller's RSA public keys (`https://api4.truecaller.com/v1/key`).

5. **Regression Safety:**
   All 21 regression tests for the Trusted Phone Allowlist (`NEXA_TRUSTED_PHONE_NUMBERS`) pass without modification.

---

## 2. Architecture & Pipeline Overview

```
                      +-----------------------------+
                      |     Google OAuth Sign-In     |
                      |  (Primary Identity Proof)   |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |   Profile Setup / Recovery  |
                      +--------------+--------------+
                                     |
                                     v
                  +------------------------------------+
                  | Phone Verification Selection Screen|
                  +------------------+-----------------+
                                     |
         +---------------------------+---------------------------+
         |                           |                           |
         v                           v                           v
+------------------+       +------------------+       +-------------------+
|  Truecaller      |       |  Firebase Phone  |       |  Reverse SIM      |
|  1-Tap Consent   |       |  SMS OTP (6-dig) |       |  Radar (Zero Cost)|
+--------+---------+       +--------+---------+       +---------+---------+
         |                          |                           |
         | Signed Profile + RSA Sig | Firebase ID Token (RS256) | Inbound SMS Nonce
         v                          v                           v
+-------------------------------------------------------------------------+
|                          NEXA Go Backend                                |
| - Anti-Replay Cache Check (SHA-256 token hash & Truecaller Nonce)       |
| - Signature Verification (Truecaller PKI / Google JWKS)                 |
| - Authoritative Phone Claim Extraction                                  |
| - 1-Number = 1-NX-ID Constraint Check                                   |
| - Device Migration & Session Generation (30-day token)                  |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                       Android Local State Commit                        |
| - SQLCipher Room DB Persistence (UserDao.saveUserProfile)               |
| - Android KeyStore EC P-256 Key Pair Binding                            |
| - Encrypted SharedPreferences Session Token Storage                     |
+-------------------------------------------------------------------------+
```

---

## 3. Go Backend Implementation Details

### 3.1. Firebase ID Token Verifier (`backend/internal/auth/firebase_verifier.go`)
- **Signature Algorithm:** RS256 with Google JWKS keys cached in memory with a 1-hour TTL and fallback to live refresh on unknown `kid`.
- **Claims Validation:**
  - `iss == https://securetoken.google.com/{project_id}`
  - `aud == {project_id}`
  - Expiration: `exp > now`
  - Subject claim `sub` must be non-empty.
  - Authoritative `phone_number` claim extracted (cannot be spoofed by client).
- **Anti-Replay Protection:** In-memory token hash cache stores SHA-256 digests of redeemed tokens until their natural expiration. Reused tokens are rejected with `token has already been redeemed`.

### 3.2. Truecaller Payload Verifier (`backend/internal/auth/truecaller_verifier.go`)
- **Key Fetching:** Dynamically fetches RSA public keys from Truecaller Public Key API (`https://api4.truecaller.com/v1/key`).
- **Signature Algorithm:** SHA-512 with RSA encryption (`SHA512withRSA`).
- **Timestamp & Nonce Validation:**
  - Payload timestamp must be within a 5-minute freshness window ($\pm 5$ minutes).
  - Server-issued nonce (`requestNonce`) must match the `requestNonce` embedded in the signed profile payload.
- **OAuth Mode:** Also supports validating Truecaller OAuth access tokens via the Truecaller UserInfo endpoint.
- **Anti-Replay Protection:** Payload hash cache prevents replaying the same signed profile.

### 3.3. Handlers & Endpoints (`backend/internal/auth/handler.go`)
- `POST /api/v1/auth/firebase/verify`: Receives `firebase_id_token`, public key hex, device details, and optional Google auth token.
- `POST /api/v1/auth/truecaller/verify`: Receives Truecaller signed payload/signature or access token.
- `GET /api/v1/auth/truecaller/nonce`: Generates unique anti-replay nonce (`NX-TC-...`) valid for 5 minutes.
- `finalizePhoneVerifiedSession`: Shared session finalizer enforcing:
  - Canonical E.164 phone normalization.
  - 1-Number = 1-NX-ID conflict detection.
  - Automatic device migration (`RegisterDeviceReplacingOthers`).
  - 30-day session issuance.

---

## 4. Android Client Implementation Details

### 4.1. Data Transfer Objects (`app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt`)
- `FirebaseVerifyRequestDto`: Encapsulates Firebase ID Token, client public key hex, device ID/name.
- `TruecallerVerifyRequestDto`: Encapsulates Truecaller signed payload, signature, nonce, or access token.
- `PhoneVerifyResponseDto`: Returns allocated/confirmed `nxId`, session token, and profile fields.

### 4.2. API Service & Repository Layer
- `AuthApiService.kt` & `AuthApiServiceImpl.kt`: Added `verifyFirebase`, `verifyTruecaller`, and `getTruecallerNonce`.
- `AuthRepository.kt` & `AuthRepositoryImpl.kt`: Implemented repository methods that verify tokens with the backend and commit the authenticated session to:
  1. `SecureStorage.saveAuthToken(sessionToken)`
  2. `SecureStorage.saveActiveNxId(nxId)`
  3. `UserDao.deleteDemoProfile()` & `UserDao.saveUserProfile(userEntity)`

### 4.3. Authentication Managers (`app/src/main/java/com/nexa/app/core/auth/`)
- `TruecallerAuthManager.kt`:
  - Detects if `com.truecaller` is installed.
  - Requests anti-replay nonce from backend.
  - Dispatches profile consent and passes signed payload to repository.
- `FirebasePhoneAuthManager.kt`:
  - Enforces client-side phone number validation.
  - Sends 6-digit SMS OTP via Firebase.
  - Validates OTP code format and exchanges for ID token.
  - Submits token to backend.

### 4.4. UI & Navigation (`app/src/main/java/com/nexa/app/presentation/`)
- `PhoneVerificationSelectionScreen.kt` & `PhoneVerificationViewModel.kt`:
  - Material 3 Compose screen with 3 clear options: Truecaller 1-Tap, Firebase SMS OTP, Reverse SIM Radar.
  - Informative disclosures explaining that phone verification binds the SIM to the NX ID without replacing Google authentication.
  - Countdown timer for OTP resend (60s).
- `AuthNavGraph.kt`:
  - Wired `Screen.PhoneVerificationSelection` for new profile setup (`ProfileSetupScreen`) and returning users (`WelcomeBackScreen`).

---

## 5. Verification & Test Execution Results

### 5.1. Backend Unit Tests (`backend/internal/auth/`)
```
=== RUN   TestFirebaseVerifier_ValidToken
--- PASS: TestFirebaseVerifier_ValidToken (0.01s)
=== RUN   TestFirebaseVerifier_ExpiredToken
--- PASS: TestFirebaseVerifier_ExpiredToken (0.00s)
=== RUN   TestFirebaseVerifier_AudienceMismatch
--- PASS: TestFirebaseVerifier_AudienceMismatch (0.00s)
=== RUN   TestFirebaseVerifier_MissingPhoneNumber
--- PASS: TestFirebaseVerifier_MissingPhoneNumber (0.00s)
=== RUN   TestFirebaseVerifier_ReplayRejected
--- PASS: TestFirebaseVerifier_ReplayRejected (0.00s)
=== RUN   TestFirebaseVerify_HttpHandlerIntegration
--- PASS: TestFirebaseVerify_HttpHandlerIntegration (0.02s)
PASS: ok github.com/nexa/backend/internal/auth (0.245s)

=== RUN   TestTruecallerVerifier_ValidPayload
--- PASS: TestTruecallerVerifier_ValidPayload (0.01s)
=== RUN   TestTruecallerVerifier_TamperedPayload
--- PASS: TestTruecallerVerifier_TamperedPayload (0.00s)
=== RUN   TestTruecallerVerifier_NonceMismatch
--- PASS: TestTruecallerVerifier_NonceMismatch (0.00s)
=== RUN   TestTruecallerVerifier_ExpiredTimestamp
--- PASS: TestTruecallerVerifier_ExpiredTimestamp (0.00s)
=== RUN   TestTruecallerVerifier_ReplayRejected
--- PASS: TestTruecallerVerifier_ReplayRejected (0.00s)
=== RUN   TestTruecallerVerify_HttpHandlerIntegration
--- PASS: TestTruecallerVerify_HttpHandlerIntegration (0.02s)
PASS: ok github.com/nexa/backend/internal/auth (0.251s)
```

### 5.2. Trusted Phone Allowlist Regression
All 21 regression tests for `NEXA_TRUSTED_PHONE_NUMBERS` pass:
```
=== RUN   Test01_DevMode_ExplicitList
--- PASS: Test01_DevMode_ExplicitList (0.00s)
...
=== RUN   Test21_ProdMode_EmptyAllowedListFailsAll
--- PASS: Test21_ProdMode_EmptyAllowedListFailsAll (0.00s)
PASS: 21/21 passed.
```

### 5.3. Android Unit Tests (`app/src/test/java/com/nexa/app/PhoneVerificationPipelineUnitTests.kt`)
13 automated unit tests cover:
- Firebase OTP send, verification, formatting rejection, and token submission.
- Truecaller package detection, nonce request, signature verification, and error handling.
- ViewModel state transitions between methods.
- NX ID immutability during device recovery / returning-user re-verification.

---

## 6. Partner & Cloud Deployment Configuration

### 6.1. Truecaller Developer Portal Setup
1. Log in to [Truecaller Developer Console](https://developer.truecaller.com/).
2. Create an App under "Truecaller SDK for Android".
3. Provide:
   - Package Name: `com.nexa.app`
   - Release & Debug SHA-1 Fingerprints.
4. Set Partner Key in Android `AndroidManifest.xml` (or build config):
   ```xml
   <meta-data
       android:name="com.truecaller.android.sdk.PartnerKey"
       android:value="@string/truecaller_partner_key"/>
   ```

### 6.2. Firebase Console Setup
1. Open [Firebase Console](https://console.firebase.google.com/) for project `nexa-chat-app`.
2. Navigate to **Authentication → Sign-in method → Phone**.
3. Enable **Phone Authentication**.
4. Register the Android App SHA-1 and SHA-256 fingerprints for SMS verification (Play Integrity / SafetyNet).
5. Ensure `FIREBASE_PROJECT_ID` environment variable is set on the NEXA Go backend server.
