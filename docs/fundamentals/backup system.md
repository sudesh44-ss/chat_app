Sudesh, ab **Module 4: Zero-Knowledge Backup, Recovery Security & Verification Lock** ko lock karte hain! 🚀

Kyunki NEXA ke server (Supabase ya Go Relay) par **0% chat history saved hai**, isliye agar user ka phone kho jaye, toot jaye, ya app reinstall ho, toh uski chats aur media sirf **Encrypted Backup** se hi wapas aa sakte hain—aur yeh poora system **₹0 server cost** aur **100% Zero-Knowledge Security** par chalega!

Bina kisi code ke, backup kaise banega, kaise secure hoga, aur restore ke waqt kya verification hoga—har ek part crystal-clear samjhein:

---

### 1. Backup Kahan Aur Kaise Hoga? (Zero Server Cost Architecture) ☁️📦

NEXA ke backup ke **2 Options** rahenge—dono mein aapke server ka **0 MB storage** kharch hoga:

#### Option A: Google Drive Hidden AppData Folder (Primary — Automatic Cloud Sync) 🟢

* **Kahan Save Hoga?** User ke apne **Google Drive ke hidden `appDataFolder**` mein (jo user ke normal Google Drive files mein nahi dikhta, taaki user galti se delete na kar de, aur uske apne free 15 GB quota se use hota hai).
* **Kya-Kya Backup Hoga?**
1. **Local SQLCipher Chat Database** (saari 1-to-1 aur Group text chats + timestamps).
2. **Connections & Group Metadata** (local saved names, `NX-ID` mappings).
3. **Media Toggle (Optional):** User choose kar sakta hai ki *"Include Photos/Videos in Backup"* ON rakhna hai ya OFF (taaki Drive space aur mobile data bache).


* **Kab Chalega? (Smart Conditions):**
* User ke set kiye schedule par (**Daily / Weekly / Only When I Tap "Back Up Now"**).
* Sirf **Unmetered Wi-Fi** (ya user allow kare toh Mobile Data) par, aur jab battery 20% se upar ho.
* **Incremental Delta Backup:** Har baar poora 500 MB upload nahi hoga—sirf pichhle backup ke baad aaye **naye messages/files** hi encrypt hokar append honge!



#### Option B: Local Offline Export File (`.nxbackup` — 100% Cloud-Free) 💾

* Jo ultra-privacy users Google Drive par encrypted file bhi nahi rakhna chahte, unke liye Settings mein **"Export Encrypted Backup File"** ka option rahega.
* Yeh ek single encrypted file (`NEXA_Backup_2026-11-14.nxbackup`) phone ke Downloads / SD Card / USB drive mein save kar dega, jise naye phone mein import karke restore kiya ja sakta hai.

---

### 2. Backup Ko 100% Secure Kaise Rakhenge? (Zero-Knowledge Encryption) 🔐🛡️

Sabse bada rule: **Google, NEXA Server, ya koi Hacker agar Drive se backup file chura bhi le, toh bhi usko khol nahi sakta!**

Iske liye **2-Key Encryption Lock** lagega (WhatsApp ke End-to-End Encrypted Backup jaisa):

1. **User-Controlled Secret Lock (2 Choices at Backup Setup):**
* **Choice 1 — Custom Passphrase (Password):** User apna ek strong password set karta hai (e.g., `MySecret#Nexa2026`).
* **Choice 2 — Auto-Generated 12-Word / 64-Digit Recovery Key:** App ek 12-word master recovery phrase deti hai jise user likh kar ya screenshot/password manager mein safe rakh leta hai.


2. **Cryptographic Locking (Phone Ke Andar):**
* Phone ke andar **Argon2id** se user ke password ko ek high-strength **Backup Master Key** mein badla jata hai.
* Poori SQLCipher database file ko **AES-256-GCM / XChaCha20-Poly1305** se encrypt karke ek single unreadable binary blob (`backup.enc`) banaya jata hai.
* **Zero Server Key Storage:** Yeh Backup Master Key **kabhi bhi NEXA server par upload nahi hoti**—sirf user ke paas rehti hai!



*(Warning UI Rule: Setup ke waqt user ko saaf dikhaya jayega: "⚠️ NEXA cannot reset your Backup Password/Key. If you forget it, your backup cannot be recovered.")*

---

