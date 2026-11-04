# NEXA — Authentication Architecture & Security Audit

**Audit Date:** November 4, 2026  
**Auditor:** Senior Security Architect & Authentication Specialist  
**Target:** Android Client Auth & Go Backend Authentication Subsystem  

---

## 1. Executive Summary

The NEXA application architecture specifies **Supabase Auth** as the **Single Authentication Authority**, with Google Sign-In (via Android Credential Manager) serving as the user authentication interface, and the Go backend performing authorization, profile management, and message routing.

This audit evaluates the current implementation against the locked architectural rules.

---

## 2. Component-by-Component Audit

### A. Android Google Sign-In (`WelcomeViewModel.kt`)
- **Status**: **PASS**
- **Implementation**:
  Uses AndroidX Credential Manager (`androidx.credentials.CredentialManager`) with `GetGoogleIdOption`.
  ```kotlin
  val googleIdOption = GetGoogleIdOption.Builder()
      .setFilterByAuthorizedAccounts(false)
      .setServerClientId(webClientId)
      .setAutoSelectEnabled(false)
      .build()
  ```
- **Findings**:
  - Securely prompts for Google Account selection without legacy GoogleSignInClient.
  - Returns `googleIdTokenCredential.idToken` and displays name/email.
  - Does NOT log private OAuth credentials or passwords.

### B. Supabase Auth Integration
- **Status**: **NOT IMPLEMENTED / GAP IDENTIFIED**
- **Existing Behavior**:
  Android passes the raw Google ID token to the Go backend endpoint `/api/v1/auth/google/verify`.
  The Go backend (`GoogleVerifier.go`) validates the token directly using Google public certificates (`https://www.googleapis.com/oauth2/v3/certs`) and mints a custom bearer session `nexa_sess_...`.
- **Architectural Violation**:
  Violates locked decision Section 2.1:
  - Creates two competing authentication authorities.
  - Bypasses Supabase Auth token exchange and Supabase user provisioning.
- **Required Path**:
  1. Android exchanges Google credential with Supabase Auth:
     `POST /auth/v1/token?grant_type=id_token` (provider: `google`, id_token: `...`).
  2. Supabase Auth creates/authenticates user in `auth.users` and returns Supabase access & refresh tokens.
  3. Android sends Supabase access token in `Authorization: Bearer <supabase_jwt>` to Go backend.
  4. Go backend validates the Supabase JWT (signature, issuer, expiration, audience).

### C. Go Backend JWT Verification
- **Status**: **PARTIAL**
- **Existing Behavior**:
  `backend/internal/auth/handler.go` inspects `Authorization: Bearer <sessionToken>` by checking against in-memory map `s.sessions[token]`.
- **Remediation**:
  Implement `SupabaseJwtVerifier` in Go that:
  - Validates Supabase JWT signature (HMAC secret or JWKS public keys from `https://<ref>.supabase.co/auth/v1/.well-known/jwks.json`).
  - Verifies `exp`, `iat`, `iss` (`https://<ref>.supabase.co/auth/v1`), and `aud` (`authenticated`).
  - Extracts canonical Supabase user ID `sub` (UUID).
  - Associates `sub` with the user's persistent NEXA profile and assigned `NX-XXXXXXXX`.

### D. Session Security & Secret Exposure Audit
- **Service-Role Key Audit**:
  - Inspected `app/src/` for Supabase service-role keys. Result: **0 occurrences** (PASS).
  - Service-role key is never bundled in the APK or accessible to client code.
- **Session Tokens**:
  - Tokens stored on Android in `EncryptedSharedPreferences` (`SecureStorage.kt`).
  - Memory cleanup on logout resets local session state.
