# NEXA — Phase E: Standard Signal X3DH Specification & Known-Answer Test Vectors

**Date:** 2026-11-07  
**Branch:** `main`  
**Status:** SPECIFICATION COMPLETE & MATHEMATICALLY VERIFIED  
**Standard Reference:** Signal X3DH Key Agreement Protocol (Revision 1, 2016-11-04)

---

## 1. Standards Compliance Assessment

### 1.1 Audit Finding & Classification: CORRECTED IN DESIGN & VERIFIED
In prior draft specifications, the Key Derivation Function (KDF) for X3DH used a non-standard custom salt (`"NEXA_X3DH_SALT_V1"`) and custom info string (`"NEXA_HANDSHAKE_ENVELOPE"`). Under the strict mandate to avoid inventing new cryptographic constructions and conform to official Signal specifications, this design conforms strictly to the official Signal X3DH Specification (Revision 1).

### 1.2 Standards vs. Application-Specific Boundaries

| Protocol Component | Official Signal X3DH Specification | NEXA Implementation Choice | Classification |
|---|---|---|---|
| **Curve** | Curve25519 (X25519, RFC 7748) | X25519 (Google Tink / standard Montgomery) | **STANDARD** |
| **Signature Scheme** | Ed25519 (RFC 8032) | Ed25519 (BouncyCastle / RFC 8032) | **STANDARD** |
| **Hash Function** | SHA-256 (for Curve25519) | SHA-256 (RFC 6234) | **STANDARD** |
| **Padded Prefix ($F$)** | 32 bytes of `0xFF` for Curve25519 | Exactly 32 bytes of `0xFF` (`0xFF` $\times 32$) | **STANDARD** |
| **HKDF Salt** | 32 bytes of `0x00` | Exactly 32 zero bytes (`0x00` $\times 32$) | **STANDARD** |
| **HKDF Info** | Application Info String | `"WhisperText"` or `"NexaX3DH"` | **APPLICATION BINDING** |
| **Shared Key Length** | 32 bytes (256 bits) | 32 bytes (256 bits) | **STANDARD** |

---

## 2. Standards-Compliant Mathematical Derivation

### 2.1 Cryptographic Parameters & Keypairs
1. **Alice (Initiator):**
   - Identity Keypair: $IK_A = (sk_{ik_a}, pk_{ik_a})$
   - Ephemeral Keypair: $EK_A = (sk_{ek_a}, pk_{ek_a})$
2. **Bob (Responder):**
   - Identity Keypair: $IK_B = (sk_{ik_b}, pk_{ik_b})$
   - Signed Prekey: $SPK_B = (sk_{spk_b}, pk_{spk_b})$
   - One-Time Prekey (optional): $OPK_B = (sk_{opk_b}, pk_{opk_b})$

### 2.2 Diffie-Hellman Shared Secret Operations
All Diffie-Hellman operations use RFC 7748 scalar multiplication:
$$DH_1 = \text{X25519}(sk_{ik_a}, pk_{spk_b}) = \text{X25519}(sk_{spk_b}, pk_{ik_a})$$
$$DH_2 = \text{X25519}(sk_{ek_a}, pk_{ik_b}) = \text{X25519}(sk_{ik_b}, pk_{ek_a})$$
$$DH_3 = \text{X25519}(sk_{ek_a}, pk_{spk_b}) = \text{X25519}(sk_{spk_b}, pk_{ek_a})$$
$$DH_4 = \text{X25519}(sk_{ek_a}, pk_{opk_b}) = \text{X25519}(sk_{opk_b}, pk_{ek_a}) \quad [\text{if } OPK_B \text{ is present}]$$

### 2.3 Input Keying Material ($KM$) Concatenation
In accordance with Section 2.1 and Section 3.3 of the Signal X3DH Specification:
- **Case 1: One-Time Prekey ($OPK$) Present:**
  $$KM = DH_1 \parallel DH_2 \parallel DH_3 \parallel DH_4 \quad (\text{128 bytes total})$$
