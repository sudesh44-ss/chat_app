# NEXA — Test Execution & Verification Report

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Test Frameworks:** Gradle 9.6, Android JUnit 4, Kotlinx Coroutines Test, Room In-Memory Testing.

---

## 1. Test Suite Summary

- **Total Test Cases Executed:** **111**
- **Passed:** **111**
- **Failed:** **0**
- **Blocked / Skipped:** **0**
- **Pass Rate:** **100.0%**
- **Binary Assembly (`assembleDebug`):** **BUILD SUCCESSFUL (Exit Code: 0)**
- **Artifact Verified:** `H:\chat_app\app\build\outputs\apk\debug\app-debug.apk` (Size: 89,822,775 bytes, ~89.8 MB)

---

## 2. Detailed Test Execution Breakdown

### A. 50k-Scale Resilient P2P Transfer Engine Tests (`ResilientP2PAndMeshUnitTests.kt`)

| Test Name | Expected Result | Actual Result | Status | Evidence |
|---|---|---|:---:|---|
| `testBitfieldTrackerSparseChunksAndCompletion` | Tracks 100 chunks via sparse `BitSet`, correctly computes completion % (60%) and first missing chunk (2) | Matched expected BitSet bitfields & 60% completion | **PASS** | `BitfieldTracker.kt:35` |
| `testMemoryBufferPoolAcquireAndRelease` | Leases pre-allocated 64KB byte arrays, exhausts bounded queue of 16, recycles cleanly | Bounded queue capped at 16, zero memory churn | **PASS** | `MemoryBufferPool.kt:25` |
| `testChunkCipherEngineEncryptionAndDecryption` | ChaCha20-Poly1305 encrypts 64KB chunk with 12-byte deterministic nonce, decrypts losslessly | Output byte array matched original exactly | **PASS** | `ChunkCipherEngine.kt:40` |
| `testChunkCipherTamperDetection` | Tampered ciphertext byte must trigger Poly1305 MAC tag failure and throw `GeneralSecurityException` | Threw expected `GeneralSecurityException` | **PASS** | `ChunkCipherEngine.kt:65` |
| `testSwarmCrossSeederChunkDistribution` | Partitions 100 chunks across 3 peers without overlap (Peer 0: 34 chunks, Peer 1: 33, Peer 2: 33) | Asserted exact round-robin chunk sets | **PASS** | `SwarmCrossSeeder.kt:20` |
| `testPriorityLaneSmallFileLeapfrogging` | File < 5 MB receives Priority 1; file > 5 MB receives Priority 0 | Asserted priority flags (1 vs 0) | **PASS** | `TransferSessionEntity.kt:22` |

---

### B. 18-Scenario Hardened MANET / DTN Mesh Tests (`ResilientP2PAndMeshUnitTests.kt`)

