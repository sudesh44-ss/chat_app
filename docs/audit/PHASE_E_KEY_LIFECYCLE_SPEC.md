# NEXA — Phase E Key Lifecycle & Identity Hierarchy Specification

**Date:** 2026-11-07  
**Branch:** `main`  
**Status:** SPECIFICATION COMPLETE (Read-Only Design Phase)

---

## 1. Overview & Trust Architecture

The NEXA End-to-End Encryption (E2EE) system utilizes an asymmetric key hierarchy designed to satisfy three foundational security principles:
1. **Algorithmic Curve Segregation:** Strict separation between RFC 8032 Ed25519 (for identity signatures and authentication proofs) and RFC 7748 X25519 (for Diffie-Hellman key agreement). No cross-curve key derivation or birational point conversion ($u = (1+y)/(1-y)$) is permitted.
2. **Device-to-Device Pairwise Isolation:** E2EE sessions are anchored at the device level. Each physical device holds its own cryptographic identity and prekey pools. Communication across users with multiple devices is orchestrated via multiple independent pairwise Double Ratchet sessions.
3. **Cryptographic Binding:** All X25519 public keys used in key agreement ($IK_{\text{dh}}, SPK$) are bound to the authoritative Ed25519 identity key via digital signatures before they can be registered or trusted.

---

## 2. Complete Key Taxonomy & Responsibilities

| Key Symbol | Key Name | Algorithm & Curve | Lifetime | Storage Location | Responsibilities & Protocol Function |
|---|---|---|---|---|---|
| **$IK_{\text{account}}$** | Account Identity Signing Key | Ed25519 (RFC 8032) | Permanent (Account lifetime) | Primary Device: Encrypted in KeyStore / SecureStorage | Root identity trust anchor for the `NX-ID`. Signs device registration certificates. |
| **$IK_{\text{device\_sign}}$** | Device Identity Signing Key | Ed25519 (RFC 8032) | Device lifetime | Device KeyStore / SecureStorage | Signs WebSocket authentication challenges (`PACKET_AUTH_RESPONSE`), signed prekeys ($SPK$), and device X25519 identity bindings. |
| **$IK_{\text{device\_dh}}$** | Device Identity DH Key | X25519 (RFC 7748) | Device lifetime | Device KeyStore / SecureStorage | Permanent Diffie-Hellman key for X3DH initial handshakes ($DH_1$, $DH_2$). |
| **$SPK$** | Signed Prekey | X25519 (RFC 7748) | Medium-term (7 to 14 days rotation) | Private: Device SQLCipher; Public: Server `public.prekeys` | Prevents asynchronous MITM during offline handshakes ($DH_1$, $DH_3$). Certified by $IK_{\text{device\_sign}}$. |
| **$OPK$** | One-Time Prekey | X25519 (RFC 7748) | Ephemeral (Single-use pool) | Private: Device SQLCipher; Public: Server `public.prekeys` | Provides forward secrecy for initial messages ($DH_4$). Deleted immediately after use. |
| **$EK$** | Ephemeral Handshake Key | X25519 (RFC 7748) | Single handshake transaction | Private: RAM only; Public: Handshake packet | Ephemeral initiator key ($DH_2, DH_3, DH_4$). Wiped immediately after X3DH derivation. |
| **$DHR$** | Ratchet DH Keypair | X25519 (RFC 7748) | Dynamic (Rotated per ratchet turn) | Private: SQLCipher Room; Public: Wire Header (`DoubleRatchetHeader`) | Continuous Diffie-Hellman ratchet providing Post-Compromise Security (PCS). |
| **$RK$** | Double Ratchet Root Key | 256-bit symmetric | Evolving | SQLCipher Room DB | Root derivation key updated on each DH ratchet step via HKDF-SHA256. |
| **$CK_s, CK_r$** | Sending & Receiving Chain Keys | 256-bit symmetric | Evolving (Progressed per message) | SQLCipher Room DB | Symmetric step derivation for message encryption keys ($MK$). |
| **$MK$** | Message Encryption Key | 256-bit symmetric | Single message | RAM only | ChaCha20-Poly1305 message key. Wiped immediately after encryption or decryption. |

---

## 3. Cryptographic Binding & Certification Format

Under no circumstances are Ed25519 keys converted to X25519 keys or vice versa. To bind the X25519 identity key and signed prekey to the device's authoritative Ed25519 signing key, the device generates explicit cryptographic signatures over strictly formatted domain-separated payloads.

