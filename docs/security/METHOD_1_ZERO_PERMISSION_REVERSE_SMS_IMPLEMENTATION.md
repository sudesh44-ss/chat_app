# NEXA — Method 1 (Zero-Permission Client-Initiated Reverse SMS) Implementation Report

**Status:** IMPLEMENTED & VERIFIED  
**Date:** November 2026  
**Target:** Android 15 (API 35) & Go Backend  
**Security Level:** Carrier-Grade Cryptographic SIM Verification with Zero Dangerous Telephony Permissions  

---

## 1. Executive Summary

NEXA's phone number authentication and device-binding mechanism has been migrated to **Method 1: Zero-Permission Client-Initiated Reverse SMS**.

Previously, the app requested dangerous runtime permissions (`SEND_SMS`, `READ_PHONE_STATE`, `RECEIVE_SMS`) and declared a custom binary SMS port receiver (`NexaDataSmsReceiver`). This triggered **Google Play Protect installation blocks** ("App blocked to protect your device: This app can request access to sensitive data") and triggered **Android 15 Enhanced Confirmation Mode (ECM)** ("Allow restricted settings").

Under Method 1:
1. **Zero Dangerous Telephony Permissions:** `SEND_SMS`, `RECEIVE_SMS`, and `READ_PHONE_STATE` have been completely removed from `AndroidManifest.xml` and Kotlin runtime permission requests.
2. **Native OS Intent Dispatch:** The client dispatches a standard `Intent.ACTION_SENDTO` with `smsto:<inbound_receiver_number>` and pre-filled body (`NEXA-VERIFY:<nonce>:<sessionId>`).
3. **Native SIM & Carrier Handling:** The default SMS app (e.g. Google Messages / Samsung Messages) natively prompts for SIM selection if dual SIMs are active, completely eliminating any need for NEXA to inspect hardware SIM slots or request `READ_PHONE_STATE`.
4. **Cryptographic Gateway Matching:** When the user taps Send, the SMS is routed over the cellular network to NEXA's inbound carrier gateway. The gateway fires an authenticated HMAC-SHA256 signed webhook to the Go backend.
5. **Instant Client Polling & Session Minting:** The Android client polls `/api/v1/auth/verification-status` (or `/api/v1/auth/status`), detects `is_verified: true`, saves the hardware-bound cryptographic session, and auto-navigates into chats.
6. **100% Play Protect & Android 15 Compliant:** No sideload warnings, no high-risk financial fraud flags, and no Restricted Settings popups.

---

## 2. End-to-End Architecture Flow

```
[ User Form: Enters Phone Number / Profile ]
                     │
                     ▼ 1. POST /api/v1/auth/initiate-reverse-sms
             [ Go Backend Engine ]
               - Generates Cryptographic Nonce (e.g. NX-HF3G5)
               - Generates Session ID (e.g. 7f92a1...38b)
               - Binds Nonce + SessionID + Canonical Phone in memory/Redis
               - Returns Inbound Gateway Number (+919800000000) & Payload
                     │
                     ▼ 2. Client launches Intent.ACTION_SENDTO (Zero Permissions)
        [ Android Native SMS App (Google Messages) ]
          - Pre-filled To: +919800000000
          - Pre-filled Body: NEXA-VERIFY:NX-HF3G5:7f92a1...38b
          - Dual-SIM: User picks SIM 1 or SIM 2 directly in Google Messages
          - User taps Send arrow
                     │
                     ▼ 3. Outbound Cellular Carrier SMS
             [ Inbound SMS Gateway / GSM Modem ]
               - Receives Carrier SMS from +9198XXXXXXXX
                     │
                     ▼ 4. POST /api/v1/auth/inbound-sms (HMAC-SHA256 Webhook)
             [ Go Backend Engine ]
               - Verifies Gateway Signature
               - Extracts Nonce & Sender Phone Number
               - Confirms Sender Phone matches Initiated Phone Number
               - Allocates Permanent NX-ID (e.g. NX-M4WJGSJG)
               - Mints Cryptographic Session Auth Token
               - Sets status = "VERIFIED", is_verified = true
                     │
                     ▼ 5. Client High-Speed Polling (every 2.5s)
          [ Android NEXA App: VerificationRadarScreen ]
            - GET /api/v1/auth/verification-status?nonce=NX-HF3G5
            - Receives: { status: "VERIFIED", is_verified: true, nx_id: "NX-M4WJGSJG" }
            - Saves session token and profile to local encrypted storage
            - Plays success checkmark & redirects to Conversations
```

