# NEXA — Three-Layer Adaptive File Security System
## Comprehensive Architectural Audit, Implementation Record & Verification Report

**Document Version:** 2.0.0  
**Security Classification:** Highly Critical / Core Architectural Invariant  
**Implementation Status:** Fully Implemented & Independently Verified  
**Target Environment:** NEXA Privacy Messenger (Android Kotlin / Room v23 / Go Backend Wire)  
**Verification Coverage:** 574/574 Android Unit Tests Passing (100%), 12/12 Go Packages Passing (100%), Clean APK Compilation (`assembleDebug`)  

---

## 1. Executive Summary & Core Security Principles

The **NEXA Three-Layer Adaptive File Security System** establishes an uncompromising, zero-trust perimeter around file ingestion, transfer, and consumption across the NEXA privacy messenger. Prior to this implementation, files transferred across WebRTC DataChannels or DTN mesh relied heavily on sender authenticity. In a zero-trust model, authenticated peer identities do not guarantee payload safety: compromised endpoints, manipulated files, or spoofed formats could deliver exploit payloads directly to device storage.

To neutralize these threat vectors without sacrificing offline capabilities or performance on budget mobile hardware, NEXA implements **three mandatory, non-bypassable security checkpoints**:

```mermaid
flowchart TD
    subgraph CP1["Checkpoint 1: Sender-Side Pre-Dispatch Scan"]
        A1["Sender File Selection (SAF / Media)"] --> A2["File Size Ceiling & Policy Check"]
        A2 --> A3["Magic Byte Anti-Spoofing (FileTypeDetector)"]
        A3 --> A4["Format Structure Integrity (StructureValidator)"]
        A4 --> A5["In-Memory Malware Scanner (Bloom Filter + Heuristics)"]
        A5 --> A6["Format-Specific Deep Scanners (Image/PDF/Archive/Media)"]
        A6 --> A7["Deterministic Decision Engine"]
        A7 -->|Pass| A8["Offer Dispatched (Minimal Metadata Only)"]
        A7 -->|Fail| A9["Transfer Aborted & Sender Alerted"]
    end

    subgraph RAG["Receiver Authorization Gate"]
        A8 --> B1["Receiver Ingests Signaling Offer"]
        B1 --> B2{"Receiver Prompt\n[Download & Scan] vs [Decline]"}
        B2 -->|Decline| B3["Transfer Declined & Cleaned"]
        B2 -->|Authorize| B4["Open SCTP DataChannel"]
    end

    subgraph CP2["Checkpoint 2: Transfer-Time Continuous Verification"]
        B4 --> C1["Encrypted WebRTC 64KB Slices"]
        C1 --> C2["Chunk Context & Boundary Validation"]
        C2 --> C3["BitSet Replay & Duplicate Tracking"]
        C3 --> C4["Size Ceiling & Disk Explosion Guard"]
        C4 --> C5["Cryptographic HMAC Tag Validation"]
        C5 --> C6["Slices Written to Private Quarantine RAF"]
        C6 --> C7{"EOF Reached?"}
        C7 -->|Incomplete/Missing Chunks| C8["Abort, Shred File, Close Connection"]
        C7 -->|All Chunks Verified| C9["Finalize Reassembly in Quarantine"]
    end

    subgraph CP3["Checkpoint 3: Receiver Independent Quarantine Scan"]
        C9 --> D1["Isolated Encrypted Quarantine Payload"]
        D1 --> D2["Whole-File SHA-256 Hash Integrity Check"]
        D2 --> D3["Receiver Independent Format & Anti-Spoofing Scan"]
        D3 --> D4["Deep Exploit & Anomaly Analysis"]
        D4 --> D5{"Scan Verdict"}
        D5 -->|Threat Detected| D6["Cryptographic Shredding & Status REJECTED"]
        D5 -->|Safe| D7["State: WAITING_FOR_USER_ACCEPTANCE"]
    end

    subgraph CRG["Controlled Release Gate: Explicit User Acceptance"]
        D7 --> E1["Receiver UI: File Verified Safe"]
        E1 --> E2{"Receiver Action\n[Save to NEXA] vs [Decline]"}
        E2 -->|Decline| E3["Shred Quarantine Payload"]
        E2 -->|Explicit Accept| E4["Promote to Encrypted Safe Vault"]
        E4 --> E5["File Accessible to Internal Viewers"]
    end
```

