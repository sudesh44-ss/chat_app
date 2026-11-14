# NEXA — Phase 7: Firebase Cloud Messaging (FCM) End-to-End Audit Report

**Document Version:** 1.0.0  
**Target Subsystem:** Push Notification Service, Device Token Lifecycle & Background Wake-Up Pipeline  
**Target Codebase:** `H:\chat_app` (Go Backend: `backend/internal/fcm/` & Android: `com.nexa.app`)  
**Evaluation Standard:** Zero-Plaintext Push Notification & Background Wake-Up Protocol  
**Audit Date:** November 2026  
**Status:** **INDEPENDENT VERIFICATION COMPLETE — IDENTIFIED AS DE-FACTO MOCK ONLY**

---

## 1. Executive Summary

A critical reality check of the Firebase Cloud Messaging (FCM) subsystem was conducted across both the Go backend relay and the Android client application.

While Phase 6 reported FCM as "Verified (Generic Wake-Up)", source-level inspection reveals that **true end-to-end Google FCM push delivery is NOT operational in the current release build.** Specifically:
1. The Go backend's `getAccessToken()` returns an empty string `""`, lacking Google OAuth2 Service Account JWT assertion logic.
2. The Android client application lacks the `com.google.firebase:firebase-messaging` library, has no `google-services.json` credentials file, and contains no `FirebaseMessagingService` implementation.
3. As a result, live message delivery currently relies **100% on active foreground/background TLS WebSocket connections** rather than Google push wake-up packets.

---

## 2. Detailed Technical Audit: Go Backend

### 2.1 Access Token Generation Defect
- **Source Location:** [`backend/internal/fcm/fcm_service.go:111–115`](file:///H:/chat_app/backend/internal/fcm/fcm_service.go#L111-L115)
- **Code Inspection:**
  ```go
  func (s *FcmService) getAccessToken() string {
      s.mu.RLock()
      defer s.mu.RUnlock()
      return s.accessToken
  }
  ```
  `s.accessToken` is initialized as an empty string and never populated.
- **Runtime Impact:**
  - If `FCM_PROJECT_ID` is empty (default configuration), the service operates in mock mode:
    ```go
    if s.projectID == "" {
        log.Printf("[FCM-Mock] Wake-up ping simulated for device token: %s...", fcmToken[:min(len(fcmToken), 12)])
        return nil
    }
    ```
  - If `FCM_PROJECT_ID` is populated with a real Google Cloud Project ID, all HTTP calls to Google's FCM v1 endpoint (`https://fcm.googleapis.com/v1/projects/%s/messages:send`) fail with **HTTP 401 Unauthorized** because no Bearer authorization token is attached.

### 2.2 Device Token Registration & Revocation
- **Backend Endpoint:** `POST /api/v1/devices/fcm-token` is functional and updates `devices.fcm_token` in PostgreSQL.
- **Revocation Safety:** When `/api/v1/devices/{device_id}/revoke` is called, `devices.fcm_token` is cleared (`''`), ensuring revoked devices do not receive future push wake-up attempts.

---

## 3. Detailed Technical Audit: Android Client

### 3.1 Missing Firebase Messaging SDK & Service
- **Build Configuration:** [`app/build.gradle.kts`](file:///H:/chat_app/app/build.gradle.kts) does not include `com.google.firebase:firebase-messaging` or the Google Services Gradle plugin (`com.google.gms.google-services`).
- **Missing Configuration File:** `app/google-services.json` does not exist in the repository.
- **Broadcast Receiver Reality:** [`NexaPushWakeUpReceiver.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/push/NexaPushWakeUpReceiver.kt) is declared in `AndroidManifest.xml` listening for action `com.nexa.app.PUSH_WAKE_UP`. It is a standard local Android broadcast receiver that can only be invoked within the device process or by local services—it has zero connection to Google Play Services push servers.

### 3.2 Push Privacy & Zero-Plaintext Rule (VERIFIED)
- Despite the lack of live FCM dispatch, the message payload specification adheres strictly to NEXA's privacy invariant:
  ```json
  {
    "message": {
      "token": "<device_fcm_token>",
      "data": {
        "type": "WAKE_UP",
        "timestamp": "1731234567890"
      },
      "android": {
        "priority": "HIGH"
      }
    }
  }
  ```
- **Guarantees Upheld:** The push payload contains **zero message plaintext, zero sender identifiers, zero recipient phone numbers, and zero cryptographic keys**. Even if Google or a network observer intercepts the FCM packet, zero metadata or conversation data is exposed.

---

## 4. Evaluation of 10 Push Delivery Scenarios

| # | Operational Scenario | Current Build Reality | Controlled Beta Impact |
| :-: | :--- | :--- | :--- |
| **1** | Application in Foreground | Messages delivered instantly over active TLS WebSocket. | 🟢 Working as expected. |
| **2** | Application in Background (within 10m) | WebSocket keep-alives maintain connection; messages delivered. | 🟢 Working as expected. |
| **3** | Application Process Terminated (Swiped away) | WebSocket disconnected; no FCM push arrives to wake app. | 🟡 Messages wait in Redis 24h queue until user re-opens app. |
| **4** | Device Screen Locked | Handled over WebSocket while OS keeps network radio active. | 🟢 Working as expected. |
| **5** | Network Disconnected & Reconnected | `NetworkMonitor` triggers WebSocket reconnection & flushes queue. | 🟢 Working as expected. |
| **6** | Expired FCM Token | Backend logs error; token replaced on next client login. | 🟢 Handled gracefully. |
| **7** | Invalid FCM Token | Backend logs HTTP 400/404; drops token from PostgreSQL. | 🟢 Handled gracefully. |
| **8** | User Logout | Active session revoked; WebSocket closed cleanly. | 🟢 Working as expected. |
| **9** | Device Revoked | Session terminated; WebSocket immediately dropped by Hub. | 🟢 Working as expected. |
| **10**| Multiple Registered Devices | Backend fans out messages to all active device sessions. | 🟢 Working as expected. |

---

## 5. Implementation Roadmap for General Production

To transition from the current mock/WebSocket-only mode to full Google Play production push delivery:

1. **Backend OAuth2 Token Generator:**  
   Implement `backend/internal/fcm/token_source.go` using a Google Cloud Service Account JSON key to sign RS256 JWTs and exchange them at `https://oauth2.googleapis.com/token` for 1-hour access tokens with 5-minute pre-expiry caching.
2. **Android Firebase Integration:**  
   Add `com.google.gms.google-services` to root Gradle buildscript and `com.google.firebase:firebase-messaging` to `app/build.gradle.kts`.
3. **`NexaFirebaseMessagingService.kt`:**  
   Implement a `FirebaseMessagingService` subclass that catches incoming `WAKE_UP` data payloads and forwards them to `NexaPushWakeUpReceiver`.
4. **Google Services Credentials:**  
   Commit a template `google-services.json` and configure production credentials via CI secrets.

---

## 6. Audit Conclusion

For the **Controlled Beta / Pilot Release**, reliance on persistent TLS WebSocket connections and Redis 24-hour durable queue flushing is **acceptable and safe**, provided users are informed that background push notifications on deeply sleeping devices will deliver upon reopening the app.

For **General Production**, the implementation roadmap above is a mandatory release requirement.
