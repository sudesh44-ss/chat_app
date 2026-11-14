# NEXA — Device Identity & Cryptographic Session Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Android Keystore, Identity Keys, Challenge-Response & WebSocket Auth  
**Date:** November 2026  

---

## 1. Overview of Identity Architecture

NEXA uses hardware-bound, cryptographic device identity rather than shared passwords or SMS OTPs for session authentication:

1. **Android Keystore:** Protects private keys in hardware (TEE/StrongBox).
2. **Account NX-ID:** Permanent unique identifier formatted as `NX-XXXXXXXX`.
3. **Hardware Challenge-Response:** Used when logging into an existing account on the same device.
4. **WebSocket Wire Authentication:** Used to authenticate persistent message relay connections.

---

## 2. Key Generation & Cryptographic Discrepancies

### Critical Discovery: Cryptographic Key Mismatch between Onboarding & WebSocket

During the audit, a major cryptographic mismatch was uncovered between how identity keys are generated during onboarding and how they are signed during WebSocket connection:

#### Component A: Onboarding Key Generation
- **File:** `app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt:79-98`
- **Logic:**
  ```kotlin
  fun generateIdentityKeyPair(): IdentityKeyPair {
      val rawPrivateKey = ByteArray(32)
      secureRandom.nextBytes(rawPrivateKey)
      val rawPublicKey = derivePublicKeyBytes(rawPrivateKey)
      ...
  }
  ```
- **Flaw:** `derivePublicKeyBytes` implements SHA-256 bit-clamping rather than true Ed25519 or Curve25519 scalar point multiplication:
  ```kotlin
  val digest = MessageDigest.getInstance("SHA-256")
  val hash = digest.digest(privateSeed)
  publicKey[0] = (publicKey[0].toInt() and 248).toByte()
  publicKey[31] = (publicKey[31].toInt() or 64).toByte()
  return publicKey
  ```
- This pseudo-public-key is registered with the backend during onboarding via `InitiateReverseVerifyRequestDto.publicKeyHex`.

#### Component B: WebSocket Identity Signer
- **File:** `app/src/main/java/com/nexa/app/NexaApplication.kt:230-259`
- **Logic:**
  ```kotlin
  webSocketManager.setIdentitySigner { dataToSign ->
      val prefs = getSharedPreferences("nexa_handshake_keys", Context.MODE_PRIVATE)
      ...
      val pub = com.nexa.app.core.crypto.Ed25519Signer.derivePublicKey(newPriv)
      val signature = com.nexa.app.core.crypto.Ed25519Signer.sign(dataToSign, privKey)
      Pair(signature, pub)
  }
  ```
- Here, the key is generated and derived using **true Ed25519 point multiplication** (`Ed25519Signer.derivePublicKey`).

#### Component C: Backend WebSocket Verification
- **File:** `backend/internal/connection/client.go:156-177`
- **Logic:**
  ```go
  account, found := c.Hub.identityStore.GetAccountByNxID(c.NxId)
  expectedPubBytes, err := hex.DecodeString(strings.TrimSpace(account.PublicKeyHex))
  if !bytes.Equal(resp.PublicKey, expectedPubBytes) {
      log.Printf("[Session %s] Auth failed: packet public key does not match registered account public key", c.NxId)
      return false
  }
  ```
- **The Result:** The public key sent by the WebSocket client (`resp.PublicKey`) **NEVER MATCHES** the account's registered public key (`expectedPubBytes`).
- **Consequence:** The WebSocket relay server immediately rejects the connection with an authentication failure. The client displays `"Relay Disconnected"`.

---

## 3. Session Persistence & Keystore Storage

- **Local Android Persistence:**
  - `SecureStorage.kt` uses Android `EncryptedSharedPreferences` backed by `MasterKey`.
  - Session tokens and active NX-ID are preserved across normal application restarts.
  - `UserDao` (Room database) stores `UserEntity` with `isVerified = true`.
- **Backend Session Persistence:**
  - `backend/internal/identity/store.go` creates 30-day session tokens (`nexa_sess_<hex>`).
  - Stored in `data/identity_store.json`.
  - **Risk:** Erased upon Render container restart.