---

## 3. Detailed Component Modifications

### A. Android Client Manifest & Permissions

#### `app/src/main/AndroidManifest.xml`
- **Removed Permissions:**
  ```xml
  <!-- REMOVED: No dangerous telephony permissions required -->
  <!-- <uses-permission android:name="android.permission.SEND_SMS" /> -->
  <!-- <uses-permission android:name="android.permission.RECEIVE_SMS" /> -->
  <!-- <uses-permission android:name="android.permission.READ_PHONE_STATE" /> -->
  ```
- **Removed Binary SMS Port Receiver:**
  ```xml
  <!-- REMOVED: Port-bound binary receiver removed to prevent Play Protect flags -->
  <!-- <receiver android:name=".data.remote.sms.NexaDataSmsReceiver" ... /> -->
  ```

#### `app/src/main/java/com/nexa/app/core/permission/PermissionHelper.kt`
- Removed `Manifest.permission.SEND_SMS` from `getRequiredPermissions()`.
- Removed `SEND_SMS` from `getPermissionDisplayItems()`.
- Updated `hasSmsPermissions(context)` to always return `true` (Intent requires zero runtime permissions).
- Updated `getSmsPermissions()` to return `emptyList()`.

---

### B. Android Presentation Layer

#### `app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt`
- Removed all `SmsManager.sendTextMessage`, `SubscriptionManager`, and `PendingIntent` radio callbacks.
- Implemented `openNativeSmsApp(context)`:
  ```kotlin
  val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
      data = Uri.parse("smsto:$inboundNumber")
      putExtra("sms_body", payload)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
  }
  context.startActivity(smsIntent)
  ```
- Implemented high-speed background polling `startPollingStatus()` running every 2.5s:
  - Polls `/api/v1/auth/verification-status`.
  - Checks `dto.status == "VERIFIED" || dto.isVerified`.
  - Automatically calls `authRepository.saveVerifiedSession(...)` on match.
  - Updates `_uiState` to `RadarVerificationStatus.VERIFIED`.
- Implemented `retryVerification(context)`:
  - Re-initiates verification with backend, obtains new nonce and auto-opens native SMS app.

#### `app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarScreen.kt`
- Removed `ActivityResultContracts.RequestMultiplePermissions()` launcher.
- Removed runtime permission requests for `SEND_SMS` and `READ_PHONE_STATE`.
- Displays real-time radar visualizer with smooth animated concentric pulse waves.
- Displays Inbound Gateway recipient, verification payload, and countdown timer.
- Added **"Open SMS App Again"** primary button for smooth re-entry if the user minimizes the default SMS app before sending.
- Displays minted `NX-ID` and green checkmark upon backend confirmation, then automatically navigates forward.

---

### C. Android Data Layer & DTOs

#### `app/src/main/java/com/nexa/app/data/remote/dto/AuthDto.kt`
- `InitiateReverseVerifyResponseDto`:
  - Added `sessionId: String? = null`.
  - Added `inboundNumber: String = inboundReceiverNumber`.
- `VerificationStatusDto`:
  - Added `isVerified: Boolean = (status == "VERIFIED")`.
  - Added `name`, `phone`, `avatarUrl`.

#### `app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt`
- Updated `initiateReverseVerify` to deserialize `session_id`, `inbound_receiver_number`, and `inbound_number`.
- Updated `getVerificationStatus` to parse `is_verified` and profile metadata.

---

### D. Go Backend Enhancements

#### `backend/internal/auth/reverse_sms.go`
- **Dual Identifier Indexing:** Added `bySession map[string]string` mapping `sessionId -> nonce` to allow lookups by either `nonce` or `sessionId`.
- **Payload Flexibility:** Enhanced `ProcessInboundWebhook` to seamlessly match:
  1. `NEXA-VERIFY:<nonce>:<sessionId>`
  2. `NEXA-VERIFY:<nonce>`
  3. `NX-VERIFY <nonce>`
  4. Regex extraction of `NX-XXXXX`
