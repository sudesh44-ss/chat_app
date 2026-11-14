# NEXA — Phase 2: FCM & Cloud Fallback Implementation Plan

**Plan Type:** Step-by-Step Remediation & Integration Plan  
**Target Codebase:** `H:\chat_app` (Go Backend & Android Application)  
**Authority Hierarchy:** `1architecture.md` / `docs/*.md` / `PHASE_2_FCM_CLOUD_FALLBACK_AUDIT.md`  
**Status:** **PROPOSED — AWAITING EXPLICIT USER APPROVAL TO COMMENCE IMPLEMENTATION**

---

## 1. Executive Summary & Objective

This document defines the exact, controlled implementation plan to resolve all P0, P1, and P2 defects discovered during the Phase 2 FCM & Cloud Fallback Audit (`PHASE_2_FCM_CLOUD_FALLBACK_AUDIT.md`).

### Targeted Remediation Areas:
1. **[P0] Backend FCM OAuth2 Service Account Token Exchange:** Replace unpopulated `s.accessToken` with Google OAuth2 Service Account RS256 JWT assertion generation and automatic token caching/refresh.
2. **[P0] Android Firebase Messaging Client Integration:** Add Firebase Messaging SDK, register `NexaFirebaseMessagingService`, process OS wake-up intents, and provide graceful fallback for de-Googled / non-Play-Services environments.
3. **[P1] Cloudflare R2 S3 v4 Presigned URL Generation:** Implement real Cloudflare R2 S3 v4 presigned PUT URL generator on the backend with environment credential support and local dev mock fallback.
4. **[P1] Environment Variable Specification:** Update `.env.example` with all missing Cloudflare R2 and Firebase Service Account configuration keys.
5. **[P2] Device Token Detachment on Logout:** Ensure the Android client explicitly clears `fcm_token` on the backend before clearing local session credentials.
6. **[P2] FCM Backend Unit Test Suite:** Author `backend/internal/fcm/fcm_service_test.go` covering payload serialization, token caching, mock dispatch, and error handling.

---

## 2. Issue 1: Backend FCM Google OAuth2 Authentication (P0)

### 2.1 Problem Description
In `backend/internal/fcm/fcm_service.go`, `getAccessToken()` returns `s.accessToken`, which is never initialized or refreshed. When `FCM_PROJECT_ID` is set, all push dispatches to Google FCM v1 API fail with `HTTP 401 Unauthorized`.

### 2.2 Files to Modify / Create
- **Create:** `backend/internal/fcm/token_source.go`
- **Modify:** `backend/internal/fcm/fcm_service.go`
- **Modify:** `backend/go.mod` and `backend/go.sum` (if adding `golang.org/x/oauth2/google` or using standard crypto `crypto/rsa`, `crypto/x509`, `crypto/sha256`)

### 2.3 Exact Classes, Structs & Functions
```go
// In backend/internal/fcm/token_source.go

type ServiceAccountCredentials struct {
    Type                    string `json:"type"`
    ProjectID               string `json:"project_id"`
    PrivateKeyID            string `json:"private_key_id"`
    PrivateKey              string `json:"private_key"`
    ClientEmail             string `json:"client_email"`
    ClientID                string `json:"client_id"`
    AuthURI                 string `json:"auth_uri"`
    TokenURI                string `json:"token_uri"`
    AuthProviderX509CertURL string `json:"auth_provider_x509_cert_url"`
    ClientX509CertURL       string `json:"client_x509_cert_url"`
}

type TokenSource interface {
    GetAccessToken(ctx context.Context) (string, error)
}

type GoogleOAuthTokenSource struct {
    clientEmail string
    privateKey  *rsa.PrivateKey
    tokenURI    string
    httpClient  *http.Client
    
    mu          sync.RWMutex
    cachedToken string
    expiresAt   time.Time
}

// NewGoogleOAuthTokenSource parses JSON credentials (from file path or env JSON string)
func NewGoogleOAuthTokenSource(credentialsJSON []byte) (*GoogleOAuthTokenSource, error)

// GetAccessToken returns cached token or signs a new RS256 JWT assertion and posts to Google token endpoint
func (ts *GoogleOAuthTokenSource) GetAccessToken(ctx context.Context) (string, error)
```

