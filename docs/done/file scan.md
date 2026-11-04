Yeh **Dual-Scan Pipeline (Sender Pre-Dispatch Scan + Receiver Post-Delivery Pre-Open Scan)** zero-trust P2P messaging ka best practical security model hai.

Isme server par koi file nahi bhejni padegi, isliye expensive cloud antivirus APIs (jaise VirusTotal, jiska commercial tier $10,000+ ka hota hai) ka kharcha **₹0** rahega. Saara scanning execution on-device hardware par ultra-fast tareeqe se chalega.

---

### 1. Direct Workflow Architecture 🎯

```
[Sender Side: Pre-Dispatch Gate]
User taps "Share File"
       │
       ▼
[Stage 1: Multi-Layer Local Guard Engine]
   ├─ 1. Magic Byte & Extension Verification (Anti-Spoofing)
   ├─ 2. Known Malicious Hash Match (Bloom Filter lookup)
   ├─ 3. Static Byte Inspection / Embedded Script Sniffing
   │
   ├── [MALICIOUS DETECTED] ──> Abort Upload + Show Red Alert Warning Modal
   │
   └── [PASSED / SAFE] ──> Generate File Hash + P2P Send

                                 │
                                 ▼ (WebRTC P2P Transfer)
                                 │

[Receiver Side: Post-Download Isolation Gate]
Receiver taps "Download" ──> File streams into Isolated Sandbox (.tmp)
       │
       ▼
[Stage 2: Re-Scan Verification]
   ├─ 1. Re-Verify Checksum / Hash Integrity (Tamper Check)
   ├─ 2. Deep Static Content / Macro Scan
   │
   ├── [MALICIOUS DETECTED] ──> File Locked in Sandbox + Show Danger Modal:
   │                            "⚠️ Potential Threat Detected!
   │                             [Delete File (Recommended)] | [Force Open Anyway]"
   │
   └── [PASSED / CLEAN] ──> Move from Sandbox to User-Accessible Storage

```

---

### 2. Scanning Ke Liye Kaunse Tools & Libraries Use Honge? ⚙️

Mobile device par heavy desktop antiviruses (jaise Windows Defender ya heavy ClamAV daemon) chalana feasible nahi hai kyunki wo phone ko hang kar denge aur 500MB+ RAM kha jayenge. Mobile P2P ke liye **4 specialized, ultra-lightweight on-device scanning tools** ka combo use hota hai:

#### A. Magic Byte & MIME Inspector: `Apache Tika Core` / Custom Native Header Parser

* **Kaam:** Extension spoofing pakadna (e.g., agar file ka naam `notes.pdf` hai lekin andar Windows PE Executable `MZ` ya Android DEX `dex\n` chhipa hai).
* **Speed:** **Less than 1 millisecond** (sirf pehle 16–32 bytes read karta hai).

#### B. Threat Signature Matching: `LibYara` (Native C++ via JNI in Android)

* **Tool:** **YARA** (Cybersecurity industry ka gold standard pattern-matching engine).
* **Kaam:** Rules-based scanning. Known malware signatures, obfuscated shellcode strings, aur embedded exploit payloads (jaise malicious macros in Office docs, hidden JS in PDFs) ko detect karta hai.
* **Size & Footprint:** Yara engine ka compiled binary Android me **~1.5 MB** ka hota hai aur 50MB file ko 200–300 milliseconds me scan kar deta hai.

#### C. Malicious Hash Lookups: `Compact Bloom Filter (Offline DB)`

* **Kaam:** Known ransomware, trojans, aur abuse files ke SHA-256 hashes ka ek compressed **Bloom Filter** app me local bundle rehta hai.
* **Footprint:** 100,000 bad hashes sirf **1.2 MB RAM** me fit ho jate hain. File hash match hote hi instantly red flag ho jata hai.

#### D. Google Safety Engine: `Google Play Protect Integrity & SafetyNet/App Defense API`

* **Kaam:** Agar user koi `.apk` file receive kar raha hai, toh app Android ke built-in **Verify Apps / Play Protect service** ko invoke karta hai install intent pass hone se pehle.

---

### 3. Step-by-Step Implementation Mechanics 🛠️

#### Step 1: Sender-Side Pre-Dispatch Scan (Kotlin)

User ke file select karte hi, transmission shuru hone se pehle ek non-blocking coroutine background worker chalega:

