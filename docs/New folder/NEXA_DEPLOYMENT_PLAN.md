# NEXA — Render Deployment Guide & Execution Plan

**Objective:** Deploy NEXA Go Relay & Signaling Server on Render and connect the Android client over HTTPS and WSS.  
**Target URL Pattern:** `https://<your-service>.onrender.com`  

---

## Step 1: Initialize Git Repository & Push to GitHub

Because Render deploys from Git, push the project to GitHub:

```powershell
cd H:\chat_app
git init
git add backend/ database/ docs/ .gitignore
git commit -m "feat: Initial commit for NEXA Go backend cloud deployment"
# Create repository 'nexa-backend' on your GitHub account, then:
git branch -M main
git remote add origin https://github.com/<your-username>/nexa-backend.git
git push -u origin main
```

---

## Step 2: Create Render Web Service

1. Log in to [Render Dashboard](https://dashboard.render.com).
2. Click **New +** -> **Web Service**.
3. Select **Build and deploy from a Git repository** -> Choose your `nexa-backend` repository.
4. Configure the service:
   - **Name**: `nexa-relay` (or your chosen name)
   - **Region**: Singapore (`singapore`) or Frankfurt (`frankfurt`) (Singapore is recommended for lowest latency to India).
   - **Branch**: `main`
   - **Root Directory**: `backend`
   - **Runtime**: **Go** (Native)
   - **Build Command**: `go build -o server ./cmd/server`
   - **Start Command**: `./server`
   - **Instance Type**: **Free** ($0 / month)

---

## Step 3: Configure Environment Variables in Render

In the **Environment Variables** section of the Render service, add:

| Key | Value |
| :--- | :--- |
| `APP_ENV` | `production` |
| `GOOGLE_CLIENT_ID` | `356056510017-grmokt7u1sch6hdk9dbah85k2jqe32v2.apps.googleusercontent.com` |
| `OTP_SERVER_PEPPER` | `a8b3c9d2e1f40582736451a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9` |

*(Optional: Add `TWILIO_*` or `MSG91_*` if you have paid SMS gateway keys ready).*

---

## Step 4: Configure Health Check Path

In Render **Settings** -> **Health Check Path**:
- Set path to: `/health`

Click **Create Web Service**.

---

## Step 5: Verify Deployment Logs & Public Endpoints

Once Render displays **Your service is live 🎉**:

1. Test the HTTPS Health Endpoint:
   ```bash
   curl -i https://<your-service>.onrender.com/health
   ```
   **Expected Response:** HTTP 200 OK
   ```json
   {
     "status": "UP",
     "mode": "Ephemeral-RAM-Only",
     "dtnBridge": "Active",
     "diskUsage": "0 Bytes (Zero-Disk)",
     "timestamp": 1793793641646
   }
   ```

2. Test the OTP Request Endpoint:
   ```bash
   curl -X POST https://<your-service>.onrender.com/api/v1/auth/otp/request \
     -H "Content-Type: application/json" \
     -d '{"phone_number": "+919876543210"}'
   ```
   **Expected Response:**
   ```json
   {
     "status": "SENT",
     "resend_cooldown_seconds": 60,
     "expires_in_seconds": 300
   }
   ```

---

## Step 6: Connect Android Application

Once the live Render domain is verified (e.g. `nexa-relay.onrender.com`), update `ServerEndpointConfig.kt`:

```kotlin
object ServerEndpointConfig {
    // Production Cloud URL on Render
    const val PRODUCTION_DOMAIN = "nexa-relay.onrender.com"

    val isProduction: Boolean = true // Set true for cloud, false for local USB/emulator

    val httpBaseUrl: String
        get() = if (isProduction) "https://$PRODUCTION_DOMAIN" else "http://127.0.0.1:8080"

    val wsBaseUrl: String
        get() = if (isProduction) "wss://$PRODUCTION_DOMAIN" else "ws://127.0.0.1:8080"
}
```

Then rebuild and install on device:
```powershell
.\gradlew.bat assembleDebug
& "C:\Users\suryw\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 10BDCZ057A0008G install -r app\build\outputs\apk\debug\app-debug.apk
```