In `backend/internal/fcm/fcm_service.go`:
```go
type FcmService struct {
    projectId   string
    isMock      bool
    httpClient  *http.Client
    tokenSource TokenSource
    mu          sync.RWMutex
}

// Update NewFcmService to accept or read FIREBASE_SERVICE_ACCOUNT_JSON / FIREBASE_SERVICE_ACCOUNT_PATH
// Update getAccessToken() to call tokenSource.GetAccessToken(context.Background())
```

### 2.4 Graceful Fallback Strategy
- If `FIREBASE_SERVICE_ACCOUNT_JSON` or `FIREBASE_SERVICE_ACCOUNT_PATH` is empty and `FCM_PROJECT_ID` is empty:
  - Operate in `isMock = true` mode (log simulated wake-up pings, return success).
- If `FCM_PROJECT_ID` is set but service account credentials are missing:
  - In non-production (`APP_ENV != "production"`): Log prominent warning and fallback to mock mode.
  - In production (`APP_ENV == "production"`): Fail fast on startup with descriptive error to avoid silent message drops.

### 2.5 Risk & Rollback
- **Risk:** Low. If OAuth token exchange fails, errors are captured and logged without panicking.
- **Rollback:** Revert `fcm_service.go` and remove `token_source.go`.

---

## 3. Issue 2: Android Firebase Messaging Client Integration (P0)

### 3.1 Problem Description
The Android application has internal wake-up receivers (`NexaPushWakeUpReceiver`) and manager (`NexaPushManager`), but lacks the `com.google.firebase:firebase-messaging` dependency and a `FirebaseMessagingService` implementation. Consequently, Google Play Services cannot deliver remote push wake-up intents to the app.

### 3.2 Files to Modify / Create
- **Create:** `app/src/main/java/com/nexa/app/data/remote/push/NexaFirebaseMessagingService.kt`
- **Modify:** `app/build.gradle.kts`
- **Modify:** `app/src/main/AndroidManifest.xml`
- **Modify:** `app/src/main/java/com/nexa/app/data/remote/push/NexaPushManager.kt`

### 3.3 Exact Classes, Methods & Manifest Declarations

#### A. Gradle Dependency (`app/build.gradle.kts`):
```kotlin
// In dependencies block
implementation("com.google.firebase:firebase-messaging:23.4.1")
```

#### B. Manifest Registration (`app/src/main/AndroidManifest.xml`):
```xml
<service
    android:name=".data.remote.push.NexaFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

#### C. Service Implementation (`NexaFirebaseMessagingService.kt`):
```kotlin
package com.nexa.app.data.remote.push

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NexaFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var pushManager: NexaPushManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM registration token received")
        serviceScope.launch {
            pushManager.onNewPushToken(token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val type = data["type"]

        // STRICT PRIVACY CHECK: Verify this is purely a generic WAKE_UP ping
        if (type == "WAKE_UP") {
            Log.d(TAG, "Received authorized background WAKE_UP ping. Dispatching local broadcast.")
            val intent = Intent("com.nexa.app.PUSH_WAKE_UP").apply {
                setPackage(packageName)
                putExtra("source", "fcm_remote")
                putExtra("timestamp", data["timestamp"] ?: System.currentTimeMillis().toString())
            }
            sendBroadcast(intent)
        } else {
            Log.w(TAG, "Ignored unrecognized or non-compliant push message type: $type")
        }
    }

    companion object {
        private const val TAG = "NexaFCMService"
    }
}
```

#### D. Push Manager Initialization (`NexaPushManager.kt`):
```kotlin
// Add explicit method to fetch token on startup if available:
fun initializePushToken() {
    try {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        scope.launch { onNewPushToken(token) }
                    }
                } else {
                    Log.w(TAG, "Fetching FCM token failed: ${task.exception?.message}")
                }
            }
    } catch (e: Exception) {
        Log.w(TAG, "Firebase messaging not available on this device: ${e.message}")
    }
}
```

### 3.4 Fallback Strategy for De-Googled / Non-Play-Services Devices
- Devices running GrapheneOS, CalyxOS, AOSP, or emulators without Google Play Services will throw a class resolution or runtime initialization error if Firebase assumes Play Services.
- Wrapping `FirebaseMessaging.getInstance()` in defensive `try/catch` ensures zero application crashes on de-Googled devices.
- Fallback for non-FCM devices: Application relies on foreground WebSocket connections and periodic background WorkManager sync.

### 3.5 Zero-Plaintext Rule Enforcement
- `NexaFirebaseMessagingService.onMessageReceived` strictly rejects any message where `type != "WAKE_UP"`.
- Under no circumstances will incoming push payloads be parsed for message bodies, contacts, or encryption keys.

### 3.6 Risk & Rollback
- **Risk:** Medium. Build must compile without requiring a mandatory physical `google-services.json` in local CI environments. If `google-services.json` is missing during build, Gradle should either use an empty fallback or avoid hard compilation failure.
- **Rollback:** Remove service and dependency from `app/build.gradle.kts`.

---

## 4. Issue 3: Cloudflare R2 AWS S3 v4 Presigned URL Generation (P1)

### 4.1 Problem Description
In `backend/cmd/server/main.go`, `POST /api/v1/media/upload-ticket` currently generates an unsigned template URL (`https://r2.nexa.network/transfers/...`). To support direct uploads to Cloudflare R2, the backend must generate valid S3 v4 Presigned PUT URLs when Cloudflare credentials are configured.

