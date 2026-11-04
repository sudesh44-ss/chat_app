# NEXA — File & Video Transfer Security Audit

**Audit Date:** November 4, 2026  
**Auditor:** Mobile Security Specialist & Media Systems Engineer  
**Pipeline:** Sender Scan & Chunking → P2P DataChannel → Receiver Decryption & Scan  

---

## 1. Dual-Gate Sandbox Architecture

NEXA enforces a **Zero-Trust Dual-Gate Sandbox** for all media and file transfers:
1. **Gate 1 (Sender Pre-Dispatch)**: Malicious files are blocked before leaving the sending device.
2. **Gate 2 (Receiver Pre-Storage)**: Received files are quarantined and verified before reaching gallery or external storage.

---

## 2. Sender-Side Processing Verification

| Stage | Implementation | Audit Findings |
|---|---|---|
| **Magic Byte Inspection** | `DualScanEngine.kt` | Validates first 32 bytes against declared MIME type; prevents executable spoofing (DEX, MZ, ELF). |
| **Cryptographic Hashing** | `DualScanEngine.kt` | Computes SHA-256 hash across full payload before dispatch. |
| **Bloom Filter Heuristic** | `DualScanEngine.kt` | Offline Bloom filter detects known malicious signatures without network calls. |
| **Hardware Video Compression** | `VideoCompressionEngine.kt` | Android MediaCodec H.264 compression; target 720p/1080p, preserves AAC audio track; cleans up temporary files in cache. |
| **Chunk Ciphering** | `ChunkCipherEngine.kt` | 64 KB binary chunks encrypted via ChaCha20-Poly1305 with incremental monotonic nonces. |

---

## 3. Receiver-Side Processing Verification

| Stage | Implementation | Audit Findings |
|---|---|---|
| **Transfer Authorization** | `P2PTransferRepositoryImpl.kt` | Verifies incoming signaling payload contains valid sender NX-ID and authorized device session. |
| **Chunk Decryption** | `ChunkCipherEngine.kt` | Decrypts chunks into private app cache sandbox; rejects tampered chunks with Poly1305 MAC failure. |
| **Integrity Hash Matching** | `DualScanEngine.kt` | Recomputed SHA-256 of reconstructed file must match sender's manifest hash byte-for-byte. |
| **Independent Receiver Scan** | `DualScanEngine.kt` | Second independent magic-byte and antivirus check executes before saving to user-visible storage. |
| **Quarantine Storage** | `QuarantineSandboxSink.kt` | Unsafe or corrupted transfers are quarantined in `/data/user/0/com.nexa.app/files/quarantine/` and logged in Room DB. |

---

## 4. WebRTC P2P Lifecycle & Resource Management
- **State Machine**: Strict sequential transitions: `NEW -> CONNECTING -> CONNECTED -> COMPLETED -> CLOSED`.
- **Disposal Order**: DataChannels are closed and disposed before PeerConnection disposal, eliminating native `libjingle_peerconnection_so.so` SIGSEGV crashes.
- **ICE Handover**: MediaConstraints `IceRestart=true` allows seamless handover when transitioning between Wi-Fi and Cellular networks.
