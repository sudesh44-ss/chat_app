# NEXA — Phase E Step 3 Authenticated REST API Reference

**Date:** 2026-11-07  
**Branch:** `main`  
**Phase:** Phase E — Step 3  
**Base URL:** `https://<host>/api/v1`

---

## 1. Authentication & Security Policy

All endpoints in this specification require:
- **Authorization Header:** `Authorization: Bearer <session_token>`
- **Fail-Closed Validation:** The server rejects requests if the session token is revoked, expired, or if the calling device is revoked.
- **Privacy Guarantee:** The server only exposes public keys. No private keys, seeds, or ratchet shared secrets are ever accepted or returned.

---

## 2. API Endpoints

### 2.1 Upload Identity DH Key Binding (`POST /api/v1/users/identity-key`)
Binds an authenticated X25519 identity DH public key to the account's Ed25519 signing key.

- **Request Headers:**
  - `Content-Type: application/json`
  - `Authorization: Bearer <session_token>`
- **Request Body:**
  ```json
  {
    "dh_public_key": "4a7f... (64-hex X25519 public key)",
    "signature": "c8d2... (128-hex Ed25519 signature over canonical TLV)",
    "timestamp": 1799292800000
  }
  ```
- **Responses:**
  - `200 OK`:
    ```json
    {
      "status": "SUCCESS",
      "message": "Identity key binding authenticated and registered successfully",
      "nx_id": "NX-ALICE123",
      "device_id": "dev_01a2"
    }
    ```
  - `400 Bad Request`: Invalid public key size (must be 32 bytes) or signature verification failed.
  - `401 Unauthorized`: Missing or revoked session token.

---

### 2.2 Upload Signed Prekey (`POST /api/v1/users/prekeys/signed`)
Uploads a medium-term Signed Prekey ($SPK$). Deactivates any prior active SPK for the calling device.

- **Request Body:**
  ```json
  {
    "key_id": 1,
    "public_key": "2e4a... (64-hex X25519 public key)",
    "signature": "d5e3... (128-hex Ed25519 signature over canonical TLV)",
    "timestamp": 1799292800000
  }
  ```
- **Responses:**
  - `200 OK`:
    ```json
    {
      "status": "SUCCESS",
      "message": "Signed prekey authenticated and registered successfully",
      "key_id": 1,
      "expires_at": 1800502400000
    }
    ```
  - `400 Bad Request`: Cryptographic Ed25519 signature verification failed.

---

### 2.3 Batch Upload One-Time Prekeys (`POST /api/v1/users/prekeys/one-time`)
Uploads a batch of up to 100 One-Time Prekeys ($OPK$).

- **Request Body:**
  ```json
  {
    "prekeys": [
      { "key_id": 1, "public_key": "8a1b... (64-hex X25519)" },
      { "key_id": 2, "public_key": "9f2c... (64-hex X25519)" }
    ]
  }
  ```
- **Responses:**
  - `200 OK`:
    ```json
    {
      "status": "SUCCESS",
      "uploaded_count": 2,
      "available_count": 52
    }
    ```
  - `400 Bad Request`: Batch size exceeds 100 or invalid public key length.

---

### 2.4 Combined Prekey Upload (`POST /api/v1/users/prekey`)
All-in-one registration endpoint uploading identity key binding, active SPK, and an initial OPK batch in a single HTTP request.

- **Request Body:**
  ```json
  {
    "identity_key": "4a7f... (64-hex)",
    "identity_key_sig": "c8d2... (128-hex)",
    "signed_prekey": "2e4a... (64-hex)",
    "signed_prekey_id": 1,
    "signed_prekey_sig": "d5e3... (128-hex)",
    "timestamp": 1799292800000,
    "one_time_prekeys": [
      { "key_id": 1, "public_key": "8a1b... (64-hex)" }
    ]
  }
  ```
- **Responses:**
  - `200 OK`: `{"status": "SUCCESS", "message": "Prekey bundle registered successfully"}`

---

### 2.5 Get Public Prekey Bundle (`GET /api/v1/users/{nxId}/prekey-bundle`)
Idempotent public prekey lookup. **NEVER returns or consumes a One-Time Prekey ($OPK$)**.

- **URL Query Parameters:** `device_id` (optional, defaults to active primary device).
- **Responses:**
  - `200 OK`:
    ```json
    {
      "nx_id": "NX-BOB45678",
      "device_id": "dev_bob_01",
      "identity_key": "5b8f... (64-hex X25519)",
      "signing_key": "9c1a... (64-hex Ed25519)",
      "signed_prekey": "2e4a... (64-hex X25519)",
      "signed_prekey_id": 1,
      "signed_prekey_sig": "d5e3... (128-hex Ed25519 signature)"
    }
    ```
  - `403 Forbidden`: Communication is blocked between caller and target.
  - `404 Not Found`: Prekey bundle not registered or inactive.

---

### 2.6 Claim One-Time Prekey (`POST /api/v1/prekeys/claim`)
Atomically claims and discloses an available One-Time Prekey ($OPK$). Strictly adheres to the **Mandatory OPK Security Rule**.

- **Rate Limit:** 60 claims / minute per user account.
- **Request Body:**
  ```json
  {
    "recipient_nx_id": "NX-BOB45678",
    "target_device_id": "dev_bob_01",
    "claim_id": "urn:uuid:123e4567-e89b-12d3-a456-426614174000"
  }
  ```
- **Responses:**
  - `200 OK`:
    ```json
    {
      "claim_id": "urn:uuid:123e4567-e89b-12d3-a456-426614174000",
      "initiator_nx_id": "NX-ALICE123",
      "initiator_device_id": "dev_alice_01",
      "target_nx_id": "NX-BOB45678",
      "target_device_id": "dev_bob_01",
      "identity_key": "5b8f... (X25519)",
      "signing_key": "9c1a... (Ed25519)",
      "signed_prekey": "2e4a... (X25519)",
      "signed_prekey_id": 1,
      "signed_prekey_sig": "d5e3...",
      "one_time_prekey": "8a1b... (X25519, null if pool exhausted)",
      "one_time_prekey_id": 14,
      "expires_at": 1799293100000
    }
    ```
  - `403 Forbidden`: `claim_id` ownership violation.
  - `409 Conflict`: Claim has already been consumed.
  - `410 Gone`: Claim has expired and its OPK is permanently burned.
  - `429 Too Many Requests`: Rate limit exceeded.
