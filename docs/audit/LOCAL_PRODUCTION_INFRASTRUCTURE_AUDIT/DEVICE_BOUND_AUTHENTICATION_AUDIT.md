# NEXA — Device-Bound Cryptographic Authentication Architecture Audit
**Audit Mode:** Strict Read-Only Architecture & Security Inspection  
**Target:** Android Application & Go Backend Infrastructure  
**Date:** March 2026  

---

## 1. Executive Summary

This audit performs an end-to-end inspection of NEXA's device-bound cryptographic authentication. The intended design promises:
1. Hardware-backed asymmetric keys generated inside the Android Keystore (TEE or StrongBox).
2. Registration of the device public key with the backend account.
3. Cryptographic challenge-response authentication for existing-device fast login without SMS.
4. Non-exportable private keys providing unforgeable device identity.

### Audit Verdict: **CRITICALLY FLAWED & PARTIALLY IMPLEMENTED**
* **No Asymmetric Keys in Android Keystore:** The Android Keystore is **NOT** used to generate or hold asymmetric keypairs (EC P-256 or RSA). Instead, the Keystore is only used to create an AES-256 symmetric master key (`nexa_master_vault_key`). The actual identity keys are software byte arrays in RAM.
* **Corrupted Public Key Derivation:** In `CryptoKeystoreManager.kt`, the public key derivation algorithm does not perform elliptic curve scalar multiplication. It computes `SHA-256(privateSeed)` and clamps the resulting hash.
* **Key Disconnect Between Auth & Messaging:** The public key registered during account onboarding (`identity_pub_key`) is generated in `CryptoKeystoreManager` and stored in `nexa_secure_prefs`. However, the WebSocket connection signs with a completely separate keypair generated in `NexaApplication.kt` and stored in `nexa_handshake_keys`.
* **Challenge-Response Missing in Google Login:** Fast login on existing devices (`POST /api/v1/auth/google`) does **NOT** issue or verify any cryptographic challenge. It simply compares the plaintext strings `device_id` and `public_key_hex`.
* **WebSocket Signature Verification Never Validates Public Key Ownership:** While the Go backend issues a cryptographic challenge over WebSocket (`/ws`), the server verification function `VerifyAuthResponse()` **never checks if the public key in the response belongs to the registered user**. Any client can sign the challenge with an arbitrary newly generated key and gain unauthorized access to any `nx_id`.
* **Hardware Attestation Absent:** Android Key Attestation (`setAttestationChallenge`) is not implemented.

---

## 2. Key Generation & Android Keystore Implementation

### 2.1 Keystore Vault Analysis
Two classes in the Android client touch the Android Keystore:
1. [`com.nexa.app.core.keystore.HardwareKeystoreVault`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/keystore/HardwareKeystoreVault.kt)
2. [`com.nexa.app.core.security.CryptoKeystoreManager`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt)

#### Code Inspection ([`CryptoKeystoreManager.kt:30-49`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt#L30-L49))
```kotlin
private fun ensureMasterKeyExists() {
    if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }
}
```

### 2.2 Detailed Architecture Checklist

| Requirement | Implementation Status | Evidence & Code Reference |
|---|---|---|
| Asymmetric Keypair inside Android Keystore | **NOT IMPLEMENTED** | Only symmetric AES-256 (`KeyProperties.KEY_ALGORITHM_AES`) is generated in Keystore (`CryptoKeystoreManager.kt:32`, `HardwareKeystoreVault.kt:25`). |
| Algorithm: EC P-256 / RSA in Keystore | **NOT IMPLEMENTED** | No `KeyPairGenerator.getInstance("EC" / "RSA", "AndroidKeyStore")` exists. |
| Non-exportable Private Key | **NOT IMPLEMENTED** | Private key is generated as a raw 32-byte software array via `SecureRandom.nextBytes()` (`CryptoKeystoreManager.kt:80`). It is decrypted into memory whenever needed (`CryptoKeystoreManager.kt:67`). |
| StrongBox Keymaster Support | **NOT IMPLEMENTED** | `setIsStrongBoxBacked(true)` is never invoked anywhere in the codebase. |
| Hardware Backing Verification via `KeyInfo` | **NOT IMPLEMENTED** | `KeyInfo` and `SecretKeyFactory` are never imported or checked. |
| Distinguish TEE / StrongBox vs Software | **NOT IMPLEMENTED** | No hardware boundary check exists. On devices without TEE, Keystore falls back silently to software. |
| Android Key Attestation | **NOT IMPLEMENTED** | `KeyGenParameterSpec.Builder.setAttestationChallenge()` is not implemented. |

