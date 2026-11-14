# NEXA — Formal Threat Model & Trust Boundary Specification
**Project:** NEXA Secure Mobile Messenger & Multi-Hop DTN Mesh  
**Workspace:** `H:\chat_app`  
**Phase:** K–L — Security, Abuse Prevention & Multi-Device Testing  
**Framework:** STRIDE & DREAD Threat Modeling  
**Status:** COMPLETE & INDEPENDENTLY VERIFIED  

---

## 1. System Overview & Core Invariants

NEXA is a privacy-preserving, zero-trust mobile messaging system combining multi-hop Delay-Tolerant Networking (DTN), peer-to-peer Wi-Fi Direct/BLE mesh networking, and an ephemeral Cloud Gateway relay.

### Core Security Invariants
1. **Zero Plaintext Invariant:** The central server, intermediate DTN relays, and network carriers must NEVER possess plaintext messages, conversation keys, or master private identity keys.
2. **Custody != Delivery Invariant:** Relay custody acceptance (`PACKET_CUSTODY_ACK`) must NEVER mark a message as delivered to the recipient. Only an authenticated recipient receipt (`PACKET_ACK`) can advance delivery state to `DELIVERED`.
3. **Strict Revocation Invariant:** Once a device or session is marked revoked, it must immediately lose access to REST APIs, WebSocket streams, and pairwise routing token deliveries.
4. **Single-Use Ticket Invariant:** Registration auth tickets, OTP codes, and Double Ratchet message keys must be consumed exactly once. Replay attempts must be strictly rejected.

---

## 2. Threat Actors & Capabilities

| Threat Actor | Description | Capabilities | Primary Motivation |
| :--- | :--- | :--- | :--- |
| **Untrusted DTN Mule** | Stranger node carrying store-and-forward envelopes. | Can intercept, drop, duplicate, delay, or tamper with binary envelopes. | Eavesdropping, message tampering, relay denial of service. |
| **Compromised Cloud Server** | Central Cloud Gateway under hostile control. | Can inspect WebSocket packets, tamper with routing tokens, drop messages, spoof server ACKs. | User tracking, traffic analysis, mass surveillance. |
| **Active Network Attacker (MITM)** | Malicious Wi-Fi router, rogue cellular base station. | Can sniff packets, inject forged cleartext HTTP, alter TLS payloads if CA compromised. | Intercepting credentials, session hijacking, replay attacks. |
| **Malicious Peer** | Connected contact or rogue authenticated user. | Can send large spam payloads, manipulate packet IDs, spoof delivery ACKs, scrape contacts. | User harassment, resource exhaustion, phone number harvesting. |
| **Revoked Sibling Device** | Stolen or decommissioned secondary user device. | Possesses old cryptographic keys and session tokens from before revocation. | Unauthorized conversation history access, impersonation. |
| **Device Thief / Physical Attacker** | Actor with physical custody of unlocked/locked device. | Can trigger OS auto-backup, inspect notifications, attempt SQLite database extraction. | Extracting private identity keys, reading stored chat history. |

---

## 3. Trust Boundaries & Attack Surfaces

```
+---------------------------------------------------------------------------------------+
|                                    USER DEVICE                                        |
|  +-------------------------+     +------------------------+     +------------------+  |
|  | Android Keystore (TEE)  | <-> | Local SQLite SQLCipher | <-> | Double Ratchet   |  |
|  | Master Identity Keys    |     | Encrypted Room DB      |     | Session Engine   |  |
|  +-------------------------+     +------------------------+     +------------------+  |
+---------------------------------------------------------------------------------------+
                                        |  [TRUST BOUNDARY 1: App to Network]
                                        v
+---------------------------------------------------------------------------------------+
|                              MULTI-TRANSPORT NETWORK                                  |
|  [Internet WebSocket]  |  [Cellular Binary SMS]  |  [Offline BLE / Wi-Fi Mesh Relay]  |
+---------------------------------------------------------------------------------------+
                                        |  [TRUST BOUNDARY 2: Network to Server]
                                        v
+---------------------------------------------------------------------------------------+
|                                  GO BACKEND SERVER                                    |
|  +-------------------------+     +------------------------+     +------------------+  |
|  | Ephemeral Hub (RAM)     | <-> | Redis Offline Queue    | <-> | PostgreSQL DB    |  |
|  | Blind-Postman Routing   |     | (24h Encrypted Buffer) |     | User / Device ID |  |
|  +-------------------------+     +------------------------+     +------------------+  |
+---------------------------------------------------------------------------------------+
```

