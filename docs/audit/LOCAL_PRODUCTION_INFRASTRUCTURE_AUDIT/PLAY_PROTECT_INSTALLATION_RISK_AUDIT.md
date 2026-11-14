# NEXA — Google Play Protect & Installation-Time Risk Security Audit
**Audit Mode:** Strict Read-Only Architecture & Security Inspection  
**Target:** Final Merged Manifest, APK Signing Architecture, Android 15 Enhanced Confirmation Mode  
**Date:** March 2026  

---

## 1. Executive Summary

During APK sideloading via Google Files on Android 15, Google Play Protect blocks NEXA with this exact dialog:
```
"App blocked to protect your device. This app can request access to sensitive data. 
This can increase the risk of identity theft or financial fraud."
```
Once Play Protect is bypassed, the system exposes an overflow option in App Info:
```
Settings → Apps → NEXA → Three-dot menu (⋮) → "Allow restricted settings"
```

### Key Findings
1. **The Exact Play Protect Quarantine Heuristic:**  
   Google Play Protect's "financial fraud and identity theft" blocking filter specifically targets sideloaded applications requesting **`RECEIVE_SMS`**, **`READ_SMS`**, **`BIND_NOTIFICATION_LISTENER_SERVICE`**, and **`BIND_ACCESSIBILITY_SERVICE`**. These four capabilities represent the primary vectors used by banking malware to intercept 2-Factor Authentication (OTP) codes.
2. **The Smoking Gun Declaration:**  
   NEXA declares **`android.permission.RECEIVE_SMS`** in conjunction with an **`exported="true"`** broadcast receiver ([`NexaDataSmsReceiver`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/sms/NexaDataSmsReceiver.kt)) protected by `android.permission.BROADCAST_SMS`. This triggers Play Protect's automated fraud heuristic immediately.
3. **`RECEIVE_SMS` is NOT Required for Reverse SMS Verification:**  
   Reverse SMS verification is strictly an **outbound** beacon protocol. It sends an outbound SMS to a telecom gateway and confirms authentication via HTTPS polling. It never receives incoming SMS. `RECEIVE_SMS` was introduced solely for an experimental port 8901 binary SMS chat feature.
4. **Does `SEND_SMS` Alone Trigger the Exact Warning?**  
   Under Google's published Play Protect real-time fraud prevention policies, `SEND_SMS` alone without `RECEIVE_SMS` or `READ_SMS` **does NOT trigger the specific "identity theft or financial fraud" installation block**. `SEND_SMS` is classified under "Toll Fraud / Premium SMS risk" (which generates standard untrusted developer warnings or prompt dialogs, but not the automated 2FA financial fraud block).
5. **No Guarantee of Zero Warnings:**  
   While removing `RECEIVE_SMS` eliminates the specific fraud block, removing a permission **cannot guarantee that Play Protect will never show any warning**. Because NEXA is sideloaded with an unrecognized signing certificate and zero Play Store reputation, Play Protect may still display a generic "Unrecognized App" warning.

---

## 2. Distinguishing Android Security Mechanisms

