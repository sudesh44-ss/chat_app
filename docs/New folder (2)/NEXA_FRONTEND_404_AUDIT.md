# NEXA Frontend & Website 404 Audit Report

**Audit Date:** November 4, 2026  
**Issue Reference:** Issue 4 — Website UI 404 Errors on Render URL  
**Target URL:** `https://nexa-relay.onrender.com/`  
**Browser Error:** `404 page not found`  
**Status:** ROOT CAUSE IDENTIFIED — ARCHITECTURAL MISALIGNMENT  

---

## 1. Executive Summary

This audit investigates the reported issue where navigating to `https://nexa-relay.onrender.com/` in a desktop web browser presents a plain-text `404 page not found` error page.

**Core Finding:**  
The `chat_app` repository is **not a website** and contains **no web frontend codebase**. It is an Android client application coupled with a headless Go backend relay. The 404 response on the root domain is expected behavior for an unmapped route on a Go `http.ServeMux` API server, and does **not** indicate a failure of the backend services or database.

---

## 2. Complete Repository Codebase Inventory

A full recursive scan of the `H:\chat_app` repository confirms the following architecture:

| Directory | Technology | Purpose | Contains Web UI? |
| :--- | :--- | :--- | :--- |
| `app/` | Kotlin, Jetpack Compose, Android SDK 35/37 | Native Android mobile application | **NO** (Android UI only) |
| `backend/` | Go 1.24, Gorilla WebSocket, crypto/ed25519 | Headless REST API & WebSocket relay | **NO** (API endpoints only) |
| `database/` | SQL, Supabase PostgreSQL, RLS | Database schema & migration scripts | **NO** |
| `proto/` | Protocol Buffers (`nexa_wire.proto`) | Binary wire protocol definitions | **NO** |
| `docs/` | Markdown (`.md`) | System documentation & audit reports | **NO** |

**Conclusion:** There are no `index.html`, `package.json`, React, Vue, Next.js, or static asset directories anywhere in the repository.

---

## 3. Why the Root URL Returns 404

### 3.1 Go's Default Route Matching
In Go's standard library `http.ServeMux`, routes must be explicitly registered. 

Prior to audit remediation, `backend/cmd/server/main.go` only registered endpoints under specific subpaths:
- `/health`
- `/ws`
- `/ws/signaling`
- `/api/v1/auth/*`
- `/api/v1/relay/*`

When a browser initiates an HTTP request to `GET /`:
1. `http.ServeMux` searches for a handler matching `/`.
2. Because no catch-all root pattern was registered, Go automatically invokes `http.NotFound(w, r)`.
3. `http.NotFound` outputs the standard HTTP 404 status code with the default plain text body:
   ```
   404 page not found
   ```

### 3.2 Web Browser vs Android Client Expectations
- **Web Browser:** Expects an HTML document, CSS stylesheets, JavaScript bundles, and a favicon when requesting the root domain.
- **Android App:** Expects JSON APIs and WebSocket connections located at specific endpoints (`/api/v1/auth/otp/request`, `/ws`). The Android client never visits `/`.

---

## 4. Verification: API Subpaths vs Root Path

To prove that the backend is fully alive and functioning, compare the responses from different paths on the exact same host:

| Requested URL | HTTP Status | Response Body | Interpretation |
| :--- | :--- | :--- | :--- |
| `https://nexa-relay.onrender.com/` | `404 Not Found` | `404 page not found` | Root route not mapped |
| `https://nexa-relay.onrender.com/health` | `200 OK` | `{"status":"UP","mode":"Ephemeral-RAM-Only",...}` | Backend running successfully |
| `https://nexa-relay.onrender.com/api/v1/auth/otp/request` | `200 OK` | `{"status":"SENT",...}` | API handler running successfully |

The 404 error is strictly localized to the `/` root path.

---

## 5. Architectural Clarification & Recommendation

### 5.1 Project Architecture
NEXA is designed as a **privacy-first mobile platform**, where the user interface resides exclusively on physical Android devices. The server hosted on Render acts as a **zero-knowledge signaling and relay gateway**, deliberately decoupled from any web presentation layer.

### 5.2 Recommended Solution
To prevent confusion when users or developers open the Render URL in a browser, the Go backend should serve a formatted JSON or HTML status badge on the root route `/`:

```go
mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
    if r.URL.Path != "/" {
        http.NotFound(w, r)
        return
    }
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusOK)
    json.NewEncoder(w).Encode(map[string]interface{}{
        "service":     "NEXA Secure Relay & Identity Gateway",
        "status":      "RUNNING",
        "health":      "/health",
        "documentation": "https://github.com/sudesh44-ss/chat_app",
    })
})
```

This transforms the confusing `404 page not found` into an informative HTTP 200 service descriptor.