### Attack Surfaces
1. **WebSocket Ingestion Endpoint (`/ws`):** Receives raw wire packets from connected devices.
2. **REST API Endpoints (`/api/v1/...`):** OTP verification, user resolution, connection management, media ticket issuance, DTN dump ingestion.
3. **DTN Binary Wire Protocol (`DtnEnvelope`):** Multi-hop envelopes passed across untrusted physical peers.
4. **Local Mobile Storage:** Room databases, shared preferences, Android OS cloud backup pipelines.
5. **System Notifications:** Android notification tray and lock screen display.

---

## 4. STRIDE Threat Analysis & Mitigations

| Threat Category | Specific Threat | Vector | Implemented Mitigation | Verification Status |
| :--- | :--- | :--- | :--- | :--- |
| **Spoofing** | Forged sender identity on DTN envelopes. | Attacker fabricates an envelope claiming to be Alice. | NXDT v1 requires 64-byte Ed25519 digital signature over canonical header and payload SHA-256. | PASS (`NxdtEnvelopeSecurityTests.kt`) |
| **Spoofing** | Spoofed Delivery ACK. | Rogue client sends ACK claiming message delivered to another user. | `Hub.RouteDecodedPacket` verifies `Ack.RecipientNxId == sender.NxId` and drops mismatched ACKs. | PASS (`security_audit_test.go`) |
| **Tampering** | DTN payload modification by intermediate relay. | Untrusted relay mutates encrypted payload bytes. | Recipient verifies payload SHA-256 digest and Double Ratchet AEAD MAC tag. | PASS (`SecurityAndMultiDeviceTests.kt`) |
| **Tampering** | Hop count modification breaking sender signature. | Relay decrements `hopCount`. | Mutable relay metadata is excluded from canonical signing data. | PASS (`NxdtEnvelopeSecurityTests.kt`) |
| **Repudiation** | Denying sending a connection request or message. | Malicious sender claims server fabricated message. | Digital signatures bound to sender's public key on wire packets and envelopes. | PASS (Verified) |
| **Information Disclosure** | Scraping registered phone numbers. | Unauthenticated queries to `/api/v1/users/resolve`. | Endpoint requires valid Bearer session token; unauthenticated calls return 401 Unauthorized. | PASS (`security_audit_test.go`) |
| **Information Disclosure** | Lock screen notification leakage. | Plaintext exposed on lock screen. | `NexaNotificationDispatcher` forces `VISIBILITY_PRIVATE` with generic placeholder `"NEXA: 1 new encrypted message"`. | PASS (`SecurityAndMultiDeviceTests.kt`) |
| **Information Disclosure** | Cloud auto-backup data leakage. | Android OS backups unencrypted SQLite DBs to Google Drive. | `backup_rules.xml` and `data_extraction_rules.xml` exclude `database` and `sharedpref`. | PASS (Verified) |
| **Denial of Service** | Memory exhaustion via oversized payloads. | Attacker submits gigabyte payload to `/api/v1/relay/opportunistic-dump`. | `http.MaxBytesReader` caps requests: 10MB DTN dump, 64KB connection requests, 64KB media. | PASS (`dtn_handler_test.go`) |
| **Denial of Service** | OTP SMS spam & provider credit depletion. | Automated scripts request OTP repeatedly. | Rate limited to 5/phone/hour, 15/IP/hour, 60s cooldown; 3-attempt lockout. | PASS (`security_audit_test.go`) |
| **Elevation of Privilege** | Reconnecting with revoked device. | User de-authorizes phone; attacker uses old session. | Backend checks device status on challenge-response and session authentication; rejects revoked devices. | PASS (`security_audit_test.go`) |

---

## 5. DREAD Risk Scoring Summary

| Threat | Damage | Reproducibility | Exploitability | Affected Users | Discoverability | Score | Risk Level |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| Unauthenticated Phone Scraping (Pre-fix) | 8 | 10 | 10 | 9 | 9 | **9.2** | **CRITICAL (Remediated)** |
| Unauthenticated Media Ticket (Pre-fix) | 7 | 10 | 9 | 8 | 8 | **8.4** | **HIGH (Remediated)** |
| Android OS Backup Plaintext Leak (Pre-fix) | 9 | 8 | 8 | 9 | 7 | **8.2** | **HIGH (Remediated)** |
| Oversized DTN Dump Crash (Pre-fix) | 8 | 9 | 8 | 10 | 6 | **8.2** | **HIGH (Remediated)** |
| Delivery ACK Spoofing | 7 | 8 | 6 | 6 | 5 | **6.4** | **MEDIUM (Mitigated)** |
