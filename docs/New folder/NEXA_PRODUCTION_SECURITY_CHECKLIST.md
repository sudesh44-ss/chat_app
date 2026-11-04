# NEXA — Production Security & Cryptographic Integrity Checklist

**Security Standard:** Zero-Knowledge, Zero-Cloud-Disk, End-to-End Encrypted (E2EE) Messaging

---

## 1. Cryptographic Protocol Preservation

| Security Layer | Cryptographic Protocol | Enforcement State |
| :--- | :--- | :---: |
| **Long-Term Identity** | Ed25519 (32-byte public key) | **PRESERVED** |
| **Ephemeral Key Exchange** | X25519 / X3DH (Prekey bundles) | **PRESERVED** |
| **Session Ratchet** | Double Ratchet (KDF chain) | **PRESERVED** |
| **Authenticated Encryption**| ChaCha20-Poly1305 (AEAD) | **PRESERVED** |
| **Blind Postman Forwarding** | Zero-Disk RAM Queue (Ephemeral 24h TTL) | **PRESERVED** |
| **Direct P2P File Transfer** | WebRTC DataChannels (DTLS-SRTP, no cloud storage) | **PRESERVED** |

---

## 2. Cloud Deployment Security Verification

- [x] **HTTPS Only in Production**: Render reverse-proxy enforces TLS 1.3 for all REST requests.
- [x] **WSS Only for WebSockets**: WebSocket communication upgraded over TLS (`wss://`).
- [x] **Zero Plaintext Secrets in Client APK**:
  - `TWILIO_AUTH_TOKEN`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_JWT_SECRET` are never bundled in Android resources.
- [x] **Rate Limiting & Abuse Prevention**:
  - OTP requests throttled (3 requests per phone number, sliding-window IP limits).
- [x] **No Leaked Internal Errors**:
  - Production error messages avoid leaking raw SQL queries, stack traces, or internal server paths.
- [x] **Ed25519 Authentication Challenge**:
  - WebSocket `/ws` connections require signing an ephemeral 32-byte cryptographic challenge within 60 seconds; unverified sockets are forcibly severed.
- [x] **Safe Health Check**:
  - `/health` exposes only uptime status and RAM mode; exposes zero credentials, environment variables, or private data.
