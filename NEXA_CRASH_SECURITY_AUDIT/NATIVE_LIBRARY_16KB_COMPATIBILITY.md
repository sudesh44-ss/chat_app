# NEXA — Native Library & Android 16 KB Page-Size Compatibility Audit

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Target Specification:** Android 15 (API 35+) 16 KB Memory Page Size Architecture  
**Audit Standard:** ELF Binary Header Program Header (`PT_LOAD`) Alignment & ZIP Entry Page Alignment

---

## 1. Executive Summary

Android 15 introduces support for devices with a $16\text{ KB}$ kernel page size (transitioning from the historical $4\text{ KB}$ page size). For an Android application with native libraries (`.so` files) to run on $16\text{ KB}$ kernels:
1. **ELF `PT_LOAD` Segment Alignment:** Every `PT_LOAD` segment inside the compiled `.so` binary must have `p_align >= 16384 (0x4000)`, and `(p_vaddr % p_align) == (p_offset % p_align)`.
2. **ZIP Entry Alignment:** Uncompressed `.so` files packaged inside the APK must be aligned to a $16\text{ KB}$ boundary relative to the start of the APK file.

---

## 2. Native Libraries Discovered in APK

Inspection of `H:\chat_app/app/build/outputs/apk/debug/app-debug.apk`:

| Native Library Name | Source / Dependency | Architecture |
|---|---|:---:|
| `libsqlcipher.so` | `net.zetetic:sqlcipher-android:4.6.1` (Upgraded from `4.5.4`) | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| `libjingle_peerconnection_so.so` | `io.getstream:stream-webrtc-android:1.3.10` | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-path` | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| `libimage_processing_util_jni.so` | `androidx.camera:camera-core:1.4.1` | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| `libsurface_util_jni.so` | `androidx.camera:camera-core:1.4.1` | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

---

## 3. Before vs After Verification Results (`arm64-v8a`)

### A. Before Upgrade (`net.zetetic:android-database-sqlcipher:4.5.4`):
- `libandroidx.graphics.path.so`: `p_align = 16384 (0x4000)` -> **PASS**
- `libimage_processing_util_jni.so`: `p_align = 16384 (0x4000)` -> **PASS**
- `libjingle_peerconnection_so.so`: `p_align = 16384 (0x4000)` -> **PASS**
- `libsurface_util_jni.so`: `p_align = 16384 (0x4000)` -> **PASS**
- `libsqlcipher.so`: `p_align = 4096 (0x1000)` -> **CRITICAL FAILURE (FAILED 16 KB ALIGNMENT)**

### B. After Upgrade (`net.zetetic:sqlcipher-android:4.6.1`):
Command output from program header inspection:
```text
Library: lib/arm64-v8a/libandroidx.graphics.path.so (64-bit: True, phnum: 9)
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x0, p_offset=0x0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x5C40, p_offset=0x1C40
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x9FD0, p_offset=0x1FD0

Library: lib/arm64-v8a/libimage_processing_util_jni.so (64-bit: True, phnum: 9)
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x0, p_offset=0x0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0xA5C0, p_offset=0x65C0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0xE968, p_offset=0x6968

Library: lib/arm64-v8a/libjingle_peerconnection_so.so (64-bit: True, phnum: 11)
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x0, p_offset=0x0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0xAE38E0, p_offset=0xADF8E0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0xB07A30, p_offset=0xAFFA30

Library: lib/arm64-v8a/libsqlcipher.so (64-bit: True, phnum: 9)
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x0, p_offset=0x0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x529BA0, p_offset=0x525BA0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x589970, p_offset=0x581970

Library: lib/arm64-v8a/libsurface_util_jni.so (64-bit: True, phnum: 8)
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x0, p_offset=0x0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x4990, p_offset=0x990
```

---

## 4. Packaging Alignment in APK ZIP Container

Output from `zipalign -c -v 4 H:\chat_app\app\build\outputs\apk\debug\app-debug.apk`:
```text
30031872 lib/arm64-v8a/libandroidx.graphics.path.so (OK)
30048256 lib/arm64-v8a/libimage_processing_util_jni.so (OK)
30081024 lib/arm64-v8a/libjingle_peerconnection_so.so (OK)
41631744 lib/arm64-v8a/libsqlcipher.so (OK)
47431680 lib/arm64-v8a/libsurface_util_jni.so (OK)
```

### Offset Modulo Verification:
- `30031872 % 16384 == 0` (Offset 30,031,872 is aligned to 16 KB)
- `30048256 % 16384 == 0` (Offset 30,048,256 is aligned to 16 KB)
- `30081024 % 16384 == 0` (Offset 30,081,024 is aligned to 16 KB)
- `41631744 % 16384 == 0` (Offset 41,631,744 is aligned to 16 KB)
- `47431680 % 16384 == 0` (Offset 47,431,680 is aligned to 16 KB)

**Status:** **100% VERIFIED & COMPATIBLE WITH ANDROID 16 KB PAGE-SIZE KERNELS.**
