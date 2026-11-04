# NEXA — Contact Discovery & Privacy Architecture Audit

**Audit Date:** November 4, 2026  
**Auditor:** Privacy Engineer & Cybersecurity Auditor  
**Subject:** Phone Contact Discovery, NX-ID Lookup & Zero-Address-Book-Upload Policy  

---

## 1. Zero-Address-Book-Upload Privacy Policy

A core architectural pillar of NEXA is **Zero-Cloud Contact Storage**:
> **Strict Mandate**: The user's complete phone address book must never be uploaded to Supabase or stored in the NEXA server database.

### Architectural Rules:
1. **Local Address Book Stays Local**: Device contacts (names, phone numbers, notes) remain solely inside Android's local Contacts Provider and in-memory application scope.
2. **No Cloud Social Graph**: The server does not build, store, or infer the user's personal address book graph.
3. **No Bulk Contact Syncing**: The Android application does not upload or synchronize the full contact list to any cloud service.
4. **Minimal Lookup Request**:
   To discover which contacts are registered on NEXA, the client transmits only normalized phone numbers (`+1234567890`) over HTTPS in authenticated, rate-limited batches.
5. **Zero Server Logging of Plaintext Numbers**: The Go backend does not log or persist the queried phone numbers.
6. **Minimal Response**: The server returns only matching records for verified and discoverable NEXA accounts (`nx_id`, `display_name`, `public_key_hex`). Unregistered numbers are omitted from the response, preventing registration disclosure.

---

## 2. Contact Discovery Vectors Comparison

| Discovery Vector | Current Status | Privacy Characteristics | Mechanics |
|---|---|---|---|
| **Verified Phone Number** | **GAP / IMPLEMENTING** | High Privacy: Local address book query + minimal server match | Client queries `POST /api/v1/contacts/lookup`; server returns verified registered accounts only. |
| **Public NX-ID** | **PARTIAL** | High Privacy: Exact matching only | Target user shares their public `NX-XXXXXXXX`; server looks up exact profile without enumeration. |
| **Instant Cryptographic QR** | **PASS** | Maximum Privacy: Completely offline handshake | Scanned QR contains signed identity bundle; verifies via Ed25519 signature with Day-0 trust. |

---

## 3. Anti-Enumeration & Rate-Limiting Controls
To prevent malicious actors from harvesting registered phone numbers:
1. **Mandatory Authentication**: Lookup API requires a valid Supabase / NEXA session token. Anonymous callers are rejected (`401 Unauthorized`).
2. **Strict Rate Limiting**: Limit lookup queries to a maximum of 10 requests per user per hour, with batch sizes capped at 100 phone numbers per request.
3. **Verified Accounts Only**: Unverified phone numbers or pending OTP records are never returned as registered NEXA users.
4. **No Reverse Name Resolution**: The server never returns or stores contact display names from the address book.