| Test Name | Expected Result | Actual Result | Status | Evidence |
|---|---|---|:---:|---|
| `testLqiDecayEngineMovementAndInterference` | RSSI -60dBm classified as HEALTHY; FER > 0.40 overrides RSSI and flags link as UNSTABLE | Matched `LinkState.HEALTHY` and `LinkState.UNSTABLE` | **PASS** | `LqiDecayEngine.kt:40` |
| `testCompositeMetricEngineMediumWeightsAndBattery` | BLE cost weight (4.0) > Wi-Fi Aware (1.0); Battery at 10% incurs 12x penalty; Battery < 5% incurs 1000x cutoff | Asserted cost ratios and relay cutoff | **PASS** | `CompositeMetricEngine.kt:30` |
| `testRouteOscillationHysteresisAndDwellLock` | New route rejected if < 25% cheaper or if 3s dwell lock active; accepted when cost < 0.75x after 3s | Asserted route switch suppression & activation | **PASS** | `CompositeMetricEngine.kt:60` |
| `testBackpressureControllerCreditAndRelayCutoff` | Leaky-bucket credits consumed; Buffer utilization > 80% cuts off transit relaying | Verified `shouldAcceptTransit(85)` returns false | **PASS** | `BackpressureController.kt:35` |
| `testBloomDuplicateFilterPacketDropping` | 4KB Bloom filter + 2048 circular history detects duplicates in O(1) time and drops redundant multi-path frames | First seen returns false; duplicate returns true | **PASS** | `BloomDuplicateFilter.kt:28` |
| `testSlidingReorderBufferGapTimerAndSequencing` | Packets 1, 3, 2 assembled in strict order 1, 2, 3; Missing packet gap timer triggers UI release | Packets emitted strictly sequentially | **PASS** | `SlidingReorderBuffer.kt:42` |
| `testAlohaBroadcastThrottleRandomizationAndCancellation` | 30–200ms randomized delay applied; Neighbor overhear cancels duplicate transmission | Asserted delay bounds & overhear suppression | **PASS** | `AlohaBroadcastThrottle.kt:25` |
| `testMerkleClusterFingerprintMatchAndMismatch` | Identical packet sets yield identical 8-byte root; Differing sets trigger delta gossip exchange | Asserted Merkle root equivalence & mismatch | **PASS** | `MerkleClusterSync.kt:30` |

---

### C. Client-Side Dual-Gate Malware Pipeline Tests (`DualGateSecurityUnitTests.kt`)

| Test Name | Expected Result | Actual Result | Status | Evidence |
|---|---|---|:---:|---|
| `testMagicByteInspectorValidPng` | Real PNG bytes matched with declared MIME type | Declared clean | **PASS** | `MagicByteInspector.kt:30` |
| `testMagicByteInspectorExecutableDisguisedAsPdf` | Executable (MZ / PE) header disguised as PDF detected and aborted | Aborted with `ThreatCategory.EXTENSION_MIME_MISMATCH` | **PASS** | `MagicByteInspector.kt:55` |
| `testThreatBloomFilterKnownMalware` | 1.2 MB in-memory Bloom filter flags known malicious SHA-256 hash | Flagged as malware; clean file cleared | **PASS** | `ThreatBloomFilter.kt:45` |
| `testYaraFlagsEicarPattern` | In-memory YARA scanner flags standard EICAR test string | Identified with `ThreatCategory.YARA_MALICIOUS_PATTERN` | **PASS** | `DualScanEngine.kt:85` |

---

### D. Cryptographic Core & Protocol Tests

| Test Suite / Component | Test Count | Expected Result | Status |
|---|:---:|---|:---:|
| `DoubleRatchetEngineTest` | 18 | X25519 DH Ratchet forward secrecy, KDF chain step, out-of-order skipped keys recovery | **PASS** |
| `X3dhHandshakeEngineTest` | 12 | Prekey bundle encapsulation, Ed25519 signature verification, identity key binding | **PASS** |
| `HardwareKeystoreVaultTest` | 8 | AES-256 GCM Master Key generation, byte encryption/decryption, Keystore alias check | **PASS** |
| `QrHandshakeTest` | 14 | Compact URI serialization, Ed25519 digital signature validation, QR parsing | **PASS** |
| `VideoCompressionEngineTest` | 6 | Media3 Transformer configuration, 720p constraint, HEVC mime validation | **PASS** |
| `GoogleDriveBackupCryptoTest` | 10 | BIP-39 12-word seed derivation, Argon2id/PBKDF2 key stretch, AEAD vault header check | **PASS** |
| `DataSmsPackagingTest` | 11 | Port 8901 binary wire frame packaging, magic byte prefix, multipart chunking | **PASS** |

---

## 3. Build & Compilation Verification Log

```text
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 1m 32s
111 tests completed, 0 failed.

> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 48s
Artifact: H:\chat_app\app\build\outputs\apk\debug\app-debug.apk
Size: 89,822,775 bytes
Last Modified: 03-11-2026 23:58:34
```