### 4.2 Files to Modify / Create
- **Create:** `backend/internal/media/r2_signer.go`
- **Modify:** `backend/cmd/server/main.go`
- **Modify:** `backend/internal/config/config.go` (if centralizing environment variables)

### 4.3 Exact Structs & Functions
```go
// In backend/internal/media/r2_signer.go

type R2Config struct {
    AccountID       string
    AccessKeyID     string
    SecretAccessKey string
    BucketName      string
    EndpointURL     string // e.g. https://<account_id>.r2.cloudflarestorage.com
    PublicDomain    string // e.g. https://media.nexa.network
    IsConfigured    bool
}

func NewR2ConfigFromEnv() *R2Config

// GeneratePresignedPutURL generates AWS SigV4 presigned PUT URL with 900s TTL
func (r *R2Config) GeneratePresignedPutURL(objectKey string, sizeBytes int64, ttl time.Duration) (string, error)

// GeneratePresignedGetURL generates AWS SigV4 presigned GET URL for recipient download
func (r *R2Config) GeneratePresignedGetURL(objectKey string, ttl time.Duration) (string, error)
```

### 4.4 Graceful Fallback Strategy
- If `R2_ACCOUNT_ID` or `R2_ACCESS_KEY_ID` is unset:
  - `IsConfigured` is `false`.
  - Fall back to the existing mock URL pattern (`https://r2.nexa.network/transfers/%s?ticket=%s&expires=%d`) to ensure unit tests and local development without cloud accounts continue to pass seamlessly.

### 4.5 Risk & Rollback
- **Risk:** Low. Pure addition with fallback to existing string formatting if unconfigured.
- **Rollback:** Revert `main.go` changes.

---

## 5. Issue 4: Device Token Detachment on Logout (P2)

### 5.1 Problem Description
When a user logs out in `MainChatDashboardScreen.kt`, local session data is wiped, but the backend is not notified to clear the `devices.fcm_token` column.

### 5.2 Files to Modify
- **Modify:** `app/src/main/java/com/nexa/app/data/repository/AuthRepositoryImpl.kt` (or wherever `logout` is coordinated)
- **Modify:** `app/src/main/java/com/nexa/app/ui/screens/MainChatDashboardScreen.kt`

### 5.3 Exact Changes
```kotlin
// In AuthRepositoryImpl.kt / NexaPushManager.kt
suspend fun unregisterPushToken() {
    try {
        val sessionToken = sessionManager.getActiveSessionToken()
        if (!sessionToken.isNullOrBlank()) {
            authApiService.updateFcmToken("Bearer $sessionToken", UpdateFcmTokenRequest(fcmToken = ""))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to unregister push token on logout: ${e.message}")
    }
}
```
Invoke `pushManager.unregisterPushToken()` immediately before `sessionManager.clearSession()`.

---

## 6. Issue 5: Environment Variable Specification (P1)

### 6.1 Problem Description
`.env.example` lacks Cloudflare R2 credentials and has `FIREBASE_SERVICE_ACCOUNT_JSON` listed only as future/planned without clear configuration guidance.

### 6.2 Files to Modify
- **Modify:** `.env.example`

