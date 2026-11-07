# NEXA — Google Authentication Architecture & Security Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Google Credential Manager (Android) & `internal/auth/google_verifier.go` (Backend)  
**Date:** November 2026  

---

## 1. Overview & Components

Google Authentication in NEXA is designed as a single-tap identity foundation providing verified email, name, and profile picture, linked to an Android Keystore hardware key and phone number.

### Android Client
- **SDK:** Android Credential Manager (`androidx.credentials.CredentialManager`).
- **Credential Option:** `GetGoogleIdOption` with `serverClientId = context.getString(R.string.google_web_client_id)`.
- **Client ID in strings.xml:** `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com`.
- **Handling File:** `app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt:72-108`.

### Go Backend
- **Endpoint:** `POST /api/v1/auth/google` (and `/api/v1/auth/google/verify`).
- **Verifier:** `backend/internal/auth/google_verifier.go`.
- **Claims Extracted:** `sub` (Google Subject ID), `email`, `name`, `picture`, `exp`, `iss`, `aud`.

---

## 2. Cryptographic Security Assessment

`backend/internal/auth/google_verifier.go` implements a zero-trust, fail-closed verification pipeline:

1. **Algorithm Check:** Strictly enforces `alg == "RS256"`. Rejects symmetric algorithms (`HS256`, `none`).
2. **JWKS Fetching & Caching:** Dynamically queries Google's public JWKS certificates at `https://www.googleapis.com/oauth2/v3/certs`. Caches RSA public keys in memory with a 6-hour TTL (`google_verifier.go:193`).
3. **Fail-Closed Client ID:** If `GOOGLE_CLIENT_ID` is unset on the server, `VerifyIdToken` rejects all tokens with `ErrMissingGoogleClientID` (line 84).
4. **Audience Validation:** Enforces `claims.Audience == clientID`. Tokens minted for different applications are rejected.
5. **Issuer Validation:** Validates `claims.Issuer` against `accounts.google.com` or `https://accounts.google.com`.
6. **Expiration:** Compares `claims.ExpiresAt` against `time.Now().Unix()`.
7. **Signature Verification:** Computes SHA-256 digest of `header.payload` and verifies against Google's RSA public key using PKCS#1 v1.5.

---

## 3. Account Linkage & Duplicate Handling

In `backend/internal/auth/handler.go:847`:
```go
existingAcc, isExisting := h.store.GetAccountByGoogleID(claims.Subject)
```

1. **New User Path:** If `isExisting == false`, returns:
   ```json
   {
       "status": "NEW_USER",
       "google_uid": claims.Subject,
       "email": claims.Email,
       "prefill_name": claims.Name,
       "prefill_avatar": claims.Picture
   }
   ```
   The client routes to `ProfileSetupScreen`, preserving the Google UID and prefilling Name/Avatar.
2. **Existing User (Same Physical Device):**
   Checks `dev.NxID == existingAcc.NxID` or `req.PublicKeyHex == existingAcc.PublicKeyHex`. If matched, generates a hardware challenge (`REQUIRE_DEVICE_CHALLENGE`).
3. **Existing User (New Device / Re-install):**
   Returns `REQUIRE_SIM_VERIFICATION`. The user must re-verify phone ownership via Reverse SMS before the account can be transferred to the new device.
4. **Duplicate Prevention:**
   The `identity.Store` indexes accounts by `byGoogle[googleID] -> nxID` and `byPhone[phone] -> nxID`. An individual Google account cannot create multiple distinct NEXA identities.

---

## 4. Findings & Production Vulnerabilities

1. **Render Configuration Dependency:** If `GOOGLE_CLIENT_ID` is not explicitly added to Render's environment variables, Google login is 100% blocked for all users with `401 Unauthorized`.
2. **Ephemeral Disk Wipe:** Because `identity.Store` writes to `data/identity_store.json` on Render's ephemeral disk, whenever Render redeploys or restarts, `h.store.GetAccountByGoogleID(claims.Subject)` returns `false` for previously registered users, forcing them through registration again.
