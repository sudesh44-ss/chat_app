# PHASE D — API IMPLEMENTATION REPORT

## 1. Overview & Authentication Specification

All Phase D API endpoints require standard Bearer token authentication:
```http
Authorization: Bearer <session_auth_token>
```
The handler extracts the session token, verifies it against the `identity.Store` or `sessions` table, retrieves the caller's active `nx_id`, and verifies that the calling account has at least one active, verified device.

---

## 2. API Endpoints Specification

### 2.1 Discover User by NX-ID
- **Endpoint:** `GET /api/v1/users/discover?nx_id={NX_ID}`
- **Rate Limit:** 10 requests per minute per user.
- **Success Response (200 OK):**
```json
{
  "user": {
    "nx_id": "NX-A7K92B4M",
    "display_name": "Bob",
    "avatar_url": "https://cdn.example.com/avatars/bob.jpg",
    "bio": "Security researcher",
    "public_key_hex": "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b",
    "is_connected": false,
    "is_pending": false,
    "is_blocked": false,
    "created_at": 1718000000000
  }
}
```
- **Error Responses:**
  - `400 Bad Request`: Invalid NX-ID format (fails `^NX-[A-Z0-9]{8}$`) or self-lookup attempt.
  - `404 Not Found`: Account does not exist **OR caller is blocked by target user** (anti-enumeration privacy guarantee).
  - `429 Too Many Requests`: Rate limit exceeded.

---

### 2.2 Send Connection Request
- **Endpoint:** `POST /api/v1/connections/request`
- **Rate Limit:** 20 requests per hour per user.
- **Request Body:**
```json
{
  "recipient_nx_id": "NX-A7K92B4M",
  "intro_message": "Hi Bob, let's connect on NEXA."
}
```
- **Success Response (201 Created):**
```json
{
  "request": {
    "id": "req_8f19da218b0e",
    "sender_nx_id": "NX-29AB731Q",
    "recipient_nx_id": "NX-A7K92B4M",
    "intro_message": "Hi Bob, let's connect on NEXA.",
    "status": "PENDING",
    "created_at": 1718001000000,
    "expires_at": 1718605800000
  }
}
```
- **Special Flow — Reciprocal Cross-Request:**
  - If target user has already sent an active pending request to caller, the server auto-accepts both requests and returns `201 Created` with `status: "ACCEPTED"`.
- **Error Responses:**
  - `400 Bad Request`: Self-request attempt.
  - `403 Forbidden`: Target user has blocked caller, or caller has blocked target user.
  - `404 Not Found`: Recipient account does not exist.
  - `409 Conflict`: Active connection or pending request already exists.
  - `429 Too Many Requests`: Rate limit exceeded.

---

### 2.3 List Incoming Connection Requests
- **Endpoint:** `GET /api/v1/connections/requests/incoming?limit=50&offset=0`
- **Success Response (200 OK):**
```json
{
  "requests": [
    {
      "id": "req_8f19da218b0e",
      "sender_nx_id": "NX-A7K92B4M",
      "recipient_nx_id": "NX-29AB731Q",
      "intro_message": "Hi Alice!",
      "status": "PENDING",
      "created_at": 1718001000000,
      "expires_at": 1718605800000,
      "profile": {
        "nx_id": "NX-A7K92B4M",
        "display_name": "Bob",
        "avatar_url": null,
        "bio": null,
        "public_key_hex": "4a5e1e4baab89f3a...",
        "created_at": 1718000000000
      }
    }
  ]
}
```

---

### 2.4 List Outgoing Connection Requests
- **Endpoint:** `GET /api/v1/connections/requests/outgoing?limit=50&offset=0`
- **Success Response (200 OK):**
```json
{
  "requests": [
    {
      "id": "req_112233445566",
      "sender_nx_id": "NX-29AB731Q",
      "recipient_nx_id": "NX-Z8K19B2L",
      "intro_message": "Hey!",
      "status": "PENDING",
      "created_at": 1718002000000,
      "expires_at": 1718606800000,
      "profile": {
        "nx_id": "NX-Z8K19B2L",
        "display_name": "Zoe",
        "public_key_hex": "bbaaccaa..."
      }
    }
  ]
}
```

