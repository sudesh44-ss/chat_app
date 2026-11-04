# NEXA — Google Play Protect & Sideload Installation Investigation Report

**Date:** 2026-11-04  
**Author:** Google Play Release & Android Security Auditor  
**Scope:** APK / AAB Sideloading Behavior, Play Protect Warning Root Cause, Remediation  
**Status:** COMPLETED — ROOT CAUSES IDENTIFIED & PRODUCTION RESOLUTION DEFINED  

---

## 1. Issue Description

When sideloading release APKs directly onto Android devices via USB (`adb install`) or via browser/file manager download, Google Play Protect occasionally triggers a warning dialog:
> *"Unrecognized app developer. Google Play Protect doesn't recognize the developer of this app. Apps from unknown developers can sometimes be unsafe."* or *"Blocked by Play Protect"*.

---

## 2. Root Cause Analysis

Play Protect evaluates three distinct risk vectors during on-device installation:

### A. Developer Certificate & Certificate Reputation (P0 Factor)
- **Root Cause:** A newly generated self-signed keystore (`release.keystore` or debug keystore) has **zero telemetry reputation** in Google's cloud reputation database.
- **Mechanism:** Play Protect queries Google cloud servers with SHA-256 digests of the APK and signing certificate. If the certificate fingerprint has never been published on Google Play or verified through a recognized Play Console developer identity, Google assigns it the baseline "Unknown Developer" classification.
- **Production Truth:** Once the App Bundle (`app-release.aab`) is uploaded to Google Play Console and distributed via Google Play Store (or Internal Testing / Closed Beta track), Google re-signs the delivered APKs with the trusted **Google Play App Signing Key**. Play Protect will never display the "Unknown Developer" warning for apps installed via the Play Store or registered in internal app sharing.

### B. High-Risk Android Permissions
NEXA's `AndroidManifest.xml` includes high-sensitivity system permissions:
1. `android.permission.SEND_SMS` & `android.permission.RECEIVE_SMS`:
   - Monitored aggressively by Play Protect due to historically malicious fleeceware/premium SMS trojans.
   - Play Protect automatically increases heuristic inspection score when these permissions are present in sideloaded APKs.
2. `android.permission.FOREGROUND_SERVICE_DATA_SYNC`:
   - Android 14+ requires `FOREGROUND_SERVICE_DATA_SYNC` declaration and user notification for direct P2P background file transfers.

### C. Absence of Google Play Data Safety Association
- Sideloaded APKs lack the Google Play Console metadata declaration certifying that user data is end-to-end encrypted and not collected or sold.

---

## 3. Play Store Approval & Release Remediation Steps

### Step 1: Google Play Console Permission Declaration
When submitting to the Google Play Store, submit the standard **SMS/Call Log Permissions Declaration Form**:
- **Designated Use Case:** Direct Peer-to-Peer Device Communication / Encrypted Fallback Messaging (Port 8901).
- **Core Functionality Justification:** NEXA is an offline-capable, local-first privacy application that transmits E2EE packets over port 8901 without telecom internet connectivity.
- **Non-Telephony Telephony Exemption:** Packets never enter standard inbox `content://sms/inbox`; zero user SMS messages are read or altered.

### Step 2: Play App Signing with Production AAB
- Upload `app-release.aab` directly to Google Play Console.
- Ensure Google Play App Signing is enabled with an RSA-4096 or ECDSA (NIST P-256) upload key.

### Step 3: Google Play Protect Developer Verification
For enterprise or direct-download sideloading outside Google Play:
1. Register the signing certificate SHA-256 with [Google Play Protect Appeal / Whitelist Portal](https://support.google.com/googleplay/android-developer/contact/protectappeals).
2. Submit the clean virus/malware inspection report proving zero adware/fleeceware binaries.