### Core Invariants Guaranteed by Architecture:
1. **Zero-Trust Transfer Offer:** Senders cannot push bytes uninvited. The initial transfer offer contains only filename, declared size, and hash. No file chunks travel over the wire until the receiver explicitly authorizes the download via `[Download & Scan]`.
2. **Transfer-Time Continuous Authentication:** Every incoming 64 KB slice is authenticated against session context, sequence limits, BitSet replay tracking, and cumulative byte ceilings before disk write.
3. **Fail-Closed Independent Receiver Scan:** Senders claiming `SAFE` status are never trusted. Receiver executes independent deep inspections on the quarantined stream before any user notification or consumption.
4. **Controlled Promotion Gate:** Passing all scans does not release the file to device storage. The file remains locked in isolated private app quarantine until the receiver taps `[Save to NEXA]`.
5. **No ClamAV / Zero Heavy Dependencies:** Massive 450 MB virus databases and heavy JNI wrappers (like libmagic) that trigger Android Low Memory Killer (LMK) crashes or carry viral GPL licenses were completely eliminated in favor of an optimized pure-Kotlin signature engine ($O(1)$ Bloom filter lookup, streaming atom parsers, and bounded memory buffers).

---

## 2. Comprehensive Component Reference

The file security subsystem is located in [`app/src/main/java/com/nexa/app/core/security/`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/).

| Component | Responsibility | Implementation Details |
|---|---|---|
| [`FileTypeDetector.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/FileTypeDetector.kt) | Pure-Kotlin Magic Byte Anti-Spoofing | Inspects leading and embedded magic byte signatures across JPEG, PNG, GIF, WebP, PDF, ZIP, 7Z, MP4, MKV, FLAC, OGG. Blocks 22 dangerous executable types (`.apk`, `.dex`, `.so`, `.exe`, `.sh`, `.bat`, etc.) and prevents polyglot containers. |
| [`HashEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/HashEngine.kt) | Cryptographic Hash Engine | Calculates SHA-256 digests in streaming 16 KB chunks without loading full payloads into heap. Uses `MessageDigest.isEqual` for constant-time comparisons to defeat timing attacks. |
| [`MalwareScanner.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/MalwareScanner.kt) | Threat & Exploit Scanner | Combines in-memory Threat Bloom Filter ($O(1)$ known threat lookup), EICAR signature scanner, LibYARA bytecode heuristics, and NOP sled pattern detectors. |
| [`StructureValidator.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/StructureValidator.kt) | Format Structural Integrity | Validates file structure, header-trailer pairing (e.g. JPEG `FF D8` / `FF D9`, PNG `IHDR` / `IEND`, PDF `%%EOF`, ZIP `EOCD`), and parser integrity to defend against parser differential attacks. |
| [`ArchiveScanner.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/ArchiveScanner.kt) | Archive Sanitizer & Bomb Defense | Enforces strict 1:10 decompression ratio limits, 100 MB decompressed total byte ceiling, 1,000 maximum entry ceiling, canonical Zip-Slip path traversal defense (`..`, `/`, `\`), nested archive blocking, and internal executable blocking. |
| [`ImageSecurityScanner.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/ImageSecurityScanner.kt) | Image Exploit & Dimension Guard | Probes image headers using `inJustDecodeBounds = true`, enforcing 8,192 px max dimension and 33.5 MP ceiling. Provides lossless/near-lossless re-encoding to WebP Q88 to strip malicious trailing payloads and malformed chunks. |
| [`PDFSecurityScanner.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/PDFSecurityScanner.kt) | PDF Bytecode Inspector | Streams document bytecode searching for active script triggers (`/JavaScript`, `/JS`, `/Launch`, `/EmbeddedFiles`) and parser bomb object count limits (> 50,000 objects). |
| [`MediaSecurityScanner.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/MediaSecurityScanner.kt) | Container Atom Validator | Validates streaming atom hierarchy for MP4 (`ftyp`, `moov`, `mdat`), Matroska/WebM EBML headers, and audio sync word headers (MP3, FLAC, OGG, WAV). |
| [`StegoRiskAnalyzer.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/StegoRiskAnalyzer.kt) | Steganography & Appended Data Detector | Detects trailing payloads appended after format EOF markers (JPEG `FF D9`, PNG `IEND`) and identifies polyglot container embedding. |
| [`ResourceLimitGuard.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/ResourceLimitGuard.kt) | Dynamic Resource & Timeout Guard | Implements adaptive scan intensity tiers (<=10MB Full Deep, 10-100MB Streaming Deep, 100MB-1GB Chunked Streaming, >1GB Rejected) and a 30-second fail-closed timeout guard. |
| [`ChunkIntegrityVerifier.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/ChunkIntegrityVerifier.kt) | Checkpoint 2 Transfer Verifier | Real-time WebRTC chunk verification tracking transfer ID binding, sequence bounds, BitSet duplicate/replay tracking, cumulative size validation, cryptographic HMAC chunk tags, and EOF zero-missing-chunk completeness. |
| [`RiskScoringEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/RiskScoringEngine.kt) | Multi-Vector Risk Aggregator | Computes normalized weighted risk scores (0-100) across 6 vector weights (Format Spoofing 30%, Known Threat 40%, Structural Anomaly 15%, Exploit Signature 10%, Metadata Leak 5%) and produces composite risk levels (`CLEAN`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`). |
| [`ScanDecisionEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/ScanDecisionEngine.kt) | Deterministic Decision Synthesizer | Evaluates all specialized scanner outcomes into a single, deterministic `SecurityScanReport` ensuring fail-closed resolution on any suspicious or inconclusive result. |
| [`QuarantineManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/QuarantineManager.kt) | Isolated Quarantine & Vault Storage | Manages isolated encrypted quarantine directories (`security/outgoing_quarantine/`, `security/incoming_quarantine/`, `vault/safe/`), ephemeral AES-256 GCM encryption keys, 24-hour quota cleanup, and atomic safe-vault promotion. |
| [`NexaSecurityEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/NexaSecurityEngine.kt) | Authoritative Coordinator | Unifies Checkpoint 1, Checkpoint 2, Checkpoint 3, Controlled Release Gate, and Profile Picture sanitization into an authoritative single-entry coordinator. |

