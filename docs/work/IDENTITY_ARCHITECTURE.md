# NEXA Identity Architecture: Server-Side Allocation & Cryptographic Binding

---

## 1. Core Principles

NEXA uses a decentralized identity system where:
1. **The Client Owns the Keys:** The client generates an Ed25519 identity keypair inside the Android Hardware Keystore. The private key never leaves the device.
2. **Deterministic, Zero-PII NX-ID:** The server derives the user's permanent identifier (`NX-XXXX-XXX`) from the user's verified identity public key. The NX-ID contains zero phone digits, email addresses, or personal data.
3. **Multi-Device Account Topology:** An account is rooted in an authenticated credential (verified phone number or Google account). Multiple devices can register under the account, each holding independent cryptographic keys.

---

## 2. Deterministic NX-ID Derivation Algorithm

```text
Identity Ed25519 Public Key (32 Bytes)
               │
               ▼
       SHA-256 Hash (32 Bytes)
         ├─ Bytes 0..3: Big-Endian 32-bit Integer (Numeric Prefix)
         └─ Bytes 4..6: Crockford Base32 Modulo (Character Suffix)
               │
               ▼
       Format: "NX-%04d-%c%c%c"
       Example: "NX-8419-K7M"
```

### Properties:
- **Crockford Base32 Alphabet:** `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (32 symbols).
- **Zero Ambiguity:** Omits letters `I`, `L` (confused with 1), `O` (confused with 0), and `U` (avoids accidental profanities).
- **Collision Resistance:** In the unlikely event of an NX-ID collision, the server computes `SHA-256(PubKey || Salt)` with `Salt = 1, 2, ...` to resolve the conflict deterministically.

---

## 3. Account Data Model & State Machine

```text
┌─────────────────────────────────────────────────────────────────┐
│                          UserAccount                            │
│  - NxID: "NX-8419-K7M"                                          │
│  - PhoneNumber: "+919876543210" (Primary Credential)           │
│  - GoogleID: "10982736451293847" (Linked Credential)           │
│  - PrimaryPubKey: "a4f8...b12c"                                 │
│  - Devices: []Device                                            │
└──────────────┬──────────────────────────────────────────────────┘
               │ 1-to-many
               ▼
┌─────────────────────────────────────────────────────────────────┐
│                             Device                              │
│  - DeviceID: "dev_pixel_9_a1b2"                                 │
│  - DeviceName: "Google Pixel 9 Pro"                             │
│  - PublicKeyHex: "e7d3...90af" (Device-Specific Ed25519)       │
│  - DhPublicKeyHex: "44b1...81cd" (Device-Specific X25519)       │
│  - FcmToken: "fcm_token_..." (Silent Wake-Up Ping Target)       │
│  - IsRevoked: false                                             │
└──────────────┬──────────────────────────────────────────────────┘
               │ 1-to-1
               ▼
┌─────────────────────────────────────────────────────────────────┐
│                            Session                              │
│  - Token: "nexa_sess_89a0..." (Cryptographically Secure Random) │
│  - DeviceID: "dev_pixel_9_a1b2"                                 │
│  - ExpiresAt: Timestamp (Now + 30 Days)                         │
│  - IsRevoked: false                                             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Device Revocation Lifecycle

1. User opens Settings -> Devices on any active authenticated device.
2. User selects a device and taps **Revoke Device**.
3. Client dispatches `POST /api/v1/devices/{device_id}/revoke` with `Authorization: Bearer <session_token>`.
4. Server validates caller session, marks target device as `IsRevoked = true`, and deletes all active sessions associated with that device.
5. If the target device has an open WebSocket connection, the relay server immediately transmits an authorization revocation control packet and closes the socket.