### 2.3 Critical Bug: Flawed Public Key Derivation
In [`CryptoKeystoreManager.kt:128-138`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt#L128-L138):
```kotlin
private fun derivePublicKeyBytes(privateSeed: ByteArray): ByteArray {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(privateSeed)
    val publicKey = ByteArray(32)
    System.arraycopy(hash, 0, publicKey, 0, 32)
    // Clamp top/bottom bits per Curve25519 specification
    publicKey[0] = (publicKey[0].toInt() and 248).toByte()
    publicKey[31] = (publicKey[31].toInt() and 127).toByte()
    publicKey[31] = (publicKey[31].toInt() or 64).toByte()
    return publicKey
}
```
**Impact:** Curve25519 / Ed25519 public keys require scalar multiplication of the basepoint $G$ by the clamped secret scalar ($A = s \cdot G$). `CryptoKeystoreManager` merely hashes the secret seed with SHA-256 and clamps the hash! This produces a **mathematically invalid public key** that does not correspond to any valid curve point on Curve25519.

---

## 3. Public Key Registration & Account Binding

### 3.1 Transmission to Backend
The public key generated by `CryptoKeystoreManager` is transmitted to the Go backend during:
1. `POST /api/v1/auth/google` (field `public_key_hex` in [`AuthApiService.kt:376`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt#L376))
2. `POST /api/v1/auth/register` (field `public_key_hex` in [`AuthApiService.kt:183`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt#L183))
3. `POST /api/v1/auth/initiate-reverse-verify` (field `public_key_hex` in [`AuthApiService.kt:432`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt#L432))

### 3.2 Backend Storage
On the backend, [`backend/internal/identity/store.go`](file:///H:/chat_app/backend/internal/identity/store.go) stores the key:
* In `account.PublicKeyHex` (`UserAccount` struct)
* In `device.PublicKeyHex` (`Device` struct)
* In the lookup map `s.byPubKey[account.PublicKeyHex] = account.NxID`

### 3.3 Missing Proof of Possession
When `handleRegister` executes in [`backend/internal/auth/handler.go:381-391`](file:///H:/chat_app/backend/internal/auth/handler.go#L381-L391):
```go
if req.SignatureHex != "" {
    sigBytes, err := hex.DecodeString(req.SignatureHex)
    if err == nil && len(sigBytes) == 64 {
        dataToVerify := []byte(fmt.Sprintf("%s:%s", req.PublicKeyHex, req.DeviceID))
        if !ed25519.Verify(pubBytes, dataToVerify, sigBytes) {
            http.Error(w, "Cryptographic proof of possession signature invalid", http.StatusUnauthorized)
            return
        }
    }
}
```
**Flaw:** The signature check is wrapped in `if req.SignatureHex != ""`. In `AuthApiService.kt:180-189`, the Android client **never populates `signature_hex`**. The backend allows registration without any signature!

---

## 4. Challenge-Response Authentication & Existing Device Login

### 4.1 Existing-Device Login Flow (`POST /api/v1/auth/google`)
The intended design specifies that an existing device logging in via Google must be challenged cryptographically.

#### Actual Implementation ([`backend/internal/auth/handler.go:823-873`](file:///H:/chat_app/backend/internal/auth/handler.go#L823-L873))
```go
// Existing user: check if this is the same physical device/hardware key
isSameDevice := false
if req.DeviceID != "" {
    if dev, found := h.store.GetDevice(req.DeviceID); found && !dev.IsRevoked && dev.NxID == existingAcc.NxID {
        isSameDevice = true
    }
}
if !isSameDevice && req.PublicKeyHex != "" {
    if req.PublicKeyHex == existingAcc.PublicKeyHex {
        isSameDevice = true
    }
}

if isSameDevice {
    // Scenario 1: Same device, direct instant login (₹0 cost, zero friction)
    now := time.Now().UnixMilli()
    tokenBytes := make([]byte, 32)
    rand.Read(tokenBytes)
    sessionToken := "nexa_sess_" + hex.EncodeToString(tokenBytes)
    ...
    // Creates 30-day session and returns LOGIN_SUCCESS
}
```

### 4.2 Security Analysis of Existing Device Login
1. **Zero Challenge Issued:** The server generates **no nonce, no challenge, and no timestamp** for the device to sign.
2. **Zero Keystore Involvement:** The Android Keystore is never queried during Google Sign-In.
3. **Insecure String Comparison:** The backend simply checks whether the incoming HTTP POST body contains a `device_id` string matching `dev.DeviceID` or a `public_key_hex` string matching `account.PublicKeyHex`.
4. **Attacker Vector:** Any client presenting a valid Google ID token for user $U$ that knows or guesses the target's `device_id` (which is formed as `dev_<timestamp>` or `dev_<random12>`) immediately receives an unrestricted 30-day session token.

---

## 5. WebSocket Challenge-Response Inspection (`/ws`)

A challenge-response mechanism **is** implemented on the messaging WebSocket (`/ws`), but it contains catastrophic security vulnerabilities.

### 5.1 Server-Side Challenge Generation ([`backend/internal/connection/client.go:66-112`](file:///H:/chat_app/backend/internal/connection/client.go#L66-L112))
* Generates 32-byte secure random nonce: `c.ChallengeNonce = nonce`.
* Sets 60-second TTL: `c.ChallengeExpiry = now + 60000`.
* Sends `PACKET_AUTH_CHALLENGE` to client.

### 5.2 Client-Side Signing ([`app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt:135-172`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt#L135-L172))
* Decodes challenge nonce and timestamp.
* Constructs `dataToSign = nonce + ":$nxId:$challengeTs"`.
* Invokes `identitySigner(dataToSign)`.
* Returns `PACKET_AUTH_RESPONSE` containing `signature` and `publicKey`.

### 5.3 Server-Side Signature Verification: The Broken Check
Look at [`backend/internal/connection/client.go:142-174`](file:///H:/chat_app/backend/internal/connection/client.go#L142-L174):
```go
if len(resp.PublicKey) != ed25519.PublicKeySize {
    return false
}
if len(resp.Signature) != ed25519.SignatureSize {
    return false
}

dataToSign := append(append(c.ChallengeNonce, []byte(":"+c.NxId+":")...), []byte(fmt.Sprintf("%d", c.ChallengeTime))...)
altDataToSign := []byte(fmt.Sprintf("%s:%s:%d", hex.EncodeToString(c.ChallengeNonce), c.NxId, c.ChallengeTime))

c.ChallengeNonce = nil

valid := ed25519.Verify(resp.PublicKey, dataToSign, resp.Signature) ||
    ed25519.Verify(resp.PublicKey, altDataToSign, resp.Signature)

if !valid {
    return false
}

c.Authenticated = true
```

### 5.4 The Critical Vulnerability
* **The server verifies `ed25519.Verify(resp.PublicKey, ...)` using the public key supplied in the incoming packet itself!**
* The server **never checks** whether `resp.PublicKey` matches the registered public key of `c.NxId` in `h.Hub.identityStore`!
* **Exploit:** An adversary can connect to `/ws?nx_id=TARGET_USER`, receive the server's challenge nonce, generate an ephemeral Ed25519 keypair in RAM, sign the challenge with their own private key, pass their own public key in `resp.PublicKey`, and the server authenticates them as `TARGET_USER`!

### 5.5 The Architectural Key Mismatch
Why was this check never implemented on the server?
Because if the server checked `resp.PublicKey == account.PublicKeyHex`, **the genuine NEXA Android app would fail to connect!**
* During registration / reverse verification: NEXA registers `keys.publicKeyHex` from `CryptoKeystoreManager` (stored in `nexa_secure_prefs`).
* During WebSocket connection: NEXA signs with `nexa_handshake_keys` created in `NexaApplication.kt:230-260`.
* The two keys are completely distinct keypairs.

---

## 6. Implementation Status Matrix

| Subsystem / Feature | Status | Defect Description |
|---|---|---|
| Hardware-backed Keypair Generation | **NOT IMPLEMENTED** | Keystore only holds symmetric AES key; private identity keys are software byte arrays. |
| Non-exportable Key Storage | **NOT IMPLEMENTED** | Software private key is decrypted into plaintext memory byte arrays. |
| StrongBox / KeyInfo Verification | **NOT IMPLEMENTED** | No StrongBox request or `KeyInfo` hardware inspection. |
| Android Key Attestation | **NOT IMPLEMENTED** | No attestation certificates or challenges requested. |
| Curve25519 Public Key Derivation | **BROKEN** | Computes clamped `SHA-256(privateSeed)` instead of scalar basepoint multiplication. |
| Proof-of-Possession on Registration | **BROKEN** | Backend skips signature check if `signature_hex` is omitted; client never sends it. |
| Existing-Device Challenge-Response | **NOT IMPLEMENTED** | Fast login compares plaintext `device_id` strings; issues session without proof of key possession. |
| WebSocket Challenge-Response | **BROKEN** | Server verifies packet signature against packet's own public key; never validates against registered user key. |
| Client-Side Key Alignment | **BROKEN** | Client registers one public key with the account and uses a different public key for WebSocket auth. |

---

## 7. Recommended Remediation Plan

1. **Migrate to Asymmetric Keystore Keys:**
   Use Android Keystore's `KeyPairGenerator` with `KeyProperties.KEY_ALGORITHM_EC` (P-256) or on API 31+ `KeyProperties.KEY_ALGORITHM_ED25519` with `KeyGenParameterSpec` set to `PURPOSE_SIGN`. Set `setIsStrongBoxBacked(true)` with graceful fallback to standard TEE.
2. **Implement Real Challenge-Response in `handleGoogleAuth`:**
   When `handleGoogleAuth` recognizes an existing device, it must return a fresh challenge: `{"status": "CHALLENGE_REQUIRED", "challenge": "<nonce>", "timestamp": 123456}`. The Android client signs this challenge inside the Keystore, submits it to `POST /api/v1/auth/device-verify`, and only then receives a session token.
3. **Fix Server WebSocket Key Validation:**
   In `backend/internal/connection/client.go:VerifyAuthResponse`, look up the user account from `h.Hub.identityStore.GetAccountByNxID(c.NxId)` and verify that `hex.EncodeToString(resp.PublicKey) == account.PublicKeyHex`.
4. **Harmonize Client Identity Keys:**
   Retire the separate `nexa_handshake_keys` and ensure `WebSocketManager` signs using the exact same identity keypair bound to the user's registered account.
