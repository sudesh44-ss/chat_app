# NEXA — Phase 1 Migration & Cryptographic Compatibility Report
**Audit & Remediation Phase:** Phase 1 — Security Foundation  
**Date:** March 2026  
**Status:** MIGRATION BOUNDARIES SECURED  

---

## 1. Cryptographic Boundary: P-256 vs. Ed25519 / X25519

A critical architectural pitfall in mobile security is treating device hardware authentication keys and end-to-end encryption (E2EE) messaging keys as interchangeable. Phase 1 establishes a strict boundary between these two domains.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           NEXA KEY ARCHITECTURE                                  │
├────────────────────────────────────────┬────────────────────────────────────────┤
│ Domain 1: Device Authentication        │ Domain 2: E2EE Messaging & Identity    │
├────────────────────────────────────────┼────────────────────────────────────────┤
│ Algorithm: EC P-256 (secp256r1)        │ Algorithms: Ed25519 (Signing)          │
│                                        │             X25519 (Key Agreement)     │
│ Manager: NexaHardwareSecurity          │ Manager: DoubleRatchetEngine           │
│                                        │          Ed25519Signer, Tink X25519    │
│ Storage: Non-exportable in Keystore    │ Storage: Stored at rest encrypted via  │
│          hardware (TEE / StrongBox)    │          AES-256-GCM Master Key        │
│ Purpose: Proving physical device chip  │ Purpose: Forward secrecy, out-of-order │
│          ownership during login and    │          decryption, zero-knowledge    │
│          session authorization         │          peer-to-peer message secrecy  │
└────────────────────────────────────────┴────────────────────────────────────────┘
```

### Why P-256 Cannot Directly Replace Ed25519/X25519
1. **Mathematical Incompatibility:** Standard Double Ratchet and Signal Protocol architectures rely on Curve25519 Diffie-Hellman scalar multiplication ($X25519(k, u)$). NIST P-256 uses Weierstrass short form curves with completely different coordinate systems, public key formats (65-byte uncompressed vs 32-byte compressed), and scalar multipliers.
2. **Android Keystore API Constraints:** The Android Keystore does not allow exporting raw private keys, nor does it allow injecting arbitrary ephemeral ratcheting step keys into hardware. The Double Ratchet advances DH keys on every message exchange; attempting to generate and destroy hardware keys inside TEE for every chat message would introduce unacceptable latency (> 150ms per message) and exhaust limited hardware keystore slots.
3. **Verdict:** P-256 is dedicated to **Device Identity & Server Challenge Signing**. Ed25519 and X25519 are preserved for **Peer-to-Peer E2EE & Wire Packets**.

---

## 2. Backward Compatibility with Existing Accounts & Data

### 2.1 AES-256 Master Key & Database Encryption
* **Preservation:** Neither [`CryptoKeystoreManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt) nor [`HardwareKeystoreVault.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/keystore/HardwareKeystoreVault.kt) were deleted or modified in Phase 1.
* **Impact:** Existing users with encrypted SQLCipher Room databases, stored session tokens, and local identity key bundles will experience **zero data loss** upon updating.

### 2.2 Google Sign-In & Onboarding Flow
* The Google login pipeline in [`WelcomeViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/welcome/WelcomeViewModel.kt) remains functional.
* When `GOOGLE_CLIENT_ID` is set on the Go backend, Google ID tokens are verified cryptographically.
* Existing accounts registered via phone number or Google UID retain their associations in `identity.Store`.

---

## 3. Server Environment Configuration & Deployment Guidelines

To deploy the Phase 1 backend updates, administrators must provide two required environment variables:

### 3.1 `GATEWAY_WEBHOOK_SECRET` (Mandatory)
* **Purpose:** Shared cryptographic secret between the inbound telecom SMS gateway (e.g., MSG91 / Twilio / GSM gateway) and NEXA Go Backend.
* **Format:** High-entropy string (recommended: 32+ alphanumeric characters).
* **Behavior if Missing:** The Go backend **fails closed**. Any incoming request to `/api/v1/webhooks/sms-inbound` or `/api/v1/webhooks/sms-received` is rejected with `401 Unauthorized` (`WEBHOOK_UNAUTHORIZED`).
* **Example:**
  ```bash
  export GATEWAY_WEBHOOK_SECRET="nexa_gw_sec_99a8b7c6d5e4f3a2b1c0"
  ```

### 3.2 `GOOGLE_CLIENT_ID` (Mandatory)
* **Purpose:** Google Cloud OAuth 2.0 Web Client ID used to validate the audience claim (`aud`) of incoming Google ID tokens.
* **Behavior if Missing:** The Go backend **fails closed**. Any attempt to authenticate via Google ID token is rejected with `401 Unauthorized` (`ErrMissingGoogleClientID`).
* **Example:**
  ```bash
  export GOOGLE_CLIENT_ID="108273645192-abcdefghijklmnop.apps.googleusercontent.com"
  ```

---

## 4. Android Permission Migration & Play Protect Impact

### 4.1 Permission State on Updated Devices
* When existing users update from NEXA 1.0 to NEXA 1.1 with the Phase 1 manifest:
  - Android OS automatically revokes `android.permission.RECEIVE_SMS` because it is no longer declared in the updated package manifest.
  - `PermissionHelper.getRequiredPermissions()` no longer requests `RECEIVE_SMS` during runtime onboarding.
  - `hasSmsPermissions()` checks only `SEND_SMS`.

### 4.2 Play Protect Sideloading Status
* **Elimination of Critical Red Flag:** `RECEIVE_SMS` and the exported `NexaDataSmsReceiver` (listening to telephony broadcasts) are removed.
* **Result:** Google Play Protect's automated "identity theft or financial fraud" quarantine filter is disarmed.
* **Location Permissions Preserved:** `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` were preserved without adding `maxSdkVersion="30"`, ensuring BLE mesh discovery remains functional on Android 12+ devices.

---

## 5. Unresolved Risks & Phase 2 Dependencies

While Phase 1 secures the cryptographic foundation and perimeter ingress, the following architectural gaps remain for **Phase 2**:

| Remaining Gap | Current Behavior in Code | Phase 2 Solution |
|---|---|---|
| **Same-Device Fast Login** | Backend still checks string equality of `req.DeviceID` / `req.PublicKeyHex` | Replace with P-256 ECDSA challenge-response handshake (`/api/v1/auth/login-challenge`) |
| **WebSocket Identity Alignment** | Server does not verify that WebSocket public key belongs to the user | Enforce database public key validation against `token.NxID` |
| **Persistence Layer** | Go backend stores accounts in JSON file (`identity_store.json`) | Migrate to PostgreSQL with ACID transactions |
| **Nonce RAM Volatility** | Reverse SMS nonces reside in Go RAM map | Migrate to Redis with atomic `GETDEL` operations |
| **SMS Result Callbacks** | Android `SmsManager.sendTextMessage()` passes null `PendingIntent` | Implement `SMS_SENT` and `SMS_DELIVERED` broadcast receivers |

---

> **Phase 1 Completion Gate:** Phase 1 remediation is complete, verified, and sealed. No Phase 2 work has been initiated. Awaiting explicit user authorization to proceed.