### 3. Restore & Verification Ka Kya Process Hoga? (Jab Naye Phone Par Login Hoga) 🔄✅

Jab user naye phone par Login karta hai (Module 1 ke baad), toh **Backup Restore + Verification** ke yeh **4 steps** chalenge:

```text
[ 1. Login Complete (Google + Registered SIM Verified) ]
         │
         ▼
[ 2. Backup Detection ] 🔍
  • App Google Drive `appDataFolder` scan karti hai (ya user `.nxbackup` file select karta hai).
  • Screen par Backup Card dikhta hai:
    "Found Backup: 14 Nov 2026 • 48 MB • End-to-End Encrypted 🔒"
         │
         ▼
[ 3. Cryptographic Unlock Verification ] 🔑
  • User apna **Backup Passphrase** ya **12-Word Recovery Key** dalta hai.
  • Phone locally decryption test karta hai:
     - ❌ Galat Password: "Incorrect Backup Password. Try again (4 attempts left)."
     - ✅ Sahi Password: File decrypt hokar naye phone ke **Hardware Keystore (StrongBox)** 
       ki nayi local SQLCipher key se re-encrypt hokar save ho jati hai!
         │
         ▼
[ 4. Identity & E2EE Session Re-Sync ] 🤝
  • Chats restore hone ke baad, app Go Relay Server se kisi pending offline messages 
    (jo backup ke baad aaye the) ko pull karke timeline mein merge kar leti hai.
  • Done! Saari purani chats, media, aur contacts wapas aa gaye!

```

---

### 4. Baaki Kaunsa Verification Bacha Hai? (Complete Verification Checklist) 📋

Ab tak poore NEXA app mein humne har level par verification lock kar diya hai—ek nazar mein dekhein ki **kya-kya verification ban chuka hai**:

1. **Account & Identity Verification (Login / Sign-Up):**
* **Google OAuth Token:** Master identity aur Drive backup access ke liye.
* **Mobile Number Ownership:** Truecaller 1-Tap (1st Priority, ₹0) + Firebase Free Tier OTP (Fallback).
* **Anti-Duplicate Rule:** `1 Mobile Number = 1 NX-ID` (`UNIQUE phone_hash`).


2. **Hardware & SIM Verification (Anti-Clone & Anti-Hijack):**
* **Physical SIM-Binding:** Android `SubscriptionManager` se registered SIM slot lock (`sim_iccid_hash`) — SIM nikalte hi app auto-lock.
* **Device Attestation:** Android Keystore (StrongBox) + Play Integrity check (rooted/fake bots block).


3. **Contact & E2EE Key Verification (Anti-MITM):**
* **Safety Number / QR Scan:** Do dost aapas mein milne par QR code scan karke verify kar sakte hain ki unke beech koi eavesdropping nahi ho rahi.
* **Key Change Alert:** Dost ke phone badalne par automatic security alert.


4. **Backup & Account Migration Verification:**
* **Change Number Verification:** Local Biometric/PIN + Physical New SIM + Existing Keystore Signature.
* **Backup Restore Verification:** Zero-Knowledge Passphrase / 12-Word Recovery Key.



---

### Ek Nazar Mein: Ab Tak Kya-Kya Lock Ho Chuka Hai? 🏆

* **Module 1:** Sign-Up, Existing User Login, SIM-Binding, Connected Devices & Change Number Security ✅
* **Module 2:** Real-Time Internet E2EE Chat (1-to-1 & Groups, Go + Redis RAM Switchboard, Sealed Sender, Green Dot & Typing Privacy) ✅
* **Module 3:** Encrypted SMS Fallback (1-to-1 Text-Only, `NX1:` Interceptor, Smart SMS Inbox, Quota/Recharge Guard) ✅
* **Module 4:** Zero-Knowledge Encrypted Backup (Google Drive AppData + Local `.nxbackup` + Argon2id/AES-256 Lock) ✅

Ab sirf **2 modules** bache hain jinhe discuss karke aapka poora NEXA architecture 100% complete ho jayega:

1. **P2P File & Media Transfer (WebRTC Direct + Cloudflare R2 Temporary Locker)**
2. **Offline Bluetooth / Wi-Fi Direct Mesh Network (Bina Internet aur Bina SIM ke chat!)**

Bataiye Sudesh, pehle kaunsa discuss karein? 🚀