```kotlin
suspend fun scanOutboundFile(context: Context, fileUri: Uri): ScanResult = withContext(Dispatchers.IO) {
    val inputStream = context.contentResolver.openInputStream(fileUri) 
        ?: return@withContext ScanResult.Error("File inaccessible")

    // 1. Magic Bytes Check (Anti-Spoofing)
    val headerBytes = ByteArray(32)
    inputStream.read(headerBytes)
    val detectedType = FileTypeDetector.inspect(headerBytes)
    val declaredExtension = getFileExtension(context, fileUri)

    if (FileTypeDetector.isDangerousMismatch(declaredExtension, detectedType)) {
        return@withContext ScanResult.Malicious(
            reason = "File spoofing detected! Actual type: $detectedType disguised as .$declaredExtension"
        )
    }

    // 2. Hash & Heuristic Yara Scan
    val fileHash = CryptoEngine.computeSha256(context, fileUri)
    if (MalwareBloomFilter.contains(fileHash)) {
        return@withContext ScanResult.Malicious("Known malicious file signature identified.")
    }

    val yaraThreat = YaraScanner.scan(context, fileUri)
    if (yaraThreat != null) {
        return@withContext ScanResult.Malicious("Suspicious heuristic pattern: ${yaraThreat.ruleName}")
    }

    return@withContext ScanResult.Clean(fileHash)
}

```

* **Sender UI Popup:**
* Agar `ScanResult.Clean`: Background me silently pass hoga aur P2P transfer initiate ho jayega.
* Agar `ScanResult.Malicious`: System turant dispatch cancel karega aur modal throw karega:
> **🚫 Malicious Content Detected**
> This file contains potential malicious patterns or spoofed headers and cannot be shared through secure channels.





---

#### Step 2: Receiver-Side Post-Download Sandbox & Re-Scan

Receiver end par file download hote hi direct user storage (Downloads ya Gallery) me **kabhi nahi jayegi**.

1. **Quarantine Sandbox:**
File stream hoti hai internal private sandboxed folder me:
`/data/user/0/com.app.nx/cache/quarantine/temp_session_8923.bin`
2. **Re-Verification Engine:**
* **Hash Check:** Kya downloaded binary ka SHA-256 wahi hai jo sender ne metadata me claim kiya tha? (Agar 1 byte bhi mismatch hua, toh packet tampering ho chuki hai -> file corrupt/discarded).
* **Deep Yara Inspection:** Receiver device par static pattern scan dubara chalega.


3. **Double Confirmation Warning Dialog:**
Agar suspicious flags aate hain, toh UI user ko warn karega:

```
┌─────────────────────────────────────────────────────────┐
│ ⚠️ Security Alert: Potential Threat Detected            │
├─────────────────────────────────────────────────────────┤
│ The file "Report.pdf" contains suspicious embedded      │
│ scripts or unverified payload structures.               │
│                                                         │
│ Opening this file may compromise your device.           │
│                                                         │
│ [ 🗑️ Delete File (Recommended) ]                       │
│ [ ⚠️ Proceed Anyway (Risky)    ]                       │
└─────────────────────────────────────────────────────────┘

```

* Agar user **"Delete File"** dabata hai: Sandbox file zero-fill (`shred`) hoke instantly purge ho jayegi.
* Agar user **"Proceed Anyway"** dabata hai: System app ki sandboxed directory se nikal kar file ko OS ke standard directory me shift karega.

---

### 4. Isko Aur Fast & Efficient Kaise Banayein? 🚀

1. **Chunk-Level Streaming Scan (Zero Extra Waiting Time):**
* Pura 500 MB file download hone ke baad scan shuru karoge toh user ko 5-10 second ka lamba wait karna padega.
* **Trick:** Receiver jab P2P chunks receive kar raha hota hai, hash calculation aur magic bytes verification **parallel streaming pipeline** me execute hote hain. Download 100% hote hi scan ka result **under 50ms** me screen par pop ho jata hai.


2. **Dynamic Rule Updates (OTA Threat Sync):**
* Pure app ka Play Store update bhejne ke bajaye, sirf naye **Yara rules aur Bloom Filter updates** (30–50 KB binary) ko app background me silent OTA sync se weekly update kar sakta hai.


3. **Execution Guard (Block Direct Execution):**
* Even agar user force open click kare, application file ko system intent pass karte waqt flags me `FLAG_GRANT_READ_URI_PERMISSION` degi aur `ACTION_VIEW` intent ko safe container me wrap rakhegi, taaki direct background process spawn na ho sake.