# NEXA — Render Web Service Form Configuration

**Repository:** `https://github.com/sudesh44-ss/chat_app`  
**Target Service:** NEXA Go Blind-Postman Relay & WebRTC Signaling Server  

---

## 1. Primary Recommendation: Native Go Runtime (Recommended)

> [!TIP]
> **Why Native Go is better than Docker on Render Free Tier:**
> - **Build Speed:** Native Go compiles in ~10 seconds. Docker takes 2–3 minutes.
> - **Memory Efficiency:** Free tier gives 512 MB RAM. Docker builds can occasionally hit memory limits when compiling 7 binaries. Native Go builds just the relay server (`./cmd/server`) cleanly with ~15 MB RAM usage.
> - **Simplicity:** Zero container configuration errors.

### Exact Values to Enter (Native Go):

| Render Field | Exact Value to Enter | Reason / Technical Detail |
| :--- | :--- | :--- |
| **Name** | `nexa-relay` (or `chat-app`) | Unique name for your service URL (`https://nexa-relay.onrender.com`). |
| **Project** | `chat_app` (or default) | Groups your services together on Render. |
| **Environment** | `Production` | Standard production environment tag. |
| **Language** | **Go** *(Change from Docker to Go)* | Native Go runtime is faster and lighter. |
| **Branch** | `main` | Production branch containing backend code. |
| **Region** | **Singapore (Southeast Asia)** | **Crucial:** User is in India. Singapore gives ~40ms ping vs ~250ms for Oregon (US West). |
| **Root Directory** | `backend` | **CRITICAL:** `go.mod` and Go source code live inside `backend/`, not root. |
| **Build Command** | `go build -o server ./cmd/server` | Builds the unified relay, auth, and WebRTC signaling binary. |
| **Start Command** | `./server` | Executes the compiled Go server binary. |
| **Compute Plan** | **Free (0.1 CPU, 512 MB RAM)** | Zero-cost plan ($0.00/mo). Zero-disk architecture fits easily. |
| **Health Check Path** | `/health` | Defined in `main.go:142`. Returns HTTP 200 `{"status":"UP"}`. |
| **Auto-Deploy** | `Yes` (Default) | Automatically redeploys when you push new commits to `main`. |

---

## 2. Alternative: If You Prefer to Keep "Docker"

If you choose to keep **Language: Docker**, enter these exact values:

| Render Field | Exact Value to Enter | Reason / Technical Detail |
| :--- | :--- | :--- |
| **Name** | `nexa-relay` (or `chat-app`) | URL identifier (`https://<name>.onrender.com`). |
| **Project** | `chat_app` | Project grouping. |
| **Environment** | `Production` | Environment tag. |
| **Language** | **Docker** | Uses Docker engine. |
| **Branch** | `main` | Branch with code. |
| **Region** | **Singapore (Southeast Asia)** | Lowest latency for India. |
| **Root Directory** | `backend` | **MANDATORY:** `Dockerfile` is inside `backend/`. If left blank, Docker build fails. |
| **Dockerfile Path** | `Dockerfile` (or `./Dockerfile`) | Relative to `backend/`. Matches `backend/Dockerfile`. |
| **Build Command** | *(Leave Blank / Disabled)* | Docker uses instructions inside `Dockerfile`. |
| **Start Command** | *(Leave Blank / Disabled)* | Docker uses `CMD ["./nexa-relay"]` defined in `Dockerfile`. |
| **Compute Plan** | **Free (0.1 CPU, 512 MB RAM)** | Zero-cost plan ($0.00/mo). |
| **Health Check Path** | `/health` | Exact path returning HTTP 200 OK. |
