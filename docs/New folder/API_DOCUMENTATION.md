# NEXA — Backend REST & WebSocket API Specification

## 1. Overview
The NEXA backend exposes a hardened HTTP/REST and WebSocket signaling interface designed for zero-trust privacy and authenticated operations.

Base URL: `https://api.nexa.internal/api/v1` (or local development: `http://localhost:8080/api/v1`)

---

## 2. Authentication Endpoints

### 2.1 Verify Supabase Auth Session
Exchanges a Supabase Auth JWT for a NEXA backend device session token and allocates an NX-ID if registering for the first time.

- **Method**: `POST /api/v1/auth/supabase/verify`
- **Headers**: `Content-Type: application/json`
- **Request Body**:
```json
{
  "supabase_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "device_id": "dev_phone_pixel_9",
  "device_name": "Google Pixel 9 Pro"
}
```
- **Response `200 OK`**:
```json
{
  "session_token": "nxt_8f192b0c441a4a1599...",
  "user_id": "usr_9981a2f4",
  "nx_id": "NX-29AB731Q",
  "display_name": "Alice Sharma",
  "phone_verified": true,
  "expires_at": 1731283200
}
```
- **Response `401 Unauthorized`**:
```json
{
  "error": "invalid_token",
  "message": "Supabase JWT signature validation failed"
}
```

---

## 3. Profile & Identity Endpoints

### 3.1 Get Current User Profile
Retrieves the authenticated profile associated with the bearer session token.

- **Method**: `GET /api/v1/profile`
- **Headers**: `Authorization: Bearer <session_token>`
- **Response `200 OK`**:
```json
{
  "user_id": "usr_9981a2f4",
  "nx_id": "NX-29AB731Q",
  "display_name": "Alice Sharma",
  "phone_number": "+919876543210",
  "phone_verified": true,
  "bio": "Building local-first secure software",
  "gender": "Female",
  "created_at": 1730000000
}
```

### 3.2 Update User Profile
- **Method**: `PUT /api/v1/profile`
- **Headers**: `Authorization: Bearer <session_token>`, `Content-Type: application/json`
- **Request Body**:
```json
{
  "display_name": "Alice S.",
  "bio": "Privacy-first enthusiast",
  "is_discoverable_by_phone": true
}
```
- **Response `200 OK`**:
```json
{
  "status": "updated",
  "nx_id": "NX-29AB731Q"
}
```

---

## 4. Privacy-Preserving Contact Discovery Endpoints

### 4.1 Batch Contact Phone Lookup
Allows an authenticated user to discover which phone numbers from their address book belong to active, verified NEXA users.

- **Method**: `POST /api/v1/contacts/lookup`
- **Headers**: `Authorization: Bearer <session_token>`, `Content-Type: application/json`
- **Constraint**: Maximum 100 normalized phone numbers per request.
- **Request Body**:
```json
{
  "phone_numbers": [
    "+919876543210",
    "+919876543211",
    "+14155552671"
  ]
}
```
- **Response `200 OK`**:
```json
{
  "matches": [
    {
      "phone_number": "+919876543210",
      "nx_id": "NX-29AB731Q",
      "display_name": "Alice Sharma",
      "public_key_hex": "b5a41d3f9e..."
    }
  ]
}
```
*Note: Unregistered phone numbers or users who disabled `is_discoverable_by_phone` are completely omitted from the response. The server logs zero address-book details.*

---

## 5. WebSocket Signaling Endpoints

### 5.1 Real-Time Signal Relay
- **Protocol**: `WSS /ws/signaling`
- **Headers**: `Sec-WebSocket-Protocol: nexa-v1`, `Authorization: Bearer <session_token>`
- **Payload Format**: Binary Protobuf (`NexaWirePacket`).
- **Envelopes Handled**:
  - `PACKET_MESSAGE_ENVELOPE` (49-byte header + ciphertext)
  - `PACKET_TYPING` (Typing indicator)
  - `PACKET_DELIVERY_ACK` (Delivery receipt)
  - `PACKET_READ_RECEIPT` (Read acknowledgment)
  - `PACKET_DEVICE_SYNC` (Multi-device state ratchet sync)
