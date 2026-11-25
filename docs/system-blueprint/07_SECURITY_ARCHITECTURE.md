# NEXA — Master System Architecture Blueprint
## 07. Security Architecture, Cryptography & Privacy Controls

---

### 1. Threat Model & Security Invariants

NEXA is engineered against an aggressive threat model that assumes:
1. **Untrusted Network Transport:** The physical transport medium (public Wi-Fi, cellular networks, ISP routers, hostile BLE mesh environments) is compromised by active eavesdroppers and man-in-the-middle (MITM) adversaries.
2. **Untrusted / Compromised Cloud Server:** The central relay server infrastructure may be subpoenaed, compromised, or maliciously inspected. The server must have zero mathematical capability to read message bodies or access private keys.
3. **Physical Device Seizure / Rooting:** Devices running NEXA may be lost, stolen, or subjected to hardware extraction. Plaintext chats must not exist unprotected on flash memory.
4. **Malicious Content Injection:** Hostile peers may attempt to send weaponized executable payloads, zero-day exploits, or steganographic files disguised as harmless media.

#### Core Security Invariants
- **Invariant 1 (Zero-Plaintext Server):** No plaintext message content, encryption keys, voice data, or backup archives ever reside unencrypted on cloud servers.
- **Invariant 2 (Continuous Forward Secrecy):** Compromise of a device's current ratchet key material does not reveal previously transmitted messages.
- **Invariant 3 (Post-Compromise Security):** A compromised session automatically heals and re-secures itself as soon as the adversary misses an active DH ratchet exchange.
- **Invariant 4 (Decoupled Identity):** The user's public handle (NX-ID) contains zero information about their phone number, email address, or hardware serial numbers.

---

### 2. Cryptographic Primitives & Specifications

| Cryptographic Primitive | Standard / Algorithm | Key / Output Size | Architectural Application |
|---|---|---|---|
| **Asymmetric Identity Signing** | Ed25519 (RFC 8032) | 256-bit Public / 512-bit Signature | Identity authentication, WebSocket auth challenges, signed prekeys. |
| **Diffie-Hellman Key Exchange** | X25519 (RFC 7748) | 256-bit Public & Private Keys | Double Ratchet DH steps, X3DH handshakes, pairwise routing tokens. |
| **Symmetric AEAD Cipher** | ChaCha20-Poly1305 (RFC 8439) | 256-bit Key, 96-bit Nonce, 128-bit MAC | End-to-end messaging payloads, 64 KB P2P file chunks. |
| **Key Derivation Function (KDF)** | HKDF-SHA256 (RFC 5869) | 256-bit Derived Outputs | Advancing Root Key, Sending Chain, and Receiving Chain ratchets. |
| **Hardware Key Protection** | AES-256-GCM (`AndroidKeyStore`) | 256-bit Master Key, 96-bit IV | Encrypting local private keys and SQLCipher database passphrases. |
| **Local Database Encryption** | 256-bit AES-CBC (SQLCipher 4.6.1) | 256-bit Passphrase | Full on-disk page-level encryption of `nexa_encrypted_vault.db`. |
| **Phone Number Privacy Hashing** | HMAC-SHA256 | 256-bit Deterministic Hash | Phone discovery index without exposing plaintext phone numbers. |
| **Phone Number Storage Encryption**| AES-256-GCM | 256-bit Key, 96-bit IV | Encrypting phone numbers in PostgreSQL (`public.profiles`). |
| **Backup Passphrase Derivation** | Argon2id / PBKDF2-HMAC-SHA512 | 256-bit Master Vault Key | Stretching 12-word BIP-39 mnemonic into Google Drive backup key. |
| **Streaming Backup Encryption** | AES-256-GCM | 256-bit Key, 96-bit IV per chunk | Resumable streaming encryption of Google Drive backup bundles. |

---

### 3. Android Keystore Integration & Hardware-Backed Security

NEXA delegates all master key storage to the hardware-backed **Android Keystore System** (`core/keystore/HardwareKeystoreVault.kt`):