- **Case 2: One-Time Prekey ($OPK$) Absent (Exhausted Pool):**
  $$KM = DH_1 \parallel DH_2 \parallel DH_3 \quad (\text{96 bytes total})$$

### 2.4 Padded Prefix ($F$) and HKDF Execution
To satisfy Signal X3DH Section 2.1 for Curve25519:
1. Define prefix $F$:
   $$F = \text{0xFF, 0xFF, \dots, 0xFF} \quad (\text{32 bytes of } \text{0xFF})$$
2. Concatenate $F$ with $KM$:
   $$IKM = F \parallel KM$$
3. Standard RFC 5869 HKDF Extract and Expand using SHA-256:
   $$\text{Salt} = \text{0x00, 0x00, \dots, 0x00} \quad (\text{32 bytes of } \text{0x00})$$
   $$\text{PRK} = \text{HMAC-SHA256}(\text{Salt}, IKM)$$
   $$\text{Info} = \text{"NexaX3DH"}$$
   $$SK = \text{HKDF-Expand}(\text{PRK}, \text{Info}, 32)$$

---

## 3. Known-Answer Test Vectors (Deterministic KAT)

The following test vectors provide exact byte-level verification for both initiator and responder implementations.

### 3.1 Test Keypairs (RFC 7748 Clamped Scalars)

```
Alice Identity Private Key (sk_ik_a):
e81b6727230a66d581729c138b556b63d67f7e9f8a3138b3014a9ec0af96d158
Alice Identity Public Key (pk_ik_a):
f5b324d0dddbf2010f8fc94f690deb4d8d1d1e4b5f0c761e9dd3188b03f5a453

Alice Ephemeral Private Key (sk_ek_a):
904f86d63b2cf9d690a76a5996fc3b745fd903fcb5b4e33433a7e4b2a8d38b4f
Alice Ephemeral Public Key (pk_ek_a):
d8950ac21c1ea2fca01a2bbb4f0e22b1177a516eda1e2db3dadb55aacf032345

Bob Identity Private Key (sk_ik_b):
5034c56858e93ee5b0ad5ec1db98a83a0058b291583ff6e9be1e39a5dcbb746a
Bob Identity Public Key (pk_ik_b):
dd7e0ae6ba82222f9244b5a423426ef1f8ecaacaab4907a08bfe09eaff184d09

Bob Signed Prekey Private (sk_spk_b):
2824bb8b05dc6e7299a8ea8d95ab4db56fc120c169992d9f824143a416ad8869
Bob Signed Prekey Public (pk_spk_b):
060031ab8354902fb7fe6fca9213711d361eddc4de85d9409f6b4eb5b7b1e27e

Bob One-Time Prekey Private (sk_opk_b):
c0879e61b9a8966276708fb5625ffda9ca97e59f49b6b7a2d645e5f39642c159
Bob One-Time Prekey Public (pk_opk_b):
d3e0ababfc79b86e746ef1ef91e3fd71c11d38a650924485a8563fe6ad661e0f
```

---

### 3.2 Test Vector A: Full X3DH with One-Time Prekey ($OPK$) Present

#### Step 1: Diffie-Hellman Computations
- **$DH_1 = \text{X25519}(sk_{ik_a}, pk_{spk_b}) = \text{X25519}(sk_{spk_b}, pk_{ik_a})$:**
  `6631c00839026efb778bf454731e711a955a05949d5bb603ce85e0a6e17f566e`
- **$DH_2 = \text{X25519}(sk_{ek_a}, pk_{ik_b}) = \text{X25519}(sk_{ik_b}, pk_{ek_a})$:**
  `5f13dff3e9abd27ea4a6eeb35c54fbee3e59a2ce39bd73edc39d92fd9aadfc25`
- **$DH_3 = \text{X25519}(sk_{ek_a}, pk_{spk_b}) = \text{X25519}(sk_{spk_b}, pk_{ek_a})$:**
  `62ddb4e36be8d47b0736c193bb68078981cfe29fff4992567021ca24168e5474`