---

### 2.5 Accept Connection Request
- **Endpoint:** `POST /api/v1/connections/requests/{request_id}/accept`
- **Success Response (200 OK):**
```json
{
  "status": "ACCEPTED",
  "connection_id": "conn_55667788"
}
```
- **Error Responses:**
  - `403 Forbidden`: Caller is not the intended recipient of this request.
  - `404 Not Found`: Request not found or has expired.
  - `409 Conflict`: Request is not in `PENDING` state.

---

### 2.6 Reject Connection Request
- **Endpoint:** `POST /api/v1/connections/requests/{request_id}/reject`
- **Success Response (200 OK):**
```json
{
  "status": "REJECTED"
}
```
- **Error Responses:**
  - `403 Forbidden`: Caller is not the intended recipient of this request.
  - `404 Not Found`: Request not found.

---

### 2.7 Cancel Outgoing Connection Request
- **Endpoint:** `POST /api/v1/connections/requests/{request_id}/cancel`
- **Success Response (200 OK):**
```json
{
  "status": "CANCELLED"
}
```
- **Error Responses:**
  - `403 Forbidden`: Caller is not the sender of this request.
  - `404 Not Found`: Request not found.

---

### 2.8 List Established Connections (Contacts)
- **Endpoint:** `GET /api/v1/connections?limit=100&offset=0`
- **Success Response (200 OK):**
```json
{
  "connections": [
    {
      "nx_id": "NX-A7K92B4M",
      "display_name": "Bob",
      "avatar_url": "https://cdn.example.com/bob.jpg",
      "bio": "Dev",
      "public_key_hex": "4a5e1e4baab...",
      "is_connected": true,
      "created_at": 1718005000000
    }
  ]
}
```

---

### 2.9 Remove Connection
- **Endpoint:** `DELETE /api/v1/connections/{target_nx_id}`
- **Success Response (200 OK):**
```json
{
  "status": "REMOVED"
}
```
- **Behavior:** Updates relationship status to `REMOVED`. Preserves chat history if desired by client and permits future connection requests.

---

### 2.10 Block User
- **Endpoint:** `POST /api/v1/users/{target_nx_id}/block`
- **Request Body (optional):** `{"reason": "Spam"}`
- **Success Response (200 OK):**
```json
{
  "status": "BLOCKED"
}
```
- **Behavior:**
  1. Inserts record into `user_blocks`.
  2. Sets any active connection between the two users to `REMOVED`.
  3. Rejects any pending connection requests in either direction.

---

### 2.11 Unblock User
- **Endpoint:** `DELETE /api/v1/users/{target_nx_id}/block`
- **Success Response (200 OK):**
```json
{
  "status": "UNBLOCKED"
}
```

---

### 2.12 List Blocked Users
- **Endpoint:** `GET /api/v1/users/blocked`
- **Success Response (200 OK):**
```json
{
  "blocked_users": [
    {
      "nx_id": "NX-BADUSER1",
      "display_name": "Spammer",
      "public_key_hex": "1234...",
      "created_at": 1718006000000
    }
  ]
}
```

---

## 3. Real-Time WebSocket Notification Events

Whenever relationship state changes occur, `hub.BroadcastEventToNxID` dispatches lightweight real-time notification frames over active WebSockets:
- `connection:request_received`: Sent to recipient when a new request arrives.
- `connection:accepted`: Sent to sender when their request is accepted.
- `connection:rejected`: Sent to sender if rejected.
- `connection:removed`: Sent to peer when connection is removed.
- `user:blocked`: Sent to target user to invalidate presence / connection state.