---

## 3. Database Schema & Migration Record (Room v23)

To track the multi-checkpoint lifecycle, Room Database was updated to version `23` via `MIGRATION_22_23`.

### 1. Database Entity Changes ([`TransferEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/TransferEntity.kt))
```kotlin
@Entity(
    tableName = "file_transfers",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["status"]),
        Index(value = ["securityState"])
    ]
)
data class TransferEntity(
    @PrimaryKey val transferId: Long,
    val conversationId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val localUri: String,
    val fileHash: String,
    val chunksCompleted: Int,
    val totalChunks: Int,
    val status: String,
    val isIncoming: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val securityState: String = "OFFERED",
    val scanResult: String = "SCANNING",
    val quarantinePath: String? = null,
    val senderScanReported: String? = null,
    val threatDetails: String? = null,
    val isExplicitlyAccepted: Boolean = false,
    val expiryTimestamp: Long = 0L
)
```

### 2. Migration Execution ([`NexaDatabase.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt))
```kotlin
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE file_transfers ADD COLUMN securityState TEXT NOT NULL DEFAULT 'OFFERED'")
        db.execSQL("ALTER TABLE file_transfers ADD COLUMN scanResult TEXT NOT NULL DEFAULT 'SCANNING'")
        db.execSQL("ALTER TABLE file_transfers ADD COLUMN quarantinePath TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE file_transfers ADD COLUMN senderScanReported TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE file_transfers ADD COLUMN threatDetails TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE file_transfers ADD COLUMN isExplicitlyAccepted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE file_transfers ADD COLUMN expiryTimestamp INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_file_transfers_securityState ON file_transfers (securityState)")
    }
}
```

