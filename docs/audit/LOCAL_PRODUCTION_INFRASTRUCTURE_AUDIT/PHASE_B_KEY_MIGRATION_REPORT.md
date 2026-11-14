# NEXA — Phase B Key Migration Report: Identity Key Unification & E2EE Compatibility

**Date:** November 6, 2026  
**Status:** IMPLEMENTED & VERIFIED  

---

## 1. Cryptographic Analysis of Legacy Derivation

### The Legacy Implementation
In `CryptoKeystoreManager.kt` lines 128–138:
```kotlin
private fun derivePublicKeyBytes(privateSeed: ByteArray): ByteArray {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(privateSeed)
    val publicKey = ByteArray(32)
    System.arraycopy(hash, 0, publicKey, 0, 32)
    publicKey[0] = (publicKey[0].toInt() and 248).toByte()
    publicKey[31] = (publicKey[31].toInt() and 127).toByte()
    publicKey[31] = (publicKey[31].toInt() or 64).toByte()
    return publicKey
}
```

### Why It Was Mathematically Invalid
- **Curve25519 vs. Ed25519:** In Curve25519 scalar multiplication (RFC 7748), the private scalar is clamped by masking bits 0..2 and bit 255.
- **Point Multiplication vs. Hashing:** An Ed25519 public key $A$ is a point on Edwards curve $E$: $A = a \cdot B$, where $a$ is derived from the first 32 bytes of $\text{SHA-512}(k)$ and $B$ is the generator point.
- The legacy code hashed $k$ with SHA-256, clamped the output, and treated the raw clamped bytes directly as the public key. This byte sequence does not represent a valid curve point $a \cdot B$ and cannot verify signatures generated with private seed $k$.

---

## 2. Caller Inventory & Key Store Audit

| Key Store | Storage Location | Stored Keys | Previous Callers | Remediated Status |
| :--- | :--- | :--- | :--- | :--- |
| **SecureStorage** | EncryptedSharedPreferences (`nexa_secure_preferences`) | `key_identity_public_key`, `key_identity_encrypted_privkey`, `key_identity_iv` | `AuthRepositoryImpl` (Google Login, Reverse SMS, Account Registration) | **Unified Authoritative Store**; derives true Ed25519 public key |
| **Handshake Store** | Standard SharedPreferences (`nexa_handshake_keys`) | `sign_priv_enc`, `sign_priv_iv`, `sign_pub` | `NexaApplication` (WebSocket Signer), `ContactRepositoryImpl`, `QrHandshakeRepositoryImpl` | **Synchronized** from Authoritative Store |
| **DH Ratchet Store** | Standard SharedPreferences (`nexa_handshake_keys`) | `dh_priv_enc`, `dh_priv_iv`, `dh_pub` | `DoubleRatchetEngine` (E2EE Session Encryption) | **Preserved Intact**; 100% backward compatible |

---

## 3. Migration Strategy: In-Place Transparent Migration

To prevent invalidating existing users or deleting identity material, the migration executes seamlessly on first boot after update:

```kotlin
// Inside CryptoKeystoreManager.getOrCreateIdentityKeyPair()
val encBytes = enc.hexToByteArray()
val ivBytes = iv.hexToByteArray()

val truePubKeyHex = try {
    val rawPriv = decryptWithMasterKey(ivBytes, encBytes)
    val derived = derivePublicKeyBytes(rawPriv).toHex()
    Arrays.fill(rawPriv, 0.toByte()) // Immediate wipe from memory
    derived
} catch (e: Exception) {
    pub
}

if (truePubKeyHex != pub) {
    // Migrate: Update stored public key hex without changing private seed
    secureStorage.saveIdentityKeyPair(
        publicKeyHex = truePubKeyHex,
        encryptedPrivKeyHex = enc,
        ivHex = iv
    )
}
```

### Safety Guarantees:
1. **Zero Private Key Churn:** The 32-byte private key seed encrypted with Android Keystore AES-256 GCM remains untouched.
2. **E2EE Double Ratchet Sessions Preserved:** The X25519 Diffie-Hellman keys (`dh_priv_enc`, `dh_pub`) are stored under distinct preference keys and remain completely unaffected.
3. **Account Recovery & Multi-Device Sync:** Accounts re-verifying via Reverse SMS automatically update their device public key on the server, completing cloud alignment.