```
+---------------------------------------------------------------------------------------+
|                             NEXA CRYPTOGRAPHIC BINDING                                |
+---------------------------------------------------------------------------------------+
|                                                                                       |
|   Account Ed25519 Identity (IK_account)                                               |
|          │                                                                            |
|          ▼ [Signs Device Registration Cert]                                           |
|   Device Ed25519 Signing Key (IK_device_sign)                                         |
|          │                                                                            |
|          ├──────► Signs Device X25519 DH Key (IK_device_dh)                           |
|          │        Signature: Ed25519Verify(IK_device_sign, Attestation_IK) == OK       |
|          │                                                                            |
|          └──────► Signs Medium-Term Signed Prekey (SPK)                               |
|                   Signature: Ed25519Verify(IK_device_sign, Attestation_SPK) == OK      |
|                                                                                       |
+---------------------------------------------------------------------------------------+
```

### 3.1 Device X25519 Identity Binding Attestation
To prove that $IK_{\text{device\_dh}}$ belongs to the authenticated device and prevent key-substitution attacks, the device signs the following byte stream:

$$\text{DataToSign}_{IK_{\text{dh}}} = \text{"NEXA\_BIND\_IK\_V1:"} \parallel \text{nx\_id} \parallel \text{":"} \parallel \text{device\_id} \parallel \text{":"} \parallel IK_{\text{device\_dh}}^{\text{pub}} \parallel \text{":"} \parallel \text{uint64\_be}(timestamp)$$

- **Domain Separator:** `"NEXA_BIND_IK_V1:"` (ASCII constant).
- **Public Key:** Raw 32 bytes of the X25519 identity public key.
- **Timestamp:** Big-endian 64-bit millisecond epoch timestamp.
- **Signature:** 64-byte Ed25519 signature generated with $IK_{\text{device\_sign}}^{\text{priv}}$.
- **Backend Verification:**
  The server verifies this signature before committing the device identity into `public.identity_keys`:
  $$\text{ed25519.Verify}(IK_{\text{device\_sign}}^{\text{pub}}, \text{DataToSign}_{IK_{\text{dh}}}, \text{Signature}) == \text{true}$$

### 3.2 Signed Prekey ($SPK$) Attestation
Bob's device certifies its signed prekey using $IK_{\text{device\_sign}}^{\text{priv}}$:

$$\text{DataToSign}_{SPK} = \text{"NEXA\_SPK\_SIG\_V1:"} \parallel \text{nx\_id} \parallel \text{":"} \parallel \text{device\_id} \parallel \text{":"} \parallel \text{uint32\_be}(spk\_id) \parallel \text{":"} \parallel SPK^{\text{pub}} \parallel \text{":"} \parallel \text{uint64\_be}(timestamp)$$

- **Domain Separator:** `"NEXA_SPK_SIG_V1:"` (ASCII constant).
- **SPK Public Key:** Raw 32 bytes of the X25519 signed prekey.
- **Signature:** 64-byte Ed25519 signature.
- **Peer Verification (Alice):**
  Before computing X3DH secrets, Alice fetches Bob's prekey bundle and independently validates:
  $$\text{Ed25519Verify}(IK_{\text{device\_sign}}^{\text{pub}}, \text{DataToSign}_{SPK}, \text{Signature}_{SPK}) == \text{true}$$
  If validation fails, the handshake is aborted immediately.

---

## 4. Key Storage, Memory Hygiene & Security Boundaries

### 4.1 Android Client Storage Hierarchy
1. **Master Key (Hardware KeyStore):**
   - Alias: `nexa_master_vault_key`
   - Algorithm: AES-256-GCM.
   - Backed by Android StrongBox Keymaster hardware security module (HSM) or TEE.
   - Purpose: Encrypts all raw private key seeds at rest.
2. **Identity Keys ($IK_{\text{sign}}, IK_{\text{dh}}$):**
   - Private seeds (32 bytes each) are encrypted using the Master Key.
   - Stored in `EncryptedSharedPreferences` / `SecureStorage`.
   - Never written to plain files or unencrypted storage.
3. **Prekeys ($SPK, OPK$ Pools) & Ratchet Sessions:**
   - Stored in Zetetic SQLCipher AES-256-CBC encrypted SQLite database (`nexa_encrypted_vault.db`).
   - Tables: `prekeys_local` and `ratchet_sessions`.
   - The database passphrase is derived from the hardware KeyStore master key.
