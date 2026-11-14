# NEXA — Android Manifest & Permission Audit

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app/app/src/main/AndroidManifest.xml`  
**Target:** Exported Components, Permission Justification, Android 14/15 Compliance

---

## 1. Declared Permissions & Feature Justification

| Permission | Protection Level | Purpose & Justification in NEXA | Principle of Least Privilege Compliance |
|---|:---:|---|:---:|
| `android.permission.INTERNET` | Normal | Core WebSocket relay, WebRTC signaling, Google Drive backup, OTA rules sync | **JUSTIFIED** |
| `android.permission.ACCESS_NETWORK_STATE` | Normal | `NetworkMonitor.kt` detecting cellular vs Wi-Fi transitions and flapping | **JUSTIFIED** |
| `android.permission.FOREGROUND_SERVICE` | Normal | Long-running P2P direct transfers and background streaming sync | **JUSTIFIED** |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Normal | Required on Android 14+ (API 34+) for data sync foreground services | **JUSTIFIED** |
| `android.permission.WAKE_LOCK` | Normal | `PARTIAL_WAKE_LOCK` in `P2PTransferForegroundService` preventing CPU sleep | **JUSTIFIED** |
| `android.permission.POST_NOTIFICATIONS` | Dangerous | Incoming message and active file transfer progress notifications (API 33+) | **JUSTIFIED** |
| `android.permission.READ_CONTACTS` | Dangerous | Privacy-preserving hashed contact discovery (`ContactRepositoryImpl`) | **JUSTIFIED** |
| `android.permission.SEND_SMS` | Dangerous | Port 8901 binary Data SMS dispatch when internet is offline | **JUSTIFIED** |
| `android.permission.RECEIVE_SMS` | Dangerous | Port 8901 binary Data SMS reception via `NexaDataSmsReceiver` | **JUSTIFIED** |
| `android.permission.READ_PHONE_STATE` | Dangerous | Telephony SIM slot state detection for multi-SIM SMS routing | **JUSTIFIED** |
| `android.permission.CAMERA` | Dangerous | QR code scanning via CameraX fallback on de-Googled ROMs | **JUSTIFIED** |
| `android.permission.BLUETOOTH` (maxSdk=30) | Normal | Legacy Bluetooth mesh peer discovery on Android 7.0–11 | **JUSTIFIED** |
| `android.permission.BLUETOOTH_ADMIN` (maxSdk=30)| Normal | Legacy Bluetooth adapter control on Android 7.0–11 | **JUSTIFIED** |
| `android.permission.ACCESS_FINE_LOCATION` | Dangerous | Required for legacy BLE scanning on Android 7.0–11 | **JUSTIFIED** |
| `android.permission.ACCESS_COARSE_LOCATION` | Dangerous | Required for legacy BLE scanning on Android 7.0–11 | **JUSTIFIED** |
| `android.permission.BLUETOOTH_SCAN` | Dangerous | Modern BLE scanning (`neverForLocation` flag declared) | **JUSTIFIED** |
| `android.permission.BLUETOOTH_ADVERTISE` | Dangerous | Modern BLE advertising for mesh neighbor discovery | **JUSTIFIED** |
| `android.permission.BLUETOOTH_CONNECT` | Dangerous | Modern BLE L2CAP CoC channel connection establishment | **JUSTIFIED** |
| `android.permission.NEARBY_WIFI_DEVICES` | Dangerous | Modern Wi-Fi Aware high-throughput mesh discovery (API 33+) | **JUSTIFIED** |

---

## 2. Exported Components & Security Isolation

```xml
<!-- MainActivity: Single entry point, exported=true with MAIN/LAUNCHER -->
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:theme="@style/Theme.Nexa_chat"
    android:windowSoftInputMode="adjustResize">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<!-- Port-Bound Binary Data SMS Receiver (Port 8901) -->
<receiver
    android:name=".data.remote.sms.NexaDataSmsReceiver"
    android:exported="true"
    android:permission="android.permission.BROADCAST_SMS">
    <intent-filter>
        <action android:name="android.intent.action.DATA_SMS_RECEIVED" />
        <data
            android:scheme="sms"
            android:host="localhost"
            android:port="8901" />
    </intent-filter>
</receiver>

<!-- Internal Services: Strictly non-exported (exported=false) -->
<service
    android:name=".data.remote.service.P2PTransferService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />

<service
    android:name=".data.service.P2PTransferForegroundService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

### Component Security Evaluation:
1. **`MainActivity`:** Properly exported with launcher intent filter.
2. **`NexaDataSmsReceiver`:** Exported because incoming SMS is broadcast by the Android OS; **strictly protected by `android:permission="android.permission.BROADCAST_SMS"`** (a signature-level system permission). Malicious third-party apps on the device cannot spoof or send fake broadcasts to this receiver.
3. **Foreground Services:** Explicitly declared with `android:exported="false"`, ensuring other applications cannot hijack or trigger file transfers.
4. **Android 14 Foreground Service Compliance:** Both services declare `android:foregroundServiceType="dataSync"`, satisfying Android 14+ strict foreground service validation.
