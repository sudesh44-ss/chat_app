Yeh dono problems (Selective P2P Downloads aur Malicious File Security) bina central cloud server ke system ko production-grade banane ke liye sabse critical hain.

Dono challenges ka step-by-step breakdown:

---

### Part 1: Selective / On-Demand P2P File Transfer (Space & Bandwidth Optimization) 📁

Jab sender file bhejta hai, toh by default kisi bhi user ke phone par actual file bits stream nahi honge. Har transfer **Two-Phase Architecture** par chalega:

```
[Phase 1: Metadata Envelope (Micro-packet)]
Sender ──> Group / Receiver (File name, Size, Blake3 Hash, Blurhash Thumbnail, Ephemeral Session ID)
Status: "AVAILABLE_ON_DEMAND" (Storage: ~1 KB)

                                    │
                  ┌─────────────────┴─────────────────┐
                  ▼                                   ▼
        [User A: Download Tap]               [User B: Ignore/Reject]
                  │                                   │
      [Phase 2: P2P Pull Request]                (Zero bytes downloaded,
      Receiver ──> Swarm / Sender                 Zero phone storage used)

```

#### 1. The On-Demand "Pull" Mechanics

* **Phase 1 (Lightweight Discovery):**
* Sender file upload karne ke bajaye sirf ek **Metadata Envelope** (File name, size, type, Blake3 hash, blur-hash placeholder, aur symmetric file key ka encrypted bundle) broadcast karta hai.
* Receiver aur group ke 50 members ko chat screen par sirf ek clean card dikhta hai: `Project_Report.pdf (120 MB) [⬇ Download Button]`.
* Is stage par sabhi 50 users ki device storage par sirf **1 KB** text metadata save hota hai.


* **Phase 2 (Explicit User Pull):**
* Jin 5 logon ne "Download" button par tap kiya, unka app sender ko ek direct signaling packet bhejega: `REQUEST_FILE_STREAM(file_hash)`.
* Sirf un 5 users ke sath WebRTC DataChannel establish hoga aur encrypted chunks flow hona shuru honge.
* Jin 45 logon ne download nahi kiya, unke phone par **0 MB** space kharch hogi.



#### 2. Group Swarm Distribution & Late Downloaders

* **Seeders Pool:** Group me jin 5 logon ne file download kar li, wo 5 log ab us file ke **"Seeders"** ban chuke hain.
* **Late Downloaders (Baad me aane wale):**
* Maan lo 3 din baad Member #6 ne download par click kiya.
* Us waqt agar Original Sender offline hai, tab bhi Member #6 ko wait nahi karna padega. Uska app group ke active members se query karega: *"Is hash ka chunk kiske paas hai?"*
* Un 5 members me se jo bhi online hoga, file uske phone se P2P stream hokar Member #6 ko mil jayegi.


* **Cache Management Policy (Phone Storage Protection):**
* Har phone par ek **Configurable LRU Cache (Least Recently Used)** set hota hai (e.g., Max 2 GB for P2P Seeding).
* Agar storage limit reach hoti hai, toh app un downloaded files ke local relay chunks ko auto-prune (delete) kar deta hai jinko user ne khud save/pin nahi kiya hai.



---

### Part 2: Malicious / Virus File Security in P2P Architecture 🛡️

Chunki beech me koi cloud server nahi hai jo AWS ya Google Cloud par files ko automatically antivirus se scan kare, isliye security **Client-Side Zero-Trust & Sandboxing** par depend karti hai:

#### 1. In-App Sandboxed Storage (No Direct Gallery/System Exposure)

* Android 10+ me **Scoped Storage** mandatory hai. Download hone wali file seedha user ke public Downloads folder me dump nahi hoti.
* File app ke isolated internal storage directory me aati hai:
`/data/user/0/com.app.nx/files/vault/encrypted_payload`
* Koi bhi file tab tak system me register nahi hoti jab tak user usko explicitly "Save to Device" na kare.

#### 2. MIME-Type Spoofing Protection (Magic Byte Inspection)

* Attacker aksar `.exe`, `.apk` ya malicious script ko `.jpg` ya `.pdf` ka extension dekar bhejte hain (e.g., `cute_cat.jpg`).
* **Technical Countermeasure:**
* File ke extension par kabhi trust mat karo.
* Receiver file ke pehle **16 to 32 bytes (Magic Bytes / File Header)** read karta hai:
* PNG: `89 50 4E 47 0D 0A 1A 0A`
* PDF: `%PDF-` (`25 50 44 46`)
* Android Executable: `dex\n` (`64 65 78 0A`)
* Windows Executable: `MZ` (`4D 5A`)


* Agar extension `.jpg` hai lekin file magic byte executable ka hai, toh app file ko instantly isolate karke **Permanent Quarantine / Auto-Delete** kar dega aur user ko warning dega: *"File header mismatch: Potential Malicious File Blocked"*.



#### 3. Client-Side On-Device Malware Scanning (TensorFlow Lite / YARA Rules)

* Server-free environment me security maintain karne ke do lightweight mechanisms:
* **Compact YARA Signatures:** Known dangerous malware scripts, shellcode patterns aur APK trojan signatures ka ek lightweight 2 MB signature database client-side update hota hai. File complete hote hi background thread par fast YARA scan run hota hai.
* **Android Play Protect API:** Agar user koi `.apk` download karta hai, toh app install intent trigger karne se pehle Google Play Protect ko invoke karta hai verification ke liye.



#### 4. Safe Preview Rendering

* PDFs, SVGs aur Images me malicious buffer overflow exploits ho sakte hain.
* App media render karne ke liye un-sandboxed WebViews ya external apps direct open nahi karega.
* Documents aur PDFs ke liye **Android PdfRenderer** sandboxed canvas par run hota hai jahan script execution completely disabled rehta hai.

---

### System Summary Matrix

| Problem | Root Cause | Tech Solution | Storage / Safety Impact |
| --- | --- | --- | --- |
| **User A Bheja, B Ne Reject Kiya** | Unwanted data transfer | Metadata-first envelope + Explicit Pull request | **0 MB consumed** on User B's device. |
| **Group Me 5 Ne Download Kiya, 45 Ne Nahi** | Group broadcast spam | P2P Swarm seeding (5 downloaders become relay nodes) | Baki 45 users ka storage aur network clean. |
| **Original Sender Offline Hone Par Download** | Single point of failure | Any of the other 5 downloaders acts as seed peer | Late downloaders get file without original sender. |
| **Malicious Payload (Fake Extension)** | Trojan / Malware | File Magic Byte inspection + Sandboxed storage | System compromise blocked instantly before opening. |