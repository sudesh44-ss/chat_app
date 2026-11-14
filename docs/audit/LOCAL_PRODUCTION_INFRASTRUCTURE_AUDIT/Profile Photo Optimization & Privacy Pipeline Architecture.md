# Profile Photo Optimization & Privacy Pipeline Architecture

## 1. Executive Summary & Context

In a peer-to-peer, decentralized messaging application like NEXA, efficiency and privacy are paramount.
When displaying avatar cards across:
- Group conversations (e.g., 50–100 peers)
- Device address books & mutual discovery lists
- Active chat header bars and connection requests

Loading uncompressed or lightly compressed 3.5 MB – 5 MB original photos would force devices to download **150 MB to 350 MB** of data simply to render contact lists. This causes noticeable scroll stutter, memory spikes, and rapid battery/mobile data exhaustion.

### Production Standard Rule
* **Profile Photo Target:**
  - Maximum Dimensions: **1024 × 1024 pixels** (1:1 strict center-square crop).
  - Compression Format: **WebP (Lossy, Quality 88)** via Android hardware-accelerated `libwebp`.
  - Target File Size: **150 KB to 400 KB** (~85–90% reduction, visually indistinguishable from source).
  - Privacy Stripping: **100% EXIF metadata removed** (GPS location tags, camera make/model, embedded device IDs).
* **Gallery Safety:**
  - Original gallery files remain **100% untouched**.
  - All optimized avatars are written to private, sandboxed app internal storage (`context.filesDir/avatars/`).

---

## 2. Local Profile Photo Optimization Pipeline 🛠️

```text
[ User selects image from Gallery / Camera ]
                     │
                     ▼
       [ Gallery File 100% Untouched ]
                     │
                     ▼
  [ 1. Safe inJustDecodeBounds Probe ] ──► Extracts dimensions with 0MB RAM allocation
                     │
                     ▼
  [ 2. Power-of-Two Sub-Sampling ]    ──► inSampleSize reduces decode footprint (OOM Guard)
                     │
                     ▼
  [ 3. EXIF Orientation Normalizer ]  ──► Auto-rotates 90°/180°/270° via ExifInterface
                     │
                     ▼
  [ 4. Center-Square 1:1 Smart Crop ] ──► minOf(width, height) centered crop
                     │
                     ▼
  [ 5. Downscale to Max 1024x1024 px ] ──► createScaledBitmap if larger
                     │
                     ▼
  [ 6. Native WebP Compression (Q=88) ] ──► Strips all EXIF GPS/metadata
                     │
                     ▼
  [ 7. Content Hash Generation ]       ──► Deterministic 8-char hex (e.g. avatar_9842a1b2.webp)
                     │
                     ▼
  [ 8. Sandboxed Internal Storage ]   ──► Saved to context.filesDir/avatars/
                     │
                     ▼
  [ 9. Immediate Heap Reclamation ]   ──► Explicit .recycle() on intermediate bitmaps
```

---

## 3. Core Component Implementation

### `ProfilePhotoOptimizer.kt` ([`com.nexa.app.core.media`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/media/ProfilePhotoOptimizer.kt))

- **OOM Prevention**:
  `inJustDecodeBounds = true` reads dimensions first without allocating pixel memory. `calculateInSampleSize` downsamples high-resolution 4K/8K images into memory safely.
- **Orientation Auto-Correction**:
  Reads `ExifInterface.TAG_ORIENTATION` directly from the content `InputStream` and applies `Matrix.postRotate` so photos taken in portrait or upside-down never display sideways.
- **Smart 1:1 Center-Cropping**:
  Calculates `(width - newDimension) / 2` and `(height - newDimension) / 2` to ensure the subject remains centered.
- **Deterministic Content Hash**:
  Computes SHA-256 / Blake3 hash of output WebP bytes, generating clean names like `avatar_9842a1b2.webp`.
- **Memory Hygiene**:
  Calls `.recycle()` on intermediate unscaled bitmaps and raw decodes to eliminate native heap leaks.

---

## 4. Final Media Rules Matrix 📊

| Media Type | Storage / Processing Strategy | Compression & Specs | Integrity & Verification |
| :--- | :--- | :--- | :--- |
| **Profile Photos** | Sandboxed Private Storage (`filesDir/avatars/`) | 1024×1024 1:1 Center-Crop, WebP Q88 (~150–350 KB), EXIF GPS stripped | Deterministic short content hash (`avatar_XXXXXXXX.webp`) |
| **Chat Shared Photos** | Blind Encrypted Vault / Local Cache | **100% Original Binary Intact** (untouched, bit-by-bit identical) | Blake3 / SHA-256 chunked merkle root |
| **Chat Shared Videos** | Hardware-Accelerated Local Compression | H.265 / HEVC 720p/1080p target bitrate | Media3 Transformer / Streaming cipher |
| **Documents & Files** | Blind Encrypted Vault / SAF | **100% Original Binary Intact** | Blake3 chunk hash, Antivirus quarantine verified |
| **User Gallery** | Read-Only | **Untouched** (app never alters user source files) | SAF ContentResolver `openInputStream` |

---

## 5. UI Integration

- **`ProfileSetupScreen.kt`** ([`ProfileSetupScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/ProfileSetupScreen.kt)):
  Features a 92dp circular avatar hero with camera action badge. When tapped, user selects any high-resolution photo from Gallery. The background coroutine invokes `ProfilePhotoOptimizer.optimizeProfilePhoto(context, uri)` and updates `SetupViewModel` with the optimized, sandboxed WebP file.
- **`SetupViewModel.kt`** ([`SetupViewModel.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/setup/SetupViewModel.kt)):
  Manages `OnAvatarChanged` event to update the cryptographic profile payload with the optimized WebP avatar path.

---

## 6. Verification & Test Coverage

- **`ProfilePhotoOptimizerUnitTests.kt`** ([`ProfilePhotoOptimizerUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/ProfilePhotoOptimizerUnitTests.kt)):
  1. `calculateInSampleSize_downscalesPowerOfTwoCorrectly`: Verifies 1024px, 4K, and 8K sub-sampling ratios.
  2. `centerSquareCrop_dimensionMathCalculation`: Confirms centered offset calculation for landscape and portrait aspect ratios.
  3. `computeShortContentHash_deterministicAndUnique`: Verifies identical byte streams produce identical 8-char hex strings and distinct inputs produce distinct hashes.
  4. `mediaMatrixRules_profilePhotoConstraintsVerification`: Asserts 1024 max dimensions, WebP Q88, and 150KB–400KB target bandwidth constraint.
- All unit tests: **PASS**.