---

## 4. UI Implementation & Controlled Release Gate

The user interface strictly reflects the three-checkpoint security model in [`TransferProgressCard.kt`](file:///H:/chat_app/app/src/presentation/transfer/components/TransferProgressCard.kt):

1. **State `OFFERED` (Receiver Authorization Gate):**
   - Displays file name, size, and sender identity.
   - Shows action buttons: `[Download & Scan]` (authorizes P2P DataChannel establishment) and `[Decline]` (rejects offer, informs sender, cleans up).
2. **State `TRANSFERRING` (Checkpoint 2 In-Flight Verification):**
   - Displays progress bar and verified chunk count.
   - Live badge shows `Verifying Chunks...`.
3. **State `WAITING_FOR_USER_ACCEPTANCE` / `SCAN_PASSED` (Checkpoint 3 Passed):**
   - File is completely reassembled and verified in isolated encrypted quarantine.
   - Green security badge displayed: `Security Scan Passed — Safe`.
   - Receiver retains total control: action buttons `[Save to NEXA]` (promotes file to safe vault) and `[Decline]` (shreds quarantined file).
4. **State `COMPLETED` / `RELEASED` (Controlled Release):**
   - Only shown after explicit user acceptance.
   - Action buttons: `[Open]` (launches internal secure viewer) and `[Save to Device]` (SAF export).
5. **State `REJECTED_MALICIOUS` / `SCAN_REJECTED`:**
   - Red security shield: `Blocked Threat: [threatDetails]`.
   - Single red action button: `[Delete & Shred]`. Quarantined file payload is zeroized.

---

## 5. Verification Matrix: 22 Mandatory Scenarios

All 22 test scenarios specified in Phase 16 were implemented in [`NexaThreeLayerFileSecurityTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/NexaThreeLayerFileSecurityTests.kt) and verified to pass with 100% success rate:

| # | Test Scenario | Verified Behavior | Test Method | Outcome |
|---|---|---|---|---|
| 1 | Sender-side malware detection | Malicious test payload detected via Bloom filter; produces `ScanResult.MALICIOUS` with `KNOWN_MALWARE_HASH`. | `testScenario01_SenderMalwareDetectionRejectsThreat` | **PASS** |
| 2 | Sender-side type mismatch | Executable binary disguised as `.jpg` is blocked by magic byte analysis with `BANNED_EXECUTABLE`. | `testScenario02_SenderTypeMismatchDetected` | **PASS** |
| 3 | Malformed file structure | Truncated PNG missing `IHDR`/`IEND` is rejected by `StructureValidator` with `MALFORMED_STRUCTURE`. | `testScenario03_MalformedFileStructureRejected` | **PASS** |
| 4 | Sender scan failure prevents transfer | Banned extension (`.apk`) is rejected pre-dispatch before any signaling offer is sent. | `testScenario04_SenderScanFailurePreventsP2PTransfer` | **PASS** |
| 5 | Receiver decline prevents transfer | Receiver decline on offer transitions state to `DECLINED` without accepting DataChannel slices. | `testScenario05_ReceiverDeclinePreventsTransfer` | **PASS** |
| 6 | Invalid chunk authentication | Chunk with tampered HMAC tag is rejected by `ChunkIntegrityVerifier` with `CORRUPTED_STREAM`. | `testScenario06_InvalidChunkAuthenticationStopsTransfer` | **PASS** |
| 7 | Replayed chunks rejected | Re-feeding identical chunk index returns `DuplicateOrReplayed` via BitSet tracking without byte over-counting. | `testScenario07_ReplayedChunksRejected` | **PASS** |
| 8 | Missing chunks prevent completion | Finalizing session with missing chunk index returns `TransferCompletionResult.Incomplete`. | `testScenario08_MissingChunksPreventSuccessfulCompletion` | **PASS** |
| 9 | Modified chunks fail authentication | Tampering with payload bytes invalidates HMAC tag; transfer is immediately halted. | `testScenario09_ModifiedChunksCauseAuthenticationFailure` | **PASS** |
| 10 | Hash mismatch prevents release | Whole-file SHA-256 mismatch detected in quarantine halts pipeline before release. | `testScenario10_CompleteHashMismatchPreventsRelease` | **PASS** |
| 11 | Receiver independent scanning | Reassembled clean file is independently verified across all format, malware, and structure layers. | `testScenario11_ReceiverIndependentlyScansCompleteFile` | **PASS** |
| 12 | Sender PASS cannot bypass receiver | Inbound transfer with `senderScanReported = "SAFE"` still enforces full receiver Checkpoint 3 scan. | `testScenario12_SenderReportedPassCannotBypassReceiverScan` | **PASS** |
| 13 | Receiver scan failure prevents release | Malicious scan result locks `isExplicitlyAccepted = false` and forbids promotion. | `testScenario13_ReceiverScanFailurePreventsRelease` | **PASS** |
| 14 | PASS without explicit acceptance | Scan-passed file remains unreleased in quarantine until user taps `[Save to NEXA]`. | `testScenario14_PassWithoutExplicitAcceptanceDoesNotRelease` | **PASS** |
| 15 | Quarantine content inaccessible | Quarantined payloads reside strictly in private cache/quarantine and cannot be read externally. | `testScenario15_QuarantineContentCannotBeOpenedExternally` | **PASS** |
| 16 | Rejected content non-exportable | Transfers with `REJECTED_CORRUPTED` or `SCAN_REJECTED` cannot be exported or forwarded. | `testScenario16_RejectedContentCannotBeForwardedOrExported` | **PASS** |
| 17 | State persistence across restarts | All 8 pending/transferring/quarantine states cleanly serialize and deserialize in Room entities. | `testScenario17_AppRestartRestoresValidPendingStates` | **PASS** |
| 18 | Crash during release preserves integrity | Failed promotion leaves original quarantine payload intact without metadata corruption. | `testScenario18_CrashDuringReleaseDoesNotCorruptMetadata` | **PASS** |
| 19 | Resource limit enforcement | Files > 1 GB rejected; adaptive scanning tiers (Full Deep vs Streaming vs Chunked) enforced. | `testScenario19_LargeFilesResourceLimitPolicies` | **PASS** |
| 20 | Concurrent transfer isolation | Separate `ChunkIntegrityVerifier` sessions prevent cross-transfer stream contamination. | `testScenario20_ConcurrentTransfersRemainIsolated` | **PASS** |
| 21 | Profile picture validation gate | Shell scripts and non-images are blocked before avatar re-encoding pipeline. | `testScenario21_ProfilePictureValidationGate` | **PASS** |
| 22 | Inconclusive scan fails closed | Inconclusive or unsupported formats cannot be marked `SAFE` or released. | `testScenario22_UnsupportedAndInconclusiveCannotPass` | **PASS** |

---

## 6. Full Verification Log & Regression Status

### A. Android Test Suite
```text
Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 27s
Total Tests: 574
Passed: 574 (100%)
Failed: 0 (0%)
Ignored: 0 (0%)
```

### B. Go Backend Test Suite
```text
ok      nexa/wire/internal/api          0.667s
ok      nexa/wire/internal/auth         1.250s
ok      nexa/wire/internal/connection   2.673s
ok      nexa/wire/internal/connections  0.768s
ok      nexa/wire/internal/database     0.608s
ok      nexa/wire/internal/dtn          0.315s
ok      nexa/wire/internal/identity     0.402s
ok      nexa/wire/internal/prekey       0.520s
ok      nexa/wire/internal/queue        0.883s
ok      nexa/wire/internal/ratelimit    0.468s
ok      nexa/wire/internal/signaling    0.431s
ok      nexa/wire/internal/wire         0.296s
All 12 packages passed in under 3.0s.
```

### C. Android Compilation Verification
- `assembleDebug` completed successfully with zero compilation errors.
- Room database schema migration v22 -> v23 verified.

---

## 7. Operational Readiness Sign-off

The Three-Layer Adaptive File Security System is fully integrated into NEXA's core messaging and transfer architecture. No files can bypass the multi-checkpoint pipeline. Every received file remains isolated in encrypted quarantine until independently verified and explicitly accepted by the user.