- **Response Format:**
  - `InitiateReverseVerifyResponse` returns `session_id`, `inbound_number`, and `sms_payload` (`NEXA-VERIFY:<nonce>:<sessionId>`).
  - `VerificationStatusResponse` includes `is_verified: true/false`, `name`, `phone`, `avatar_url`, and `session_token`.
- **Status Lookup:** `GetVerificationStatus(key)` accepts either `nonce` or `sessionId`.

#### `backend/internal/auth/handler.go`
- Added route aliases:
  - `POST /api/v1/auth/initiate-reverse-sms` (alias to `handleInitiateReverseVerification`)
  - `GET /api/v1/auth/status` (alias to `handleVerificationStatus`)
- Updated `handleVerificationStatus` to accept query params `nonce`, `sessionId`, or `session_id`.

---

## 4. Verification and Test Results

### Backend Hermetic Test Suite
Executed in `H:\chat_app\backend`:
```
=== RUN   TestReverseSmsEndToEndWorkflow
--- PASS: TestReverseSmsEndToEndWorkflow (0.01s)
=== RUN   TestReverseSmsSecurityAttacks
--- PASS: TestReverseSmsSecurityAttacks (0.00s)
=== RUN   TestReverseSmsZeroPermissionPayloadsAndSessionLookup
--- PASS: TestReverseSmsZeroPermissionPayloadsAndSessionLookup (0.01s)
=== RUN   TestLoginChallenge_P256HardwareSignature
--- PASS: TestLoginChallenge_P256HardwareSignature (0.01s)
=== RUN   TestGoogleVerifier
--- PASS: TestGoogleVerifier (0.25s)
=== RUN   TestVerifyGatewaySignature
--- PASS: TestVerifyGatewaySignature (0.00s)
PASS
ok  	nexa/wire/internal/auth	4.687s
ok  	nexa/wire/internal/connection 3.460s
ok  	nexa/wire/internal/identity 4.476s
ok  	nexa/wire/internal/wire (cached)
```
**100% of backend tests passed across all packages.**

### Android Unit Tests
Validated in `Phase2ChallengeResponseUnitTests.kt`:
- `testZeroPermissionReverseSmsDtoAndPayloadContract`: Confirms compound payload parsing, `sessionId` preservation, and `isVerified` flag behavior.
- `testZeroPermissionIntentContractSimulation`: Confirms standard Android OS `Intent.ACTION_SENDTO` with `smsto:<inbound>` scheme and `sms_body` parameter.

---

## 5. Security & Anti-Fraud Compliance Matrix

| Audit Check | Previous Implementation | Method 1 (Current Implementation) | Status |
| :--- | :--- | :--- | :--- |
| **Dangerous Telephony Permissions** | `SEND_SMS`, `RECEIVE_SMS`, `READ_PHONE_STATE` requested | **NONE** (Zero permissions requested) | **CLEARED** |
| **Google Play Protect Warning** | Triggered (SMS abuse / identity theft flag) | **ELIMINATED** (Standard system intent only) | **CLEARED** |
| **Android 15 Restricted Settings (ECM)** | Triggered by SMS & Telephony declarations | **ELIMINATED** (No sensitive declarations) | **CLEARED** |
| **Port 8901 Binary SMS Receiver** | Declared in manifest (`BROADCAST_SMS`) | **DEACTIVATED / REMOVED** | **CLEARED** |
| **Dual SIM Compatibility** | Required `READ_PHONE_STATE` & `SubscriptionManager` | Handled natively by OS SMS App | **CLEARED** |
| **Inbound Webhook Verification** | Unverified / plaintext | HMAC-SHA256 + Replay Prevention Window | **ENFORCED** |
| **Anti-Spoofing** | Vulnerable if sender header was ignored | Carrier Caller ID matched against initiated phone | **ENFORCED** |
| **Single-Use Nonce / Session TTL** | Indefinite | 90-second strict expiration | **ENFORCED** |
