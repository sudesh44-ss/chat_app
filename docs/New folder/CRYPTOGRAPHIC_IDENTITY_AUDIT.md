# NEXA — Cryptographic Identity & E2EE Preservation Audit

**Audit Date:** November 4, 2026  
**Auditor:** Senior Cryptography & Security Auditor  
**Subject:** End-to-End Encryption Primitives, Keystore Protection & Session Preservation  

---

## 1. Cryptographic Primitive Inventory

| Primitive / Protocol | Implementation File | Standard / Algorithm | Audit Status |
|---|---|---|---|
| **Identity Keypair** | `AndroidKeyStoreManager.kt`<br>`Ed25519Signer.kt` | Ed25519 (256-bit Edwards Curve) | **PASS** — Hardware-backed private key |
| **Key Agreement (Diffie-Hellman)** | `DoubleRatchetEngine.kt` | X25519 (RFC 7748 Curve25519) | **PASS** — Tink subtle engine |
| **Prekey Handshake** | `X3dhHandshakeEngine.kt` | X3DH (Signal Protocol Specification) | **PASS** — Signed prekeys + one-time prekeys |
| **Symmetric Encryption** | `ChaChaAeadCipher.kt` | ChaCha20-Poly1305 (RFC 8439) | **PASS** — 128-bit Poly1305 MAC tag |
| **Key Derivation (KDF)** | `DoubleRatchetEngine.kt` | HKDF-SHA256 (RFC 5869) | **PASS** — Separate root & chain KDFs |
| **Wire Envelope Overhead** | `EncryptedEnvelope.kt` | 49 bytes (1B version, 32B DH, 4B seq, 12B nonce) | **PASS** — Compact binary framing |
| **Anti-Spam PoW** | `ProofOfWorkEngine.kt` | Hashcash Micro-PoW (16 leading zero bits) | **PASS** — Client-side solve ~300ms |

---

## 2. Four-Tier Identity Separation Verification

The audit confirms strict separation across the four identity tiers:

1. **Supabase Account Identity**:
   - Identified by Supabase `auth.users.id` (UUID).
   - Manages authentication sessions and account lifecycle.
2. **NEXA NX-ID**:
   - Public handle `NX-XXXXXXXX`.
   - Immutable public lookup identifier.
3. **Cryptographic Identity**:
   - Ed25519 / Curve25519 keypair.
   - Private key generated via AndroidKeyStore, non-exportable from TEE/StrongBox.
   - Server NEVER receives or stores private identity keys.
4. **Device Identity**:
   - Independent `device_id` per physical installation.
   - Bound to the user's NX-ID and authenticated with proof-of-possession signatures.

---

## 3. Cryptographic Proof of Possession & Anti-Impersonation
In `backend/internal/auth/handler.go`, when a client registers a public key, it must submit a cryptographic signature:
```go
dataToVerify := []byte(fmt.Sprintf("%s:%s", req.PublicKeyHex, req.DeviceID))
if !ed25519.Verify(pubBytes, dataToVerify, sigBytes) {
    http.Error(w, "Cryptographic proof of possession signature invalid", http.StatusUnauthorized)
    return
}
```
This guarantees that an attacker cannot register an arbitrary victim's public key without possessing the corresponding private key.

---

## 4. Double Ratchet Preservation Audit
- **Send Path**: `DoubleRatchetEngine.ratchetEncrypt()` creates ratchet steps with monotonically increasing counters.
- **Receive Path**: `DoubleRatchetEngine.ratchetDecrypt()` detects out-of-order messages, stores skipped keys in SQLCipher (`skipped_keys` table with 24-hour TTL), and prevents replays.
- **Session Continuity**:
  The audit confirms that `ratchetDao.getSession(contactNxId)` is preserved in `nexa_encrypted_vault.db` across user login, token refresh, and network reconnection. Ratchet sessions are **never** cleared or reset during normal lifecycle events.
