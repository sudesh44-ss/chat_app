# NEXA — Render Deployment Risks & Prevention Guide

---

## Top 5 Deployment Pitfalls & How They Are Resolved

### 1. Root Directory Left Blank (Most Common Error)
* **Risk:** The repository root has Android files (`app/`, `build.gradle.kts`, `gradle/`). The Go backend and `go.mod` reside inside `backend/`.
* **Failure if ignored:** Render will fail with: `go: go.mod file not found in current directory or any parent directory`.
* **Fix:** In the Render form, **Root Directory MUST be set to `backend`**.

### 2. Dockerfile Path Mismatch
* **Risk:** If Language is set to `Docker` and Root Directory is `backend`, setting Dockerfile Path to `backend/Dockerfile` will fail with `file not found` because the path is relative to the Root Directory.
* **Fix:** When Root Directory is `backend`, Dockerfile Path must be simply `Dockerfile` or `./Dockerfile`.

### 3. Region Latency (Oregon vs. Singapore)
* **Risk:** Oregon (US West) adds ~240ms round-trip latency to users in India, degrading real-time WebSocket messaging and P2P WebRTC handshake speed.
* **Fix:** Select **Singapore (Southeast Asia)** in the Region dropdown for 40–60ms latency.

### 4. Health Check Path Missing or Incorrect
* **Risk:** Render's default health check tries to query `/`. The root path `/` is not an explicit HTTP endpoint in `cmd/server/main.go`, which could cause Render to report an unhealthy service or fail zero-downtime deployment.
* **Fix:** In **Health Check Path**, enter: `/health` (defined in `cmd/server/main.go:142`).

### 5. Render Free Tier Sleep Behavior
* **Risk:** On the Free plan, Render spins down web services after 15 minutes of inactivity. When a mobile user opens the app after an hour, the first request takes ~30 to 50 seconds to respond while the container wakes up.
* **Handling:** In the Android client, [WebSocketManager.kt](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt) contains exponential backoff and retry logic. For production 24/7 reliability, upgrade to Render's $7/mo Starter plan.
