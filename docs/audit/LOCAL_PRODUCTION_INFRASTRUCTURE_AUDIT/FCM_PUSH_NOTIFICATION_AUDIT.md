# NEXA — Firebase Cloud Messaging (FCM) Production Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Background Wake-Up Service (`backend/internal/fcm/fcm_service.go`)  
**Date:** November 2026  

---

## 1. Startup Warning Inspection

### Exact Log Observed
```
[FCM] Initialized in local/test mode (FCM_PROJECT_ID not set; logging wake-ups without external HTTP calls)
```

### Source Location
- `backend/internal/fcm/fcm_service.go:28-43`

---

## 2. Technical Findings: Mock Mode vs Broken Production Mode

### A. Missing Environment Variable in Render
- `FCM_PROJECT_ID` is not configured in the Render Dashboard.
- As a result, `isMock` evaluates to `true`.
- The server logs simulated pings:
  ```
  [FCM] Simulated wake-up ping dispatched to device token: ... (Payload: {type: 'WAKE_UP'})
  ```
- No external HTTP requests are made to Firebase servers.

### B. Severe Code Flaw: Missing Service Account & OAuth2 Bearer Token
Even if a developer configures `FCM_PROJECT_ID` on Render, the FCM service **will fail immediately with HTTP 401 Unauthorized**:

1. **HTTP Request Construction (`fcm_service.go:56-84`):**
   ```go
   url := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", s.projectId)
   ...
   if token := s.getAccessToken(); token != "" {
       req.Header.Set("Authorization", "Bearer "+token)
   }
   ```
2. **Access Token Retrieval (`fcm_service.go:111-115`):**
   ```go
   func (s *FcmService) getAccessToken() string {
       s.mu.RLock()
       defer s.mu.RUnlock()
       return s.accessToken
   }
   ```
3. **The Omission:**
   In the entire Go backend, **`s.accessToken` is NEVER initialized, refreshed, or assigned**.
   There is no code importing `golang.org/x/oauth2/google` or parsing a Google Cloud Service Account JSON key.
4. **Result:** An empty `Authorization: Bearer ""` is sent to Google Cloud. Google rejects the request with `401 Unauthorized`.

---

## 3. Privacy Policy Compliance (IT Rules 2021)

Despite the missing OAuth2 token exchange, the payload design in `fcm_service.go:58-69` is **100% compliant with privacy standards**:

```json
{
  "message": {
    "token": "<device_fcm_token>",
    "data": {
      "type": "WAKE_UP",
      "timestamp": "1730900000000"
    },
    "android": {
      "priority": "HIGH"
    }
  }
}
```

- **Zero Plaintext:** Message bodies, sender names, and recipient identifiers are **NEVER sent through Google FCM servers**.
- The FCM push functions strictly as a background wake-up trigger for `P2PTransferService` or `WebSocketManager` to pull encrypted packets directly from the relay.

---

## 4. Impact on User Journey to Chat List

- **Initial Access:** **NOT BLOCKED.** Reaching and viewing the Chat List does not require FCM.
- **Background Delivery:** If the Android app is closed or backgrounded, incoming messages cannot wake up the app until FCM credentials and OAuth2 exchange are implemented.
