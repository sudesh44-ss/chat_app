QR code ke zariye friend add karne ka feature do zarooraton ko pura karega: **Instant Online Contact Addition** aur **Zero-Internet Day-0 Offline Handshake**.

Isko implement karne ka complete technical mechanics aur architecture:

---

### 1. Direct Concept Breakdown 🎯

User ka QR code ek standard link ya plain text nahi hoga; yeh ek **Compact Cryptographic Identity Payload** contain karega.

1. **QR Display (User A):** User A apna QR code screen par dikhata hai. Is QR ke andar uska **NX ID**, **Display Name**, **Public Encryption Key**, aur **Local Peer/Bluetooth ID** pack hota hai.
2. **Scan & Parse (User B):** User B apne app ke scanner se QR scan karta hai. App payload ko parse karke verify karta hai.
3. **Dual-Route Handshake:**
* **Agar Internet Hai:** App server ko notify karega aur contact list me sync ho jayega.
* **Agar Internet Nahi Hai:** Dono devices ke paas ek doosre ki public key aur local BLE/Wi-Fi radio details instantly pahunch chuki hain. Wo bina kisi internet ya server ke **turant usi second se offline encrypted chat aur file transfer** shuru kar sakte hain.



---

### 2. QR Code Payload Structure (The Engine) ⚙️

QR code me jitna zyada data hoga, QR code utna hi dense (ghana) banega aur camera ko scan karne me time lagega. Isliye JSON ke bajaye compact **URI Scheme** ya **Binary Base64** use kiya jata hai.

#### Standard URI Format:

```text
nx://add?id=NX-9842-K7X&name=Alex&pk=a9f4c3b2e8d1...&bt=7C:D1:C3:8A:12:4F

```

| Field | Purpose | Size |
| --- | --- | --- |
| `id` | User ki unique NX ID | 10–12 chars |
| `name` | Local display name (editable) | Text |
| `pk` | X25519 Public Encryption Key | 32 bytes (Hex/Base64) |
| `bt` / `peer` | Device BLE Advertising ID / Service UUID | 6–16 bytes (offline mesh ke liye) |

---

### 3. Underlying Tech Stack & Android Implementation 🛠️

#### A. QR Code Generator (Display Screen)

Android me QR generate karne ke liye standard library **ZXing ("Zebra Crossing")** use hoti hai:

```groovy
// build.gradle (app)
implementation("com.google.zxing:core:3.5.3")

```

**Kotlin Generator Logic:**

```kotlin
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun generateFriendQrBitmap(payload: String, sizePx: Int = 512): Bitmap {
    val bitMatrix = QRCodeWriter().encode(
        payload, 
        BarcodeFormat.QR_CODE, 
        sizePx, 
        sizePx
    )
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}

```

#### B. QR Code Scanner (Camera Screen)

Scanner ke liye heavy third-party SDKs lagane ki zaroorat nahi hai. Google ka **Google Code Scanner API (ML Kit)** use karein:

* *Kyun?* Isme app ka size **0 MB** badhta hai (module Google Play Services se dynamically load hota hai), aur app ko dangerous camera runtime permission maangne ki zaroorat nahi padti kyunki Google ka native sheet pop-up hota hai.

```kotlin
val scanner = GmsBarcodeScanning.getClient(context)

scanner.startScan()
    .addOnSuccessListener { barcode ->
        val rawValue = barcode.rawValue ?: return@addOnSuccessListener
        handleScannedFriendPayload(rawValue)
    }
    .addOnFailureListener { e ->
        // Handle scanning error or cancel
    }

```

---

### 4. Scan Hote Hi Background Me Kya Hoga? 🔄

```
[QR Scanned]
     │
     ├── 1. Parse URI & Extract (NX ID, Public Key, Local Peer ID)
     │
     ├── 2. Save in Local Encrypted Database (Room + SQLCipher)
     │       - Status: CONTACT_VERIFIED
     │       - Public Key: Pre-cached for Instant E2EE
     │
     ├── 3. Check Network Connectivity:
     │       ├── Internet Available:
     │       │     └─> Sync friend request to Server Relay via WebSocket
     │       │
     │       └── Offline:
     │             └─> Add to Local BLE / Wi-Fi Aware Discovery White-list
     │                 (Bina internet direct chat enable)
     │
     └── 4. UI: Chat Screen open ho jayegi!

```

---

### 5. Isko Aur Effective & Efficient Kaise Banayein? 🚀

1. **Self-Contained Offline Verification:**
* QR payload ke sath user ki **Ed25519 Identity Signature** bhi jodi ja sakti hai (`sig=...`).
* Isse scanner device bina kisi server se pooche mathematically verify kar legi ki yeh QR code asli owner ka hi hai aur kisi ne text tamper nahi kiya hai.


2. **Dynamic Screen Brightness Boost:**
* Jab user "My QR Code" screen open kare, app window brightness ko automatically 80–100% boost kar de:
```kotlin
val layoutParams = window.attributes
layoutParams.screenBrightness = 1.0f
window.attributes = layoutParams

```


* Isse dim screens, scratches ya dhoop me bhi saamne wale ka camera 0.1 second me instant scan kar lega.


3. **One-Time QR Option (Anti-Stalking / Privacy):**
* Default QR permanent NX ID show karega.
* Lekin public places me share karne ke liye ek **"Ephemeral / Expiring QR"** ka toggle de sakte hain jo 15 minute baad expire ho jata hai (Session-based temporary prekey), jisse koi unknown person photo kheench kar baad me spam na kar sake.