```
       +-------------------------------------------------------------+
       |               ANDROID APPLICATION PROCESS (RAM)             |
       |                                                             |
       |  CryptoKeystoreManager.kt                                   |
       |    - Requests encryption / decryption of session keys       |
       |    - Zeroizes decrypted buffers immediately after execution |
       +-------------------------------------------------------------+
                                      |
                     Binder IPC to Keystore Daemon
                                      v
       +-------------------------------------------------------------+
       |          SECURE HARDWARE ENCLAVE (TEE / STRONGBOX)          |
       |                                                             |
       |  Keymaster / StrongBox Keymaster                            |
       |    - Stores AES-256 Master Key (`nexa_master_key`)          |
       |    - Private key material NEVER leaves the secure hardware  |
       |    - Enforces KeyProperties.PURPOSE_ENCRYPT / DECRYPT       |
       |    - Resists JTAG debugging, memory dumping, and rooting    |
       +-------------------------------------------------------------+
```

1. **Hardware-Isolation Guarantee:** The 256-bit AES master key is generated directly inside the device's **Trusted Execution Environment (TEE)** or dedicated **StrongBox Keymaster** chip. The raw key bytes are inaccessible to the Android OS kernel and user-space processes.
2. **Ephemeral In-Memory Zeroization:** When an Ed25519 private key or X25519 identity key is decrypted in RAM for a signature or DH computation, `keystoreManager.withDecryptedPrivateKey()` executes the operation within a scoped block and immediately executes:
   `java.util.Arrays.fill(rawPrivateKeyBytes, 0.toByte())`
   This prevents lingering private keys in garbage-collected memory heaps.

---

### 4. End-to-End Encryption Protocol Implementation

#### A. Signal-Style Double Ratchet (`core/crypto/DoubleRatchetEngine.kt`)
NEXA implements the Double Ratchet Protocol to guarantee end-to-end security:

```
                  Symmetric KDF Chains & DH Ratchet Cycle
                  
                  [Root Key (RK_i)] --------+
                          |                 |
                   DH Ratchet Step          |
                   (X25519 Keypair)         |
                          |                 |
                          v                 v
                  [Root Key (RK_i+1)]   [Chain Key (CK_0)]
                                                |
                                          Symmetric Step
                                          (HKDF-SHA256)
                                                |
                                                v
                                        [Chain Key (CK_1)] ---> [Message Key (MK_1)]
                                                |                        |
                                                |                  ChaCha20-Poly1305
                                                v                        |
                                        [Chain Key (CK_2)]               v
                                                                   [CIPHERTEXT]
```

1. **KDF Chain Ratchet:** For each message sent or received, HKDF-SHA256 derives a new 256-bit Message Key (`MK`) from the current Chain Key (`CK`), advancing the chain key forward. The message key is discarded immediately after encrypting or decrypting a single message.
2. **DH Ratchet:** When the sender receives a reply containing a new ratchet public key from the peer, a new Diffie-Hellman output is calculated: $DH_{out} = X25519(SK_{self}, PK_{peer})$. The Root Key is advanced, creating fresh Sending and Receiving chains.
3. **Out-of-Order Message Handling:** If a message arrives with a higher index ($N > N_{expected}$), all intermediate keys are derived and inserted into the `skipped_keys` table in the encrypted database. When the late packet eventually arrives, its key is retrieved from `skipped_keys`, decrypted, and permanently deleted.

#### B. Extended Triple Diffie-Hellman (X3DH) Handshake
When initiating a conversation with a new contact:
1. Initiator fetches recipient's Prekey Bundle: Identity Key ($IK_B$), Signed Prekey ($SPK_B$), Prekey Signature, and optional One-Time Prekey ($OPK_B$).
2. Initiator verifies Ed25519 signature on $SPK_B$ using $IK_B$.
3. Initiator generates an ephemeral X25519 keypair ($EK_A$).
4. Computes:
   - $DH1 = X25519(IK_A, SPK_B)$
   - $DH2 = X25519(EK_A, IK_B)$
   - $DH3 = X25519(EK_A, SPK_B)$
   - $DH4 = X25519(EK_A, OPK_B)$ (if OPK present)
