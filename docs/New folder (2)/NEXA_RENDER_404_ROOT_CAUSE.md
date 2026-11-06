# NEXA Render Root URL 404 Root Cause Analysis

**Target Host:** `https://nexa-relay.onrender.com/`  
**Report Date:** November 4, 2026  
**Status:** **RESOLVED**  

---

## 1. Problem Description
When accessing the root URL `https://nexa-relay.onrender.com/` in a desktop web browser, the browser DevTools console displayed:
```text
GET https://nexa-relay.onrender.com/ 404 (Not Found)
GET https://nexa-relay.onrender.com/favicon.ico 404 (Not Found)
```
and the page rendered Go standard library's plain text:
```text
404 page not found
```

---

## 2. Router & Route Prefix Inspection

Inspection of `backend/cmd/server/main.go` and `backend/internal/auth/handler.go` revealed:

### Registered Routes:
| Route | Method | Purpose |
| :--- | :--- | :--- |
| `/health` | `GET` | Health status JSON |
| `/ws` | `GET` (WebSocket Upgrade) | Client messaging socket |
| `/ws/signaling` | `GET` (WebSocket Upgrade) | WebRTC P2P signaling |
| `/api/v1/auth/otp/request` | `POST` | Phone OTP dispatch |
| `/api/v1/auth/otp/verify` | `POST` | Phone OTP verification |
| `/api/v1/auth/register` | `POST` | Identity & device registration |
| `/api/v1/auth/link` | `POST` | Account linking |
| `/api/v1/profile` | `GET`, `PUT` | Profile management |
| `/api/v1/contacts/lookup` | `POST` | Contact discovery |
| `/api/v1/devices` | `GET` | Device list |
| `/api/v1/devices/{id}/revoke`| `POST` | Revoke device |
| `/api/v1/devices/fcm-token` | `POST` | Update FCM push token |
| `/api/v1/relay/opportunistic-dump` | `POST` | DTN opportunistic packet queue |

### Missing Routes Prior to Fix:
1. **Root Route (`/`)**: In Go's `http.ServeMux`, if `/` is not explicitly registered, requests to `/` trigger Go's default `http.NotFound(w, r)`.
2. **Favicon Route (`/favicon.ico`)**: Browsers automatically request the site icon on every URL navigation.

---

## 3. Root Cause Finding
- **Is `/` intentionally unsupported?**
  NEXA is a native Android mobile application with a headless Go relay backend. There is no web frontend (no HTML/CSS/React).
- **Does 404 mean the server is down?**
  **NO.** The backend server was 100% operational on `/health` and all `/api/v1/...` routes. The 404 on `/` was purely an unmapped endpoint in Go's `http.ServeMux`.

---

## 4. Implemented Fix
In `backend/cmd/server/main.go`:
```go
// Register Favicon handler to eliminate browser 404 warnings
http.HandleFunc("/favicon.ico", func(w http.ResponseWriter, r *http.Request) {
    w.WriteHeader(http.StatusNoContent)
})

// Register root JSON service status handler
http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
    if r.URL.Path != "/" {
        http.NotFound(w, r)
        return
    }
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(map[string]interface{}{
        "service":   "NEXA Blind-Postman Relay Server",
        "status":    "UP",
        "mode":      "Ephemeral-RAM-Only",
        "version":   "1.0",
        "endpoints": []string{"/health", "/ws", "/ws/signaling", "/api/v1/auth/otp/request", "/api/v1/auth/otp/verify"},
        "timestamp": time.Now().UnixMilli(),
    })
})
```
This cleanly eliminates the 404 error and provides structured API metadata to any browser or testing client without building an unnecessary web page.