- **$DH_4 = \text{X25519}(sk_{ek_a}, pk_{opk_b}) = \text{X25519}(sk_{opk_b}, pk_{ek_a})$:**
  `01a330ecb310958042735d43c44cc7966996caa746daa00beda9dbce261c0c2f`

#### Step 2: $KM$ and $IKM$ Construction
- **$KM = DH_1 \parallel DH_2 \parallel DH_3 \parallel DH_4$ (128 bytes):**
  `6631c00839026efb778bf454731e711a955a05949d5bb603ce85e0a6e17f566e5f13dff3e9abd27ea4a6eeb35c54fbee3e59a2ce39bd73edc39d92fd9aadfc2562ddb4e36be8d47b0736c193bb68078981cfe29fff4992567021ca24168e547401a330ecb310958042735d43c44cc7966996caa746daa00beda9dbce261c0c2f`
- **$IKM = F \parallel KM$ (160 bytes):**
  `ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff6631c00839026efb778bf454731e711a955a05949d5bb603ce85e0a6e17f566e5f13dff3e9abd27ea4a6eeb35c54fbee3e59a2ce39bd73edc39d92fd9aadfc2562ddb4e36be8d47b0736c193bb68078981cfe29fff4992567021ca24168e547401a330ecb310958042735d43c44cc7966996caa746daa00beda9dbce261c0c2f`

#### Step 3: HKDF Derivation
- **Salt (32 zero bytes):**
  `0000000000000000000000000000000000000000000000000000000000000000`
- **Pseudorandom Key ($PRK = \text{HMAC-SHA256}(\text{Salt}, IKM)$):**
  `b8a9027f02a8e52c4323c9cfa697a03ae3c9738951b7cd84aec4c1cd2d048791`
- **Info String:** `"NexaX3DH"` (`4e65786158334448`)
- **Master Shared Secret ($SK = \text{HKDF-Expand}(PRK, Info, 32)$):**
  `2507051bebe082a43d8743047310d188dc5dfeb2704fd4c0f138deabbcbed4f2`

---

### 3.3 Test Vector B: X3DH with One-Time Prekey ($OPK$) Absent

#### Step 1: Diffie-Hellman Computations
- $DH_1, DH_2, DH_3$ are identical to Vector A.
- $DH_4$ is omitted.

#### Step 2: $KM$ and $IKM$ Construction
- **$KM = DH_1 \parallel DH_2 \parallel DH_3$ (96 bytes):**
  `6631c00839026efb778bf454731e711a955a05949d5bb603ce85e0a6e17f566e5f13dff3e9abd27ea4a6eeb35c54fbee3e59a2ce39bd73edc39d92fd9aadfc2562ddb4e36be8d47b0736c193bb68078981cfe29fff4992567021ca24168e5474`
- **$IKM = F \parallel KM$ (128 bytes):**
  `ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff6631c00839026efb778bf454731e711a955a05949d5bb603ce85e0a6e17f566e5f13dff3e9abd27ea4a6eeb35c54fbee3e59a2ce39bd73edc39d92fd9aadfc2562ddb4e36be8d47b0736c193bb68078981cfe29fff4992567021ca24168e5474`

#### Step 3: HKDF Derivation
- **Salt (32 zero bytes):**
  `0000000000000000000000000000000000000000000000000000000000000000`
- **Pseudorandom Key ($PRK$):**
  `50fc8368f78737b85ba91561b4605f960819eb8773a33b7db8c860e3bcdcfe90`
- **Info String:** `"NexaX3DH"` (`4e65786158334448`)
- **Master Shared Secret ($SK$):**
  `831601209178575514a4e20543a46b426a3ddbb496cd52838fd8e1996a5d8af0`

---

## 4. Maintained Cryptographic Implementation Reference

1. **Android Client:**
   - Use standard `com.google.crypto.tink.subtle.X25519` for all scalar multiplications.
   - Use standard `javax.crypto.Mac` (HmacSHA256) for RFC 5869 HKDF extract and expand.
2. **Go Backend / Integration Testing:**
   - Use standard Go `golang.org/x/crypto/curve25519` and `golang.org/x/crypto/hkdf`.
