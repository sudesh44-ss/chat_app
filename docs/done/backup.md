Google Drive par hidden backup banana WhatsApp aur Signal jaise apps ka standard production model hai. Isme user ke Google Drive account me file regular folders me nahi dikhti, balki ek isolated, hidden sandbox me store hoti hai.

Different devices par restore aur security edge cases ko handle karne ka pura technical architecture:

---

### 1. Direct Concept Breakdown 🎯

* **Hidden Drive Storage:** Google Drive API me ek special area hota hai jise **`appDataFolder`** kehte hain.
* Is folder me rakhi files user ko Google Drive ki website ya app me files list me **nahi dikhti**.
* Sirf wahi app is folder ki files ko read/write kar sakti hai jisne use create kiya hai. User Drive Settings me jakar sirf storage space (e.g., "NX App - 45 MB") dekh sakta hai aur data delete kar sakta hai.


* **Client-Side Zero-Knowledge Encryption:** Google Drive par unencrypted SQLite database bhejna privacy disaster hoga. Isliye device locally ek **Master Backup Key** se pure database ko encrypt karegi, aur sirf encrypted blob upload hoga.
* **Multi-Device / New Device Restore:** Naye phone me Google Login hote hi app `appDataFolder` query karega. File download hogi, user apna **Master Password ya 12-Word Mnemonic Phrase (BIP-39)** enter karega, aur data local phone ke hardware keystore me decrypt hoke bind ho jayega.

---

### 2. Underlying Tech Stack & Mechanics ⚙️

#### A. Google Drive `appDataFolder` Integration

* **Scope Required:** `[https://www.googleapis.com/auth/drive.appdata](https://www.googleapis.com/auth/drive.appdata)` (Is scope me user se puri Drive ka access nahi maangna padta, sirf app ke apne hidden folder ka access milta hai—Play Store approval ke liye safest hai).
* **SDK:** `Google Drive REST API v3` (Google Play Services / Android Credential Manager ke OAuth token ke sath).

```kotlin
// Android REST query to search hidden backup
val fileList = driveService.files().list()
    .setSpaces("appDataFolder")
    .setQ("name = 'nx_secure_backup.vault'")
    .setFields("files(id, name, modifiedTime, size)")
    .execute()

```

#### B. Cryptographic Backup Engine (Local Encryption Before Upload)

1. **Key Derivation (Argon2id ya PBKDF2-HMAC-SHA256):**
* User registration ke waqt ek **12-word Recovery Passphrase (BIP-39)** generate karta hai.
* Naye phone par restore karte waqt is Passphrase + User ke Unique Salt se **256-bit AES Master Key** derive hoti hai.


2. **Database Snapshotting (SQLite VACUUM INTO):**
* Live database ki running file copy karne se database corrupt ho sakta hai.
* Local Room DB par command run hoti hai:
```sql
VACUUM INTO '/data/user/0/com.app/cache/backup_temp.db';

```


* Is temporary snapshot database ko **AES-GCM-256** se encrypt karke `.vault` file banayi jati hai aur Drive ke `appDataFolder` me push kiya jata hai.



---

### 3. Edge-Case Matrix: Restore & Device Scenarios 🔍

| Scenario | Problem | Handling Strategy |
| --- | --- | --- |
| **New Device Restore (Different Phone)** | Naye device ke hardware me puraane device ke Hardware Keystore keys nahi hote. | User apna **12-Word Passphrase** enter karega. Master Key memory me derive hogi, Drive se encrypted file download hogi, decrypt hoke naye phone ke hardware keystore me bind ho jayegi. |
| **Backup Mid-Way Fail / Network Drop** | 50 MB upload karte waqt 30 MB par internet cut ho gaya. | **Google Resumable Upload API** use karein. Upload bytes offset save rehta hai, internet aate hi bache hue bytes upload honge. Pura backup scratch se nahi chalega. |
| **Two Phones Active (Race Condition)** | User ne Phone A par backup kiya, fir Phone B par kuch chats kiye aur Phone B se overwrite kar diya. | **Metadata Conflict Header:** Backup file me ek sequential version counter (`version_epoch: 17294820`) hoga. Agar cloud par naya backup hai aur phone purana upload karne ki koshish kare, toh app overwrite rok dega aur warning dega. |
| **Backup Corruption on Cloud** | Upload hone ke baad koi byte corrupt ho gaya. | Har backup file ke header me encrypted payload ka **SHA-256 / Blake3 Checksum** attach hota hai. Naye phone par download hote hi checksum pehle match hoga; agar mismatch hua toh local existing data wipe nahi hoga. |
| **Phone Lost + Forgot 12-Word Passphrase** | User ka phone kho gaya aur user ke paas 12-word key bhi nahi hai. | **Zero-Knowledge Principle:** Data mathematically recover nahi ho sakta. Yeh trade-off user ko pehle hi transparently onboarding screen par explain karna zaroori hai. |

---

### 4. Isko Aur Effective & Efficient Kaise Banayein? 🚀

1. **Gzip/Zstandard Compression Before Encryption:**
* Text aur chat databases me bohot saara repetitive text hota hai.
* SQLite snapshot ko encrypt karne se pehle **Zstandard (zstd)** ya **Gzip** se compress karein. 100 MB ka database **15–20 MB** ka ho jata hai. Isse upload time aur Google Drive storage dono 80% bachenge.


2. **Nightly Differential Backup (Only Upload Changes):**
* Roz-roz pura database upload karne ke bajaye **SQLite WAL (Write-Ahead Logging)** delta frames upload karein. Agar din bhar me sirf 50 naye text aaye hain, toh sirf 100 KB ka differential patch upload hoga.


3. **Silent Background Execution with WorkManager:**
* Backup worker ko strict constraints ke sath schedule karein:
```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED) // Sirf Wi-Fi par
    .setRequiresCharging(true)                     // Jab phone charge par laga ho
    .setRequiresDeviceIdle(true)                   // Jab user phone use na kar raha ho (raat 3 baje)
    .build()

```


* Isse user ki battery par 0% impact padega aur mobile data pack bilkul kharch nahi hoga.