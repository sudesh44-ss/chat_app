# NEXA On-Device File Security & Anti-Steganography Architecture

## 1. Executive Summary & Zero-Cloud Threat Model

In centralized messaging systems (WhatsApp, Telegram, Signal), file security relies heavily on centralized cloud infrastructure: media transcoding clusters, Antivirus scanning daemon fleets, and automated content-inspection servers. 

In NEXA, files travel directly over **Peer-to-Peer (P2P) WebRTC DataChannels** or as **Blind Encrypted Blobs on Cloudflare R2**. The central server never sees plaintext bytes, has **zero access to decryption keys**, and spends **₹0 on compute, RAM, or storage transcoding**.

### Architectural Decision Matrix: What Was Added vs Dropped

| Capability | Verdict | Rationale |
| :--- | :---: | :--- |
| **Magic Bytes / File Header Inspection** | **STRICTLY ADDED** | Never trust file extensions (`.jpg`). Always inspect the first 16–32 hex bytes to verify true container identity. |
| **Zero-Execution Media Sandbox** | **STRICTLY ADDED** | Received file bytes are stored in isolated internal storage (`/files/vault/safe/`) with inert `.bin` extensions, rendered purely via Compose Canvas / `AsyncImage`, and never executed in WebViews or OS shell contexts. |
| **Re-Encoding Sanitizer (Payload Neutralizer)** | **STRICTLY ADDED** | Decodes raw image bytes into a pure in-memory ARGB pixel matrix and generates a clean re-encoded WebP/JPEG copy. Automatically strips LSB steganographic payloads, appended EOF data, and EXIF GPS tracking metadata. |
| **Zip-Bomb & Decompression Limit Gate** | **STRICTLY ADDED** | Enforces a strict 1:10 decompression ratio ceiling, 100 MB uncompressed limit, 1-level nested recursion depth, and Zip-Slip directory traversal defense. |
| **AI/Heuristic Steganography ML Model** | **DROPPED / SKIPPED** | Mathematically impossible to detect 100% of modern steganographic algorithms (e.g., Hugo, WOW, S-UNIWARD); running heavy ML inference models locally drains mobile battery and causes false positives. Neutralizing payloads via re-encoding is 100x lighter and deterministic. |

---

## 2. End-to-End Processing Pipeline

```text
[ Incoming P2P 64KB Slices / R2 Blind Download ]
                   │
                   ▼
       [ Blake3 / SHA-256 Hash Integrity Check ]
                   │
                   ▼
     ┌────────────────────────────────────────────────────────┐
     │       NexaFileSecurityEngine (On-Device Gate)          │
     ├────────────────────────────────────────────────────────┤
     │ 1. Magic Bytes Check: Real headers (FF D8 FF, etc.)    │
     │ 2. Disguised Binary Check: Detect PE/MZ, DEX, ELF, #!  │
     │ 3. Extension Mismatch Gate: Flag MIME spoofing         │
     │ 4. Banned Extension Gate: Reject .exe, .apk, .sh, etc. │
     └────────────────────────────────────────────────────────┘
                   │
                   ▼
     ┌────────────────────────────────────────────────────────┐
     │       Archive / Image Sanitization Engine              │
     ├────────────────────────────────────────────────────────┤
     │ ArchiveSanitizer:                                      │
     │   • 1:10 Ratio Gate (Zip Bomb Defense)                 │
     │   • Zip-Slip Path Traversal Protection                 │
     │   • Nested Archive Recursion Limit: 1 level            │
     │ MediaSanitizer:                                        │
     │   • inJustDecodeBounds Dimension Probe (< 8192px)      │
     │   • Clean Pixel Extraction (ARGB_8888)                 │
     │   • Strips EXIF GPS & Trailing EOF Payloads            │
     │   • Re-encodes to clean WebP/JPEG                      │
     └────────────────────────────────────────────────────────┘
                   │
                   ▼
[ Safe Internal Storage Vault: `/files/vault/safe/{hash}.bin` ]
                   │
                   ▼
[ UI Presentation: Pure Jetpack Compose Canvas / AsyncImage (No WebViews) ]
```

---

## 3. Core Engine Implementations

### A. Magic Bytes & Anti-Spoofing Engine (`NexaFileSecurityEngine.kt`)
Inspects the true binary headers of incoming streams:

```kotlin
object NexaFileSecurityEngine {
    val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val PDF_HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46)
    val WEBP_RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    val WEBP_MAGIC = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    val GIF_HEADER = byteArrayOf(0x47, 0x49, 0x46, 0x38)
    val ZIP_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    val MP4_FTYP = byteArrayOf(0x66, 0x74, 0x79, 0x70)

    val DEX_HEADER = byteArrayOf(0x64, 0x65, 0x78, 0x0A)
    val PE_MZ_HEADER = byteArrayOf(0x4D, 0x5A)
    val ELF_HEADER = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
    val SHEBANG_HEADER = byteArrayOf(0x23, 0x21)

    val BANNED_EXTENSIONS = setOf(
        "apk", "dex", "so", "exe", "sh", "bat", "cmd", "js", "vbs", "html", "htm", "svg",
        "jar", "bin", "elf", "scr", "msi", "com", "ps1", "vbe", "wsf", "wsh", "dll"
    )
}
```

- **MIME Spoofing Defense:** If a Windows PE executable (`MZ`) or Android DEX binary is renamed to `vacation.jpg`, the engine flags and shreds the file immediately before any OS service or UI viewer accesses it.

---

### B. Image Re-Encoding Sanitizer (`MediaSanitizer.kt`)
Neutralizes hidden payloads by converting raw byte streams into a clean, newly minted image:

1. **Safe Dimension Probe (`inJustDecodeBounds`):**
   ```kotlin
   val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
   BitmapFactory.decodeFile(rawFile.absolutePath, boundsOptions)
   ```
   Ensures dimensions do not exceed 8192px or ~33 Megapixels, preventing **Decompression Bomb (OOM)** attacks.
2. **Clean Pixel Extraction:**
   Decodes pixels into an `ARGB_8888` buffer. Any malicious binary code appended after the JPEG `\xFF\xD9` EOF marker or PNG `IEND` chunk is discarded.
3. **Fresh Re-encoding:**
   Compresses the raw pixel array into a clean WebP/JPEG format. Any micro-variations introduced by LSB (Least Significant Bit) steganography are naturally disrupted and rendered inoperable.
4. **EXIF Purge:**
   Location GPS tags, camera serial numbers, and metadata comments are stripped completely.

---

### C. Archive & Decompression Bomb Protection (`ArchiveSanitizer.kt`)
Protects against zip-bomb denial of service and directory traversal attacks:

- **1:10 Decompression Ratio Limit:** Evaluates uncompressed stream size against compressed file size; aborts instantly if the ratio exceeds 1:10.
- **100 MB Absolute Ceiling:** Total uncompressed size across all entries cannot exceed 100 MB.
- **Max Recursion Depth = 1:** Rejects archives containing nested `.zip`, `.tar`, `.7z`, or `.apk` files.
- **Zip-Slip Path Traversal Protection:**
  ```kotlin
  val canonicalDestFile = outputFile.canonicalPath
  if (!canonicalDestFile.startsWith(canonicalDestDirPath + File.separator)) {
      return ArchiveExtractionResult.Failed("Zip-Slip path traversal blocked for ${entry.name}")
  }
  ```
- **Internal Executable Scanning:** Checks every entry inside the archive against `BANNED_EXTENSIONS`.

---

## 4. Verification & Automated Test Results

The suite in [`FileSecurityAndSanitizationUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/FileSecurityAndSanitizationUnitTests.kt) verifies all defensive gates:

| Test Case | Description | Result |
| :--- | :--- | :---: |
| `fileSecurity_validMagicByteHeadersPass` | Validates JPEG, PNG, PDF, WebP, GIF, and MP4 true headers | **PASSED** |
| `fileSecurity_bannedExtensionsStrictlyBlocked` | Blocks dangerous executable extensions (`.apk`, `.exe`, `.bat`, `.sh`, `.so`, etc.) | **PASSED** |
| `fileSecurity_mimeSpoofingBlocked` | Detects disguised Windows PE (`MZ`), DEX, ELF, and Shell scripts disguised as `.jpg`/`.png` | **PASSED** |
| `archiveSanitizer_zipSlipPathTraversalBlocked` | Blocks directory traversal (`../../system/etc/evil.sh`) | **PASSED** |
| `archiveSanitizer_nestedArchiveBlocked` | Rejects nested archives exceeding recursion depth 1 | **PASSED** |
| `archiveSanitizer_bannedExecutableInsideArchiveBlocked` | Rejects archives containing `.exe` or executable binaries | **PASSED** |
| `archiveSanitizer_decompressionRatioZipBombBlocked` | Blocks synthetic zip bombs exceeding the 1:10 expansion ratio | **PASSED** |
| `archiveSanitizer_safeArchiveExtractionPasses` | Confirms clean archives extract safely inside sandbox limits | **PASSED** |
| `mediaSanitizer_extensionAndBoundsCheck` | Validates safe decompression dimensions (8192px, 33MP limits) | **PASSED** |

### Compilation & Build Output:
- **Unit Tests:** `gradlew.bat testDebugUnitTest --tests com.nexa.app.FileSecurityAndSanitizationUnitTests` $\rightarrow$ **BUILD SUCCESSFUL** (28/28 tasks up-to-date/executed).
- **Application Assembly:** `gradlew.bat assembleDebug` $\rightarrow$ **BUILD SUCCESSFUL** (40 actionable tasks completed, debug APK packaged).