To avoid architectural confusion, these four distinct Android security layers must be separated:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Layer 1: Google Play Protect Installation Block                        │
│ Managed by: Google Play Services                                       │
│ Trigger: APK package inspection at install time for high-risk malware  │
│ signatures (RECEIVE_SMS, Accessibility, unknown signing certs).        │
│ Result: System dialog blocks installation completely.                  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Bypassed / Allowed by user
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Layer 2: Android 15 Restricted Settings (Enhanced Confirmation Mode)   │
│ Managed by: Android OS (AppOpsManager OP_ACCESS_RESTRICTED_SETTINGS)   │
│ Trigger: Sideloading provenance (installed via Google Files/browser).  │
│ Result: Disables special system bindings until user enters device PIN. │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ App launched
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Layer 3: Runtime Permission System                                     │
│ Managed by: Android OS PermissionController                            │
│ Trigger: First app access to dangerous permissions (SEND_SMS, etc.).   │
│ Result: Standard runtime prompt dialog (Allow / Don't allow).          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Layer 4: Special App Access                                            │
│ Managed by: Android System Settings                                    │
│ Capabilities: Notification Access, Device Admin, Accessibility.        │
│ Result: Requires manual user delegation in System Settings.            │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Complete Permission & Component Inventory (Final Merged Manifest)

Inspected from [`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`](file:///H:/chat_app/app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml):

| # | Permission / Component | Protection Level | Origin | Required by Reverse SMS? | Required by Keystore Auth? | Play Protect Sideload Risk Tier |
|---|---|---|---|---|---|---|
| 1 | `RECEIVE_SMS` | `dangerous` | App Manifest (L19) | **NO (0%)** | **NO (0%)** | **CRITICAL (Triggers Fraud Block)** |
| 2 | `NexaDataSmsReceiver` (`BROADCAST_SMS`) | Component | App Manifest (L87-98) | **NO (0%)** | **NO (0%)** | **CRITICAL (Triggers Fraud Block)** |
| 3 | `SEND_SMS` | `dangerous` | App Manifest (L18) | **YES (Automated Beacon)** | **NO** | **MEDIUM (Toll fraud heuristic)** |
| 4 | `READ_PHONE_STATE` | `dangerous` | App Manifest (L20) | **Conditionally (SIM selection)** | **NO** | **LOW-MEDIUM (Device info)** |
| 5 | `READ_CONTACTS` | `dangerous` | App Manifest (L15) | **NO (Chat feature)** | **NO** | **LOW-MEDIUM (Social privacy)** |
| 6 | `POST_NOTIFICATIONS` | `dangerous` | App Manifest (L23) | **NO** | **NO** | **NONE (Standard Android 13+)** |
| 7 | `ACCESS_FINE_LOCATION` | `dangerous` | App Manifest (L33) | **NO** | **NO** | **MEDIUM (Lacks maxSdk=30)** |
| 8 | `ACCESS_COARSE_LOCATION` | `dangerous` | App Manifest (L34) | **NO** | **NO** | **MEDIUM (Lacks maxSdk=30)** |
| 9 | `BLUETOOTH_SCAN` | `dangerous` | App Manifest (L38) | **NO** | **NO** | **NONE (`neverForLocation` set)** |
| 10 | `BLUETOOTH_ADVERTISE` | `dangerous` | App Manifest (L42) | **NO** | **NO** | **NONE (Nearby mesh)** |
| 11 | `BLUETOOTH_CONNECT` | `dangerous` | App Manifest (L45) | **NO** | **NO** | **NONE (Nearby mesh)** |
| 12 | `NEARBY_WIFI_DEVICES` | `dangerous` | App Manifest (L50) | **NO** | **NO** | **NONE (`neverForLocation` set)** |
| 13 | `CAMERA` | `dangerous` | App Manifest (L56) | **NO** | **NO** | **NONE (QR code scan)** |
| 14 | `FOREGROUND_SERVICE_DATA_SYNC` | `normal` | App Manifest (L11) | **NO** | **NO** | **NONE (P2P file sync)** |
| 15 | `RECEIVE_BOOT_COMPLETED` | `normal` | `work-runtime:2.10.0` | **NO** | **NO** | **NONE** |
| 16 | `BIND_ACCESSIBILITY_SERVICE` | Privileged | **NOT PRESENT** | **NO** | **NO** | **NONE (Clean)** |
| 17 | `BIND_NOTIFICATION_LISTENER_SERVICE` | Privileged | **NOT PRESENT** | **NO** | **NO** | **NONE (Clean)** |
| 18 | `BIND_DEVICE_ADMIN` | Privileged | **NOT PRESENT** | **NO** | **NO** | **NONE (Clean)** |
| 19 | `SYSTEM_ALERT_WINDOW` | System | **NOT PRESENT** | **NO** | **NO** | **NONE (Clean)** |
| 20 | `REQUEST_INSTALL_PACKAGES` | Dangerous | **NOT PRESENT** | **NO** | **NO** | **NONE (Clean)** |

---

## 4. Deep Investigation: `SEND_SMS` vs `RECEIVE_SMS`

### 4.1 Does `SEND_SMS` Alone Cause the Play Protect Financial Fraud Block?
* **Google Play Protect Policy:** Google's real-time fraud defense policy targets permissions capable of **intercepting one-time passwords (OTPs) and financial alerts**.
* These interception capabilities are:
  - `android.permission.RECEIVE_SMS`
  - `android.permission.READ_SMS`
  - `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`
  - `android.permission.BIND_ACCESSIBILITY_SERVICE`
* An outbound permission (`SEND_SMS`) allows an app to transmit data, but **cannot read incoming banking OTPs**. Consequently, Play Protect does not classify `SEND_SMS` alone under the "Financial Fraud & Identity Theft" auto-block category.
* **Conclusion:** `RECEIVE_SMS` is the primary culprit behind the exact warning encountered.

### 4.2 Can Reverse SMS Preserve Automated Behavior Without `RECEIVE_SMS`?
* **Yes, 100%.**
* Reverse SMS verification in [`VerificationRadarViewModel.kt:210`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt#L210) calls:
  ```kotlin
  smsManager.sendTextMessage(inbound, null, payload, null, null)
  ```
* Under Android OS telephony architecture, `sendTextMessage()` requires strictly **`android.permission.SEND_SMS`**.
* It never invokes any API requiring `RECEIVE_SMS`.
* Inbound verification is executed entirely on NEXA's Go backend when the telecom gateway receives the beacon and hits the webhook.
* Removing `RECEIVE_SMS` from `AndroidManifest.xml` leaves automated reverse SMS verification 100% functional.

---

## 5. Architectural Alternative: Zero-Permission Verification

While the automated `SmsManager` approach works with `SEND_SMS`, an optional architectural alternative is already built into NEXA: **System Composer Intent (`ACTION_SENDTO`)**.

### Comparison

| Feature Dimension | Current Automated Architecture | Optional `ACTION_SENDTO` Architecture |
|---|---|---|
| User Interaction | 1-Tap ("Verify") | 2-Taps ("Verify" → Tap "Send" in Messages app) |
| Manifest Permissions Required | `SEND_SMS` + `READ_PHONE_STATE` | **NONE (0 Permissions)** |
| Play Protect Scrutiny | Low-Medium (Sends SMS) | **ZERO (Standard Android Intent)** |
| Android 15 ECM Friction | None for SMS | **ZERO** |
| Dual-SIM Handling | Handled in NEXA UI | Handled natively by OS Messages app |
| Code Implementation | [`VerificationRadarViewModel.kt:210`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarViewModel.kt#L210) | [`VerificationRadarScreen.kt:427`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/radar/VerificationRadarScreen.kt#L427) |

---

## 6. Comprehensive Risk Assessment & Mitigation

```
┌────────────────────────────────────────────────────────────────────────┐
│ Current State: SEVERE RISK                                             │
│ Declares RECEIVE_SMS + exported NexaDataSmsReceiver (BROADCAST_SMS).   │
│ Result: Play Protect Blocks APK (Financial Fraud Quarantine).          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Remediation Step 1
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Step 1: Strip RECEIVE_SMS & NexaDataSmsReceiver                        │
│ Eliminates the 2FA interception signature. De-escalates Play Protect   │
│ from "Financial Fraud Block" to "Standard Sideload".                   │
│ Automated Reverse SMS remains fully functional with SEND_SMS.          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Remediation Step 2
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Step 2: Add maxSdkVersion="30" to Location Permissions                 │
│ Stops Android 12–15 from treating NEXA as requesting GPS Location.     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Optional Step 3
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Optional Step 3: Transition to ACTION_SENDTO (Zero Permissions)        │
│ Completely removes SEND_SMS and READ_PHONE_STATE.                      │
│ Play Protect finds zero telephony permissions.                         │
└────────────────────────────────────────────────────────────────────────┘
```

> **Important Disclosure:** Removing sensitive permissions resolves targeted heuristic triggers. However, no audit can guarantee zero Play Protect prompts for sideloaded APKs. Google Play Protect evaluates developer certificates, domain reputations, and global download volume. Apps distributed outside Google Play without established reputation will occasionally display standard "Unknown Developer" warnings.
