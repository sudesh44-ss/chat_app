# NEXA — Complete End-to-End User Onboarding & Journey Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Android 15 Client & Render Go Backend  
**Date:** November 2026  

---

## 1. Complete Flow Step-by-Step Diagnostic

The table below traces every single step from cold application launch to the main Chat List screen:

| Step # | User Journey Stage | Android Screen & ViewModel | Backend API Called | Backend Status & Existence | Local State & Persistence | Outcome / Current Failure Point |
| :---: | :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **App Launch & Splash** | `SplashScreen.kt`<br>`SplashViewModel.kt` | None (Local check) | N/A | Checks `secureStorage.getAuthToken()` & `userDao.hasActiveSession()` | If session exists → Dashboard.<br>If new user → WelcomeScreen. |
| **2** | **Welcome & Google Sign-In** | `WelcomeScreen.kt`<br>`WelcomeViewModel.kt` | `POST /api/v1/auth/google` | Exists in `handler.go:815` | Retrieves ID token via Credential Manager | **SUCCESS** if `GOOGLE_CLIENT_ID` set in Render.<br>Returns `status: "NEW_USER"`. |
| **3** | **Profile Setup & Phone Entry** | `ProfileSetupScreen.kt`<br>`SetupViewModel.kt` | `POST /api/v1/auth/initiate-reverse-verify` | Exists in `handler.go:913` | Stores profile fields in `SetupUiState` | **SUCCESS**: Backend generates Nonce (`NX-XXXXX`) & 90s TTL. Navigates to Radar. |
| **4** | **Reverse SMS Verification** | `VerificationRadarScreen.kt`<br>`VerificationRadarViewModel.kt` | `GET /api/v1/auth/verification-status?nonce=...` | Exists in `handler.go:1022` | Android opens Google Messages (`Intent.ACTION_SENDTO`) | **CRITICAL FAILURE:** Inbound number is dummy `+919800000000`. Webhook has no secret. Polling times out after 90s (`EXPIRED`). |
| **5** | **Carrier Handshake Match** | N/A (Carrier Gateway) | `POST /api/v1/auth/inbound-sms` | Exists in `handler.go:963` | Backend maps nonce to phone in memory | **BLOCKED:** Never called in production. |
| **6** | **Profile Badge Screen** | `ProfileBadgeScreen.kt` | None | N/A | Displays minted NX-ID badge | **BLOCKED:** Blocked by Step 4 timeout. |
| **7** | **Permission Gateway** | `PermissionGatewayScreen.kt`<br>`PermissionViewModel.kt` | None | N/A | Prompts for optional Post Notifications | **BLOCKED:** Blocked by Step 4 timeout. |
| **8** | **Main Chat Dashboard** | `MainChatDashboardScreen.kt` | Connects `wss://.../ws?nx_id=...` | Exists in `main.go:88` | Queries Room DB `chatDao.getChatsFlow()` | **BLOCKED:** Blocked by Step 4 timeout. |

---

## 2. In-Depth Step Analysis

### Step 1: Application Launch
- **Implementation:** `SplashViewModel.kt` executes `delay(1200)` and checks `authRepository.isLoggedIn()`.
- **Logic:** `hasToken && hasLocalSession`.
- **Verdict:** Fully implemented and robust.

### Step 2: Google Sign-In
- **Implementation:** `WelcomeViewModel.signInWithCredentialManager(context, webClientId)`.
- **Backend API:** `POST /api/v1/auth/google`.
- **Verification:** `google_verifier.go` cryptographically verifies RS256 signatures against Google JWKS.
- **Verdict:** Fully implemented. Depends on `GOOGLE_CLIENT_ID` matching in Render.

### Step 3: Phone Number Entry & Initiation
- **Implementation:** `ProfileSetupScreen` collects Phone, Age, Gender, and Bio.
- **Backend API:** `POST /api/v1/auth/initiate-reverse-verify`.
- **Response:**
  ```json
  {
    "status": "PENDING",
    "nonce": "NX-HF3G5",
    "session_id": "7f92a1...",
    "inbound_receiver_number": "+919800000000",
    "sms_payload": "NEXA-VERIFY:NX-HF3G5:7f92a1...",
    "expires_in_seconds": 90
  }
  ```
- **Verdict:** Implemented cleanly. However, `inbound_receiver_number` defaults to dummy number if `INBOUND_SMS_RECEIVER_NUMBER` is not set on Render.

### Step 4: The Fatal Failure Point (Reverse SMS Polling)
- **Implementation:** `VerificationRadarViewModel` opens Google Messages with pre-filled SMS.
- **Behavior:** User taps send. Polling queries `GET /api/v1/auth/verification-status?nonce=NX-HF3G5` every 2.5 seconds.
- **Failure Cause:**
  1. No SMS carrier is connected to `+919800000000`.
  2. No webhook is fired to the backend.
  3. `GATEWAY_WEBHOOK_SECRET` is unset, so incoming calls return 401.
  4. Timer expires at 90s.
  5. UI enters `RadarVerificationStatus.EXPIRED`.
- **Verdict:** **THE FLOW HALTS HERE. USER CANNOT PROCEED.**

### Step 5–8: Post-Verification Stages
- If Step 4 is resolved, Steps 6, 7, and 8 have complete UI and navigation code ready.
- However, at Step 8, WebSocket authentication encounters an Ed25519 public key mismatch between `CryptoKeystoreManager` and `WebSocketManager`.