4. **Memory Hygiene:**
   - In accordance with [`CryptoKeystoreManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt), whenever private keys are decrypted into RAM, they are processed inside a `try ... finally` block that executes `Arrays.fill(rawKeyBytes, 0.toByte())` immediately upon completion.
   - Ephemeral keys ($EK$) and intermediate message keys ($MK$) are wiped from memory as soon as the respective cryptographic operation finishes.

### 4.2 Server-Side Storage Invariants
1. **Zero Private Keys:**
   - The Go backend **never** possesses, receives, or stores any private identity keys, prekey private keys, ratchet private keys, or symmetric keys.
2. **Public-Only Tables:**
   - `public.identity_keys`: Stores only `public_key_hex` (Ed25519) and verified signatures.
   - `public.prekeys`: Stores only public X25519 keys (`SIGNED_PREKEY`, `ONE_TIME_PREKEY`).

---

## 5. Key Lifecycle Operations & State Transitions

### 5.1 Account Registration & Initial Key Generation
1. During initial setup, the Android client generates:
   - $IK_{\text{account}}$ (32-byte Ed25519 seed).
   - $IK_{\text{device\_sign}}$ (32-byte Ed25519 seed).
   - $IK_{\text{device\_dh}}$ (32-byte X25519 private scalar).
2. The client derives all corresponding public keys.
3. The client constructs the identity binding attestation and uploads public keys during registration.
4. The client generates:
   - Initial Signed Prekey ($SPK_1$) + Ed25519 signature.
   - Initial pool of 100 One-Time Prekeys ($OPK_1 \dots OPK_{100}$).
5. The client uploads the initial prekey bundle to the backend via authenticated HTTP `POST /api/v1/users/prekey`.

### 5.2 Signed Prekey ($SPK$) Rotation
- **Rotation Interval:** Every 14 days (minimum 7 days).
- **Procedure:**
  1. Background Android WorkManager task (`PrekeyRotationWorker`) triggers.
  2. Client generates fresh X25519 keypair $SPK_{\text{new}}$.
  3. Client increments $spk\_id$ and generates a new Ed25519 signature.
  4. Client uploads $SPK_{\text{new}}$ to `POST /api/v1/users/prekey`.
  5. The previous $SPK$ private key is retained in local SQLCipher for a grace period of 48 hours to decrypt in-flight messages sent just prior to rotation, after which it is wiped and deleted.

### 5.3 One-Time Prekey ($OPK$) Consumption & Replenishment
- **Watermark Threshold:** 20 available OPKs.
- **Replenishment Procedure:**
  1. The backend monitors unconsumed OPKs in `public.prekeys`.
  2. If count $< 20$, backend sends a `PREKEY_REPLENISH_REQUIRED` push/WebSocket message.
  3. Bob's device generates a fresh batch of 50 OPKs:
     - Generates $OPK_{i} = (sk_{opk}, pk_{opk})$.
     - Saves private keys to local SQLCipher table `prekeys_local`.
     - Uploads public keys to `POST /api/v1/users/prekey`.
  4. The server batch inserts the new OPKs with `is_consumed = FALSE`.

### 5.4 Multi-Device Enrollment & Pairwise Sessions
1. **Primary Authorization:**
   When a user registers a second device (Device B), Device B generates its own $IK_{\text{device\_sign\_B}}$ and $IK_{\text{device\_dh\_B}}$.
2. Device B displays a secure QR code or pairing challenge.
3. Device A (Primary) scans the QR code and signs a Device Enrollment Certificate:
   $$\text{Cert}_B = \text{Ed25519Sign}(IK_{\text{account}}^{\text{priv}}, \text{"NEXA\_DEVICE\_ENROLL:"} \parallel \text{nx\_id} \parallel \text{device\_id\_B} \parallel IK_{\text{device\_sign\_B}}^{\text{pub}} \parallel \text{timestamp})$$
4. Device B uploads $\text{Cert}_B$ to the backend to complete device registration.
5. **Peer Interaction:**
   When Alice communicates with Bob (who has Device 1 and Device 2), Alice claims prekey bundles for both Device 1 and Device 2 and executes two parallel, independent Double Ratchet sessions.

### 5.5 Device Revocation
1. A user revokes a compromised or lost device via their primary device.
2. The primary device signs a Revocation Notice:
   $$\text{Notice} = \text{Ed25519Sign}(IK_{\text{account}}^{\text{priv}}, \text{"NEXA\_DEVICE\_REVOKE:"} \parallel \text{device\_id} \parallel \text{timestamp})$$
3. The server sets `is_revoked = TRUE` in `public.devices`.
4. The server deletes all prekeys for the revoked device from `public.prekeys`.
5. The server disconnects any active WebSocket on the revoked device with code `4001`.
6. Peers receiving the revocation event drop the associated pairwise ratchet session and purge the session keys from local storage.