### 6.3 Exact Variables to Add / Update
```bash
# Firebase Cloud Messaging (FCM v1 HTTP API)
FCM_PROJECT_ID=nexa-chat-prod
# Path to Google Service Account JSON key or full single-line JSON string
FIREBASE_SERVICE_ACCOUNT_PATH=/etc/nexa/service-account.json
# Or raw JSON string:
# FIREBASE_SERVICE_ACCOUNT_JSON={"type":"service_account","project_id":"..."}

# Cloudflare R2 Blind Fallback Media Storage
R2_ACCOUNT_ID=your_cloudflare_account_id
R2_ACCESS_KEY_ID=your_r2_access_key_id
R2_SECRET_ACCESS_KEY=your_r2_secret_access_key
R2_BUCKET_NAME=nexa-media-transfers
R2_PUBLIC_DOMAIN=https://media.nexa.network
```

---

## 7. Issue 6: Unit Test Specifications (P2)

### 7.1 Backend FCM Unit Tests
- **Create:** `backend/internal/fcm/fcm_service_test.go`
- **Test Cases:**
  1. `TestFcmService_MockMode`: Verify that with `FCM_PROJECT_ID=""`, `SendWakeUp` succeeds and returns nil error without network calls.
  2. `TestFcmService_PayloadZeroPlaintext`: Verify that serialized FCM message payload conforms strictly to `{ "message": { "data": { "type": "WAKE_UP", "timestamp": "..." } } }` and contains zero extraneous fields.
  3. `TestOAuthTokenSource_TokenCaching`: Verify that cached access token is reused until within 5 minutes of expiration.
  4. `TestOAuthTokenSource_InvalidCredentials`: Verify that malformed JSON credentials fail fast with a descriptive error.
  5. `TestFcmService_SendWakeUpBatch`: Verify concurrent wake-up pings dispatch safely across goroutines without race conditions (`go test -race`).

### 7.2 Backend Media R2 Unit Tests
- **Create:** `backend/internal/media/r2_signer_test.go`
- **Test Cases:**
  1. `TestR2Signer_UnconfiguredFallback`: Verify mock URL is returned when credentials are unset.
  2. `TestR2Signer_PresignedUrlFormatting`: Verify valid SigV4 query parameters (`X-Amz-Algorithm`, `X-Amz-Credential`, `X-Amz-Date`, `X-Amz-Signature`) are present when credentials are provided.

---

## 8. Step-by-Step Implementation Sequence

```
┌────────────────────────────────────────────────────────┐
│ Phase 2 Implementation Gates                           │
└────────────────────────────────────────────────────────┘
                           │
                           ▼
 [Gate 1: User Approval of Audit & Implementation Plan]
                           │
                           ▼
 [Step 1: Backend FCM Token Source & Tests]
  - Create token_source.go (RS256 JWT / OAuth2 exchange)
  - Update fcm_service.go
  - Create fcm_service_test.go
  - Run: go test -v -race ./internal/fcm/...
                           │
                           ▼
 [Step 2: Backend Cloudflare R2 Presigned Signer & Tests]
  - Create r2_signer.go & r2_signer_test.go
  - Update main.go upload-ticket handler
  - Run: go test -v ./...
                           │
                           ▼
 [Step 3: Android Firebase SDK & Service Integration]
  - Add firebase-messaging to app/build.gradle.kts
  - Register NexaFirebaseMessagingService in AndroidManifest.xml
  - Implement NexaFirebaseMessagingService.kt
  - Add unregisterPushToken on logout
  - Run: gradlew testDebugUnitTest
                           │
                           ▼
 [Step 4: Environment & Documentation]
  - Update .env.example
                           │
                           ▼
 [Gate 2: Final Verification & Test Run]
  - Execute full backend test suite: go test -count=1 ./...
  - Execute Android media and auth test suites
  - Generate Phase 2 Completion Report
```

---

## 9. Verification & Phase Completion Criteria

The Phase 2 remediation will be considered **COMPLETE** when:
1. `go test -count=1 ./...` passes 100% of all tests including new FCM and R2 tests.
2. FCM payload remains 100% verified for the E2EE zero-plaintext invariant.
3. Android unit tests compile and pass without regressions.
4. Fallbacks for unconfigured cloud credentials (mock mode) and de-Googled devices (try/catch) function without crashes.
5. All code adheres to `1architecture.md` and repository guidelines.

---

**End of Implementation Plan.**  
*Awaiting explicit user approval before touching source files.*
