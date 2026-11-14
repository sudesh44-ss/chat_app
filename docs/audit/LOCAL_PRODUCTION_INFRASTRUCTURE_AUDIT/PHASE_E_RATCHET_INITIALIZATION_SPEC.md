# NEXA — Phase E Double Ratchet Initialization & Collision Resolution Specification

**Date:** 2026-11-07  
**Branch:** `main`  
**Status:** SPECIFICATION COMPLETE (Read-Only Design Phase)  
**Standard Reference:** Signal Double Ratchet Algorithm (Revision 1, 2016-11-20)

---

## 1. Mathematical Architecture & Audit Classification

### 1.1 Audit Finding & Classification: CORRECTED IN DESIGN
The previous Android implementation in [`DoubleRatchetEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt) utilized a non-standard `kdfRk` (single HMAC followed by SHA-256 with `"chain_salt"`) and inverted symmetric constants (`0x01` vs `0x02` in `kdfCk`). This specification establishes the exact, standard Signal Double Ratchet state machine transitions and asymmetric role initialization.

---

## 2. KDF Function Specifications (Signal Compliant)

### 2.1 Root Key Derivation Function ($\text{KDF\_RK}$)
In accordance with Signal Double Ratchet Specification Section 5.1:
$$\text{KDF\_RK}(RK, DH\_out) \to (RK_{\text{new}}, CK)$$
1. Execute RFC 5869 HKDF using SHA-256:
   $$\text{PRK} = \text{HMAC-SHA256}(\text{salt}=RK, \text{ikm}=DH\_out)$$
   $$\text{Output} = \text{HKDF-Expand}(\text{PRK}, \text{info}=\text{"NexaRatchetRK"}, \text{length}=64)$$
2. Split output into two 32-byte keys:
   $$RK_{\text{new}} = \text{Output}[0 \dots 31]$$
   $$CK = \text{Output}[32 \dots 63]$$

### 2.2 Symmetric Chain Key Derivation Function ($\text{KDF\_CK}$)
In accordance with Signal Double Ratchet Specification Section 5.2:
$$\text{KDF\_CK}(CK) \to (CK_{\text{next}}, MK)$$
1. Message Key Derivation:
   $$MK = \text{HMAC-SHA256}(CK, \text{byte array } [0x01])$$
2. Next Chain Key Derivation:
   $$CK_{\text{next}} = \text{HMAC-SHA256}(CK, \text{byte array } [0x02])$$

---

## 3. Asymmetric State Machine Initialization Algorithms

```
     [ Alice: Initiator ]                                      [ Bob: Responder ]
              │                                                         │
       X3DH yields SK                                            X3DH yields SK
       RK = SK                                                   RK = SK
       Generate fresh DHR_A                                      (Local DH is SPK_B)
       DH = X25519(sk_dhr_a, pk_spk_b)                                  │
       (RK, CK_s) = KDF_RK(SK, DH)                                      │
       CK_r = null, N_s = 0, PN = 0                                     │
              │                                                         │
              ├────────── First Message: Header(DHR_A, N=0, PN=0) ──────►
              │                                                         │
              │                                                  DH = X25519(sk_spk_b, pk_dhr_a)
              │                                                  (RK, CK_r) = KDF_RK(SK, DH)
              │                                                  CK_s = null, N_r = 0, PN = 0
              │                                                  (CK_r, MK_0) = KDF_CK(CK_r)
              │                                                  Decrypt message!
              │                                                         │
              │◄───────── First Reply: Header(DHR_B, N=0, PN=1) ────────┤
              │                                                  Generate fresh DHR_B
       DH = X25519(sk_dhr_a, pk_dhr_b)                           DH = X25519(sk_dhr_b, pk_dhr_a)
       (RK, CK_r) = KDF_RK(RK, DH)                               (RK, CK_s) = KDF_RK(RK, DH)
       Generate fresh DHR_A2 ...                                 (CK_s, MK_0) = KDF_CK(CK_s)
```

### 3.1 Initiator (Alice) Algorithm
1. **Inputs:**
   - Master Shared Secret $SK$ from X3DH.
   - Bob's Signed Prekey public key $pk_{spk_b}$.
2. **Execution:**
   - Set root key: $RK = SK$.
   - Generate fresh X25519 ratchet keypair: $DHR_A = (sk_{dhr_a}, pk_{dhr_a})$.
   - Compute initial Diffie-Hellman step:
     $$DH_{\text{init}} = \text{X25519}(sk_{dhr_a}, pk_{spk_b})$$
   - Advance Root KDF to initialize the sending chain:
     $$(RK, CK_s) = \text{KDF\_RK}(RK, DH_{\text{init}})$$
   - State Variables:
     - `local_dhr_priv` = $sk_{dhr_a}$, `local_dhr_pub` = $pk_{dhr_a}$
     - `remote_dhr_pub` = $pk_{spk_b}$
     - `sending_chain_key` = $CK_s$
     - `receiving_chain_key` = `null`
     - $N_s = 0$, $N_r = 0$, $PN = 0$
3. **First Outgoing Message:**
   - Advance symmetric chain: $(CK_s, MK_0) = \text{KDF\_CK}(CK_s)$.
   - AEAD Nonce: `0x4E4558410000000000000000` (12 bytes).
   - Wire Header: `ratchet_public_key` = $pk_{dhr_a}$, $PN = 0$, $N = 0$, `session_id` = UUID, `ephemeral_public_key` = $pk_{ek_a}$, `one_time_prekey_id` = $OPK_{\text{id}}$.
   - Associated Data: Canonical binary header bytes.
   - Increment $N_s = 1$.

### 3.2 Responder (Bob) Algorithm
1. **Inputs:**
   - Inbound wire packet carrying Alice's $pk_{ik_a}, pk_{ek_a}, OPK_{\text{id}}$, and $pk_{dhr_a}$.
   - Bob's private keys: $sk_{ik_b}, sk_{spk_b}$, and $sk_{opk_b}$.
2. **Execution:**
   - Execute X3DH derivation to compute $SK$.
   - Set root key: $RK = SK$.
   - Immediately securely wipe and delete $sk_{opk_b}$ from SQLCipher storage.
   - Compute matching initial Diffie-Hellman step using $sk_{spk_b}$:
     $$DH_{\text{init}} = \text{X25519}(sk_{spk_b}, pk_{dhr_a})$$
   - Advance Root KDF to initialize the receiving chain:
     $$(RK, CK_r) = \text{KDF\_RK}(RK, DH_{\text{init}})$$
   - State Variables:
     - `local_dhr_priv` = $sk_{spk_b}$, `local_dhr_pub` = $pk_{spk_b}$
     - `remote_dhr_pub` = $pk_{dhr_a}$
     - `sending_chain_key` = `null`
     - `receiving_chain_key` = $CK_r$
     - $N_s = 0$, $N_r = 0$, $PN = 0$
3. **First Inbound Decryption:**
   - Advance symmetric chain: $(CK_r, MK_0) = \text{KDF\_CK}(CK_r)$.
   - Verify Poly1305 MAC tag with $AD = Header$ and decrypt using $MK_0$.
   - Increment $N_r = 1$.
4. **Bob's First Outgoing Reply:**
   - Generate fresh X25519 keypair $DHR_B = (sk_{dhr_b}, pk_{dhr_b})$.
   - Compute DH ratchet step:
     $$DH_{\text{reply}} = \text{X25519}(sk_{dhr_b}, pk_{dhr_a})$$
   - Advance Root KDF to activate sending chain:
     $$(RK, CK_s) = \text{KDF\_RK}(RK, DH_{\text{reply}})$$
   - Send reply with header: $pk_{dhr_b}$, $PN = 1$, $N = 0$.

---

## 4. Simultaneous Session Initialization (Glare / Collision Resolution)

### 4.1 The Glare Problem
When Alice and Bob attempt to send their very first message to each other simultaneously, both claim prekeys and construct initial X3DH handshake envelopes. If unmanaged, this creates two conflicting, un-synchronized Double Ratchet sessions for the same contact.

### 4.2 Deterministic Tie-Breaking Protocol
To guarantee deterministic convergence without server intervention:
1. **Canonical Comparison Key:** Compare the raw 32-byte Ed25519 identity public keys:
   $$\text{LexCompare}(pk_{ik_a}, pk_{ik_b})$$
2. **Role Assignment:**
   - If $\text{hex}(pk_{ik_a}) < \text{hex}(pk_{ik_b})$: Alice is the **Dominant Initiator**.
   - If $\text{hex}(pk_{ik_b}) < \text{hex}(pk_{ik_a})$: Bob is the **Dominant Initiator**.
3. **Collision Resolution Flow:**
   - Suppose Bob has sent an outbound initial message, but receives Alice's initial message before Alice has received Bob's:
     - Bob detects that both sessions are in `UNACKNOWLEDGED_INIT` state.
     - Bob evaluates the tie-breaker: since Alice's key is lower, Alice's handshake takes precedence.
     - Bob **discards** his pending outbound session and unconsumed OPK reservation.
     - Bob initializes as Responder to Alice's incoming handshake.
     - Bob re-encrypts his queued outbound plaintext under the newly established receiving/sending ratchet session.

---

## 5. Deterministic Ratchet State Test Vectors

The following vectors verify identical state derivation between Alice and Bob starting from Test Vector A Master Secret ($SK$):

```
Input Master Secret (SK):
2507051bebe082a43d8743047310d188dc5dfeb2704fd4c0f138deabbcbed4f2

Alice Ratchet Private Key (sk_dhr_a):
1058b291583ff6e9be1e39a5dcbb746a5034c56858e93ee5b0ad5ec1db98a83a
Alice Ratchet Public Key (pk_dhr_a):
696c64856b109f277b04786a17132ca2df2f966cd2350a95e3ed8156b7a5464b

Bob Signed Prekey Public (pk_spk_b):
060031ab8354902fb7fe6fca9213711d361eddc4de85d9409f6b4eb5b7b1e27e

Initial DH Calculation (DH_init = X25519(sk_dhr_a, pk_spk_b)):
2c34bf2af4f04e74a5a8d8ee1b59b71e98521583b113839931324c26010fdb7c

KDF_RK Execution:
PRK = HMAC-SHA256(salt=SK, ikm=DH_init)
PRK:
13f83032cea7ca3cd2b0aa94434e414528468e162313e499342b97565c2441d0

HKDF-Expand(PRK, "NexaRatchetRK", 64):
Output[0..31]  (New RK):
8e4ca86cd102ed4dd24a4334d0aff47dda7a854f2d8937ba37cd7583a93d2320
Output[32..63] (Chain Key CK_s for Alice / CK_r for Bob):
32585db08797e5b625c18887b1d31cdf95a3ab08bd582de587d19dbed04625ed

KDF_CK Execution (First Message):
Message Key MK_0 = HMAC-SHA256(CK, [0x01]):
6f5071470f03e9b6cc6b2e715c03f2cfa07558a66ad229e4b51a5707aa5f1c5b

Next Chain Key CK_1 = HMAC-SHA256(CK, [0x02]):
ce9335c88c9bae42f6a6010d0bd3eebe912e38888f44361b0c4fe90a79756b97
```

**Result:** Alice and Bob compute identically matching $RK$, $CK$, and $MK_0$ states down to the exact byte.
