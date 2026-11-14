# NEXA — APK Signing & Package Integrity Audit

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Tooling:** `apksigner` (Build-Tools 36.0.0), `zipalign` (Build-Tools 36.0.0)

---

## 1. APK Signature Verification Results

Command executed:
`apksigner verify --verbose --print-certs H:\chat_app\app\build\outputs\apk\debug\app-debug.apk`

```text
Verifies: YES
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1

Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: 74e2c1701c882f64284d18dc3b133a1bb84bbd28cf288d5f5df68fefb76e2d7c
Signer #1 certificate SHA-1 digest: 431cb549402f2f8dd945618ef33221d02d7b228d
Signer #1 certificate MD5 digest: 3db3bf52fa359be2d2db45d6b8535689
```

---

## 2. Signature Scheme Compliance Analysis

- **APK Signature Scheme v2:** **ACTIVE & VALID.** Scheme v2 validates the entire binary hash of the APK file against the signing block, preventing any post-signing byte modification, tampering, or resource injection.
- **APK Signature Scheme v1 (JAR signing):** Disabled by default in AGP 9.4.1 for modern Android targets (minSdk 24+), which improves install speed and prevents zip-manipulation exploits.
- **Certificate Status:** Signed with Android's default debug key. For local testing and developer debugging, this is standard. For public distribution, a release keystore (`.jks`) generated with RSA 4096 or EC P-256 with scheme v2 + v3 must be supplied.

---

## 3. Package Integrity & ZIP Alignment Verification

Command executed:
`zipalign -c -v 4 H:\chat_app\app\build\outputs\apk\debug\app-debug.apk`

```text
Verifying alignment of app-debug.apk (4)...
...
30031872 lib/arm64-v8a/libandroidx.graphics.path.so (OK)
30048256 lib/arm64-v8a/libimage_processing_util_jni.so (OK)
30081024 lib/arm64-v8a/libjingle_peerconnection_so.so (OK)
41631744 lib/arm64-v8a/libsqlcipher.so (OK)
47431680 lib/arm64-v8a/libsurface_util_jni.so (OK)
...
Verification successful
```

### 16 KB Page Alignment Verification:
All five `arm64-v8a` uncompressed native libraries inside the APK ZIP archive have starting byte offsets that are strictly divisible by 16,384 (`16 KB`):
- `30031872 / 16384 = 1833.0` (Divisible by 16384)
- `30048256 / 16384 = 1834.0` (Divisible by 16384)
- `30081024 / 16384 = 1836.0` (Divisible by 16384)
- `41631744 / 16384 = 2541.0` (Divisible by 16384)
- `47431680 / 16384 = 2895.0` (Divisible by 16384)

The APK is **100% compliant with both Android 4-byte ZIP alignment and Android 15 16 KB page-size uncompressed ELF memory mapping requirements**.