5. Derives Shared Master Key:
   $SK = \text{HKDF-SHA256}(DH1 \mathbin{\Vert} DH2 \mathbin{\Vert} DH3 \mathbin{\Vert} DH4)$
6. The initiator sends its initial message containing $EK_A$, its identity key, and the ID of the consumed $OPK_B$. The recipient computes the matching DH operations to derive the identical $SK$.

---

### 5. Multi-Layer Client-Side Malware Defense (`DualScanEngine.kt`)

NEXA enforces a multi-layer defense-in-depth model for all incoming files and media before they are committed to user storage:

```
                            INCOMING FILE BUFFER
                                     |
                                     v
                 +---------------------------------------+
                 | GATE 1: Magic Byte Inspector          |
                 | - Reads first 16 bytes of stream      |
                 | - Compares magic signature with MIME  |
                 | - Detects PE / ELF / Mach-O disguised |
                 |   as PNG / PDF / MP4                  |
                 +---------------------------------------+
                                     |
                                   CLEAN?
                                   /    \
                                 YES     NO ----+
                                 /              |
                                v               |
                 +----------------------------+ |
                 | GATE 2: Threat Bloom Filter| |
                 | - 1.2 MB in-memory filter  | |
                 | - SHA-256 hash evaluation  | |
                 | - Flags known malware sums | |
                 +----------------------------+ |
                                |               |
                              CLEAN?            |
                              /    \            |
                            YES     NO ---------+
                            /                   |
                           v                    |
                 +----------------------------+ |
                 | GATE 3: Native YARA JNI    | |
                 | - Pattern-matching engine  | |
                 | - Evaluates binary rules   | |
                 +----------------------------+ |
                                |               |
                              CLEAN?            |
                              /    \            |
                            YES     NO ---------+
                            /                   |
                           v                    v
                   +---------------+   +-----------------------------+
                   | RELEASE FILE  |   | DIVERT TO QUARANTINE VAULT  |
                   | To Chat / App |   | - AES-256 encrypted sandbox |
                   | Local Storage |   | - Log in quarantine_vault   |
                   +---------------+   +-----------------------------+
```

---

### 6. User Privacy Controls & Phone Visibility Grants

1. **HMAC-SHA256 Blind Phone Lookup:**
   - Phone numbers are never exposed in user discovery queries.
   - The client computes $H = \text{HMAC-SHA256}(PHONE\_HMAC\_KEY, \text{phone})$.
   - The backend searches PostgreSQL `public.profiles` for matches on `phone_number_hash` without ever seeing the raw phone number.
2. **Granular Phone Visibility Grants (`016_create_phone_visibility_grants.sql`):**
   - Users can toggle phone discoverability globally (`is_discoverable_by_phone = false`).
   - Granular grants allow specific trusted contacts to view the user's phone number while hiding it from all other peers.
3. **Ghost Mode (Ephemeral Sessions):**
   - Enables ephemeral messaging where messages carry a configurable self-destruct timer (e.g., 30s, 5m, 24h).
   - Upon expiration, background Room DAO queries permanently purge the message and scrub the SQLite database pages.

---

### 7. Regulatory Compliance & Telemetry (IT Rules 2021 Safe-Audit)

To comply with statutory regulations (such as India's IT Rules 2021 Safe-Audit compliance) without violating end-to-end encryption or compromising user privacy:
- **Zero-Plaintext Telemetry:** Telemetry payloads sent to `/api/v1/telemetry/batch` contain strictly aggregated performance metrics (delivery latency, packet drop rates, transport hop counts).
- **Statutory Non-Interception:** The backend server maintains zero plaintext logs, zero decryption keys, and zero message archives, ensuring that no interception or wiretapping is architecturally possible.
