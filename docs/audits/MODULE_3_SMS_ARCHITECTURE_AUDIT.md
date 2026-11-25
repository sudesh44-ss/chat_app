# NEXA — Module 3: Encrypted SMS Fallback & Smart SMS Engine
## Complete Architecture Audit, Gap Analysis & Policy Eligibility Report

**Date:** 2026-11-14
**Project:** NEXA Local-First E2EE Privacy Messaging Platform
**Module:** Module 3 — Encrypted SMS Fallback & Smart SMS Engine
**Execution Context:** Android client (`app/src/main/java/com/nexa/app`), local SQLCipher Room database, existing Double Ratchet / X3DH E2EE, existing multi-transport router (`TransportMedium`, `TransportRouter`), existing Go relay (audited read-only)
**Status:** ARCHITECTURE AUDIT COMPLETE (Phase 1). Implementation proceeds under the constraints recorded here.

---

## 0. Mandatory reading — what was actually available

The request names `NEXA Brain.zip`, `1architecture.md`, `PRD.md`, `architecture.md`, `design.md`, `rules.md`, `phases.md`, `memory.md`, `tasks.md`, `specs.md`, `testing.md`, `decisions.md`.

**Finding: none of those files, and no ZIP archive, exist in this workspace.**

Verified by repository-wide glob for `**/*.zip` (0 matches) and for each named file (0 matches). The workspace instead contains a large, coherent documentation tree that serves the same purpose and was used as the authority set:

| Authority document | Role in this audit |
| :--- | :--- |
| `docs/work/IMPLEMENTATION_STATUS.md` | Canonical component status / traceability matrix (600/600 unit tests claimed, Room v24 at that point) |
| `docs/work/AUTHENTICATION_ARCHITECTURE.md`, `IDENTITY_ARCHITECTURE.md` | Identity, NX-ID, device binding |
| `docs/audits/MODULE_2_INTERNET_MESSAGING_AUDIT.md` | Most recent module-level architecture contract: zero-server-plaintext, single multiplexed socket, blind relay, local SQLCipher source of truth |
| `docs/Complete Zero-Knowledge Privacy Gatekeepers & Multi-Transport Chat Architecture.md` | Multi-transport message pipeline |
| `docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/DELIVERY_STATE_MACHINE.md`, `DELIVERY_ACK_PROTOCOL.md`, `ACK_AUTHENTICATION_AND_REPLAY_PROTECTION.md`, `MULTI_TRANSPORT_DEDUPLICATION.md` | Delivery-state and ACK contracts reused by this module |
| `docs/work/SECURITY_DECISIONS.md` | Security decision log |
| `docs/audit/LOCAL_PRODUCTION_INFRASTRUCTURE_AUDIT/NEXA_KNOWN_LIMITATIONS.md` | Known platform limitations |
| `docs/docsss/Reverse SMS-Phone Number Verification Architecture Audit Report.md` | Prior SMS decision: zero-permission `ACTION_SENDTO` only |

**Consequence:** the audit below is derived from the real source tree and this documentation set. Where the request refers to a specific rule number in a missing `1architecture.md`, the equivalent constraint from the existing module-2 contract is cited instead. This gap is recorded as a documentation debt (see §12).

---

## 1. Already implemented functionality (reusable, verified in source)

### 1.1 Identity, authentication, device binding — DO NOT MODIFY
- `backend/internal/identity/nxid.go` — deterministic NX-ID (SHA-256 + Crockford Base32, zero PII).
- Android `SecureStorage` + `CryptoKeystoreManager` (`getOrCreateIdentityKeyPair`, `getOrCreateDhIdentityKeyPair`) — Ed25519 identity key and an independent X25519 DH identity key, both AES-256-GCM wrapped by an `AndroidKeyStore` master key (`nexa_master_vault_key`). Raw private key material is decrypted only inside `withDecryptedPrivateKey { }` and wiped.
- `ContactEntity` with `ContactStatus { REQUEST_SENT, REQUEST_RECEIVED, MUTUAL_FRIEND, BLOCKED, CONTACT_VERIFIED }`, `publicKeyHex` (Ed25519), `phoneNumber`, and privacy flags `isPhoneInLocalAddressBook` / `isPhoneSharedByPeer` exposed through `shouldShowPhoneNumber`.

**Conclusion:** authenticated peer identity and an at-rest-protected asymmetric keypair already exist. No new login, OTP or identity architecture is required or permitted.

### 1.2 E2EE (Internet)
- `DoubleRatchetEngine` (623 lines): X25519 DH ratchet, HKDF-SHA256 `KDF_RK`, HMAC-SHA256 `KDF_CK`, ChaCha20-Poly1305 AEAD with 12-byte deterministic nonce, header-bound associated data (`NEXA_HDR_V1:`), skipped-key store for out-of-order delivery, per-session mutex, state never advanced on auth failure.
- `EncryptedEnvelope` — fixed 49-byte overhead binary envelope; `toProtobufPayload()` bridges to the wire protobuf.
- `ChaChaAeadCipher` — BouncyCastle ChaCha20-Poly1305, returns `(ciphertext, tag)` separately, returns `null` on tag mismatch.

**Conclusion:** reviewed AEAD + KDF primitives already exist and are directly reusable for a *separate* SMS transport session. The live ratchet must not be mutated by SMS (§4.3).

### 1.3 Local persistence
- Room + SQLCipher (`NexaDatabase`, `nexa_encrypted_vault.db`), **version 27**, explicit sequential non-destructive migrations v1→v27, `exportSchema = false`.
- `messages` (`packetId` unique PK, `conversationId`, `transportType`, `localSequence`, reply metadata, pin/star/delete flags), `chats`, `contacts`, `message_dedup_index` (authoritative cross-transport dedup), `message_transport_observations`, `ratchet_states`, `skipped_keys`.

### 1.4 Existing SMS-adjacent code
| Component | State | Notes |
| :--- | :--- | :--- |
| `core/sms/SmsConstants.kt` | Implemented | Port 8901 **binary** Data SMS constants, 133-byte PDU ceiling |
| `core/sms/SmsPayloadCodec.kt` | Implemented | 12-byte magic header + 5-byte compact CRC-8 header, `ChunkReassembler` (10-min TTL, 100 sessions) |
| `data/remote/sms/NexaDataSmsSender.kt` | Implemented but **fail-closed** | Returns `UnsupportedOperationException` unless `SEND_SMS` granted; build deliberately omits the permission |
| `data/remote/sms/NexaDataSmsReceiver.kt` | **Deactivated** (`@Deprecated`, not in manifest) | Removed to eliminate `RECEIVE_SMS` |
| `data/transport/SmsTransport.kt`, `TransportRouter.kt` | Implemented, partially used | `OutboundRoute.DATA_SMS` path exists |
| `domain/model/TransportType` | `INTERNET`, `DATA_SMS`, `DTN_MESH` | Reused as-is for SMS bubbles (`DATA_SMS` is the persisted SMS transport tag) |
| `presentation/.../TransportSelectorMenu.kt`, `NexaTransportSelector.kt` | Implemented | SMS option rendered disabled ("Play Protect compliance") |
| `presentation/.../SmsFailureBottomSheet.kt` | Implemented | Multi-transport retry remediation sheet |
| `presentation/.../MessageTransportBadge.kt` | Implemented | `✉️ SMS` badge on bubbles |
| `DataSmsTransportUnitTests`, `DataSmsSafetyGatingUnitTests` | Implemented | Cover PDU limits, out-of-order chunking, fail-closed gating |

### 1.5 Transport / ingest / ACK infrastructure
- `TransportMedium { INTERNET_WS, CELLULAR_DATA_SMS, OFFLINE_MESH }` and `TransportAdapter`.
- `NexaUniversalTransportIngest` — persistent dedup gate, Double Ratchet decrypt, atomic persistence, Ed25519-signed delivery ACK (`AckDigestHelper`), DTN custody ACK, PIN/PROFILE control packets.
- `MessageStatus { CREATED, QUEUED, RELAYED, DELIVERED, READ, FAILED, EXPIRED }` + legacy aliases.

---

## 2. Partially implemented functionality

1. **Transport selection UX** — the selector exposes three transports, but SMS is permanently disabled and no sheet, consent or capability model exists for an SMS *fallback*.
2. **SIM awareness** — **no SIM/subscription code exists anywhere in the Android module.** No `SubscriptionManager`, no `SmsManager` per-subscription access, no SIM-removal handling. The `sim-truecaller-firebase-otp-audit.md` decision only covers OTP delivery by the *auth provider*.
3. **Delivery state separation** — carrier vs application delivery are separate in principle, but nothing models "handed to SMS app, unconfirmed".
4. **Failure reporting** — `NexaDataSmsSender` returns a single generic `Result.failure`; no `SmsManager` result-code classification.
5. **Dedup** — `message_dedup_index` is keyed by wire `packetId`; there is no NX1 `messageId` space.
6. **Existing binary SMS path** — complete but unreachable: cannot be used for Module 3 because it requires `SEND_SMS`/`RECEIVE_SMS` and bypasses the Telephony provider by design.

---

## 3. Missing functionality (the actual Module 3 gap)

1. NX1 text packet protocol (encode/decode/validate/size limits).
2. Independent SMS cryptographic session (provisioning, direction keys, nonce discipline, replay window, versioning, expiry, revocation).
3. GSM-7/UCS-2-aware segment accounting (the request explicitly forbids hardcoding 160).
4. Persistent, bounded multipart reassembly with crash recovery.
5. Zero-permission `ACTION_SENDTO` composer flow + manual NX1 import.
6. Dual-SIM/subscription policy (selection, SIM removal, no silent SIM switch).
7. Carrier-handoff state machine and application ACK over SMS.
8. SMS failure classification + retry/queue-for-Internet with idempotency.
9. NX-ID ↔ phone-number mapping that refuses unknown senders.
10. Group-chat hard block enforced below the UI.
11. SMS-specific persistence tables + UI states.
12. Default-SMS-handler mode and Smart SMS Inbox (conditional, see §6).

---

## 4. Existing reusable components & architecture conflicts

### 4.1 Reuse (no duplication)
`ChaChaAeadCipher`, `Ed25519Signer`, `CryptoKeystoreManager`, `SecureStorage`, `ContactEntity`/`ContactStatus`, `MessageDao`/`ChatDao`, `messages` table + `TransportType`, `MessageDedupEntity`, `NexaDatabase` migration pattern, `NexaTransportSelector`, `SmsFailureBottomSheet`, `MessageTransportBadge`, `NexaTelemetryEngine` events, existing `SafeMessageParser`.

### 4.2 Conflicts found
1. **Two SMS identities.** `TransportType.DATA_SMS` currently means "binary port-8901 Data SMS". Module 3 is *text* SMS. Resolution: keep `TransportType.DATA_SMS` as the persisted SMS transport tag (no schema churn, badges already render it) and record the concrete channel in a new `sms_packets.deliveryChannel`-style field. No new `TransportType` value is introduced, so existing `when` blocks and DB rows stay valid.
2. **"Encrypt once, route anywhere".** `TransportRouter` reuses the *same* Double Ratchet envelope for every medium. That directly violates the Module 3 requirement for an **independent** SMS session (a 49-byte-overhead ratchet envelope also cannot fit an SMS budget). Resolution: SMS uses its own cipher state and packet format; `TransportRouter` is left untouched for the legacy path.
3. **Delivery ticks.** Module 2's rule is "never show a tick because a write succeeded". Module 3 adds a *weaker* state than `SENT` for SMS ("handed to SMS app, unconfirmed"), so `MessageStatus` is **not** extended (extension would break exhaustive `when`s across 1,093 ViewModel lines and the UI). SMS confirmation lives in `sms_packets.handoffState` and the UI maps it.
4. **Dedup space.** NX1 messages must dedupe on a 96-bit SMS `messageId`, not the wire `packetId`. Resolution: deterministic internal `packetId = "nx1-<senderNxId>-<messageId>"` so the existing unique index is the dedup gate — no second dedup table.
5. **Phone privacy.** `ContactEntity.shouldShowPhoneNumber` is a *display* rule. Sending an SMS requires the number; the transport must gate on the mapping existing, while the UI must never print the number in the chat body.

### 4.3 Cryptographic separation decision
The SMS session **must not** touch `ratchet_states`. Rationale: ratchet state is a strictly ordered chain; using it offline over an unreliable, reorderable SMS medium would burn chain keys and desynchronise Internet messaging — a direct regression risk.

**Chosen design:** a dedicated X25519 *SMS provisioning* keypair per device (stored AES-GCM-wrapped by the AndroidKeyStore master key in SQLCipher), whose public half is exchanged **inside the existing authenticated E2EE Internet channel** as an encrypted control message (`SMS_KEY_PROVISION`), exactly like the existing `PIN_MESSAGE` / `PROFILE_UPDATE` control packets. The pairwise secret is `X25519(localPriv, peerPub)`; direction keys are HKDF-SHA256 over that secret with explicit `(sender, recipient)` info strings. No plaintext secret ever leaves either device, no backend involvement, and the Internet ratchet is only used to carry the provisioning packet (as it already carries control packets).

---

## 5. Google Play policy & Android platform limitations

### 5.1 Policy position (verified against the cited policy pages; recorded as reasoning, not as an approval claim)
- Google Play's **SMS and Call Log Permissions** policy (`support.google.com/googleplay/android-developer/answer/17225965`) restricts `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `WRITE_SMS`, `READ_CALL_LOG`, etc. to a small set of permitted core use cases, with the **default SMS handler** route requiring a declared, central, user-facing SMS function plus a Play Console permissions declaration. Selection as default SMS handler on-device does **not** itself grant Play approval; the declaration is reviewed separately and can be rejected.
- **Historical precedent is adverse:** Google removed SMS/MMS access from many apps in 2019; "encrypted messaging as an SMS fallback" is not one of the enumerated permitted exceptions.
- NEXA's documented core functionality is *zero-knowledge Internet E2EE messaging with mesh/DTN fallback*. SMS is a fallback channel, not the product's primary purpose. The "Smart SMS Inbox" is not part of NEXA's core value proposition.
- **Therefore Mode B is assessed as NOT ELIGIBLE with current documented functionality**, and the Smart SMS Inbox is **DEFERRED/BLOCKED**. Implementing the role request would require declaring restricted permissions that the app cannot justify, risking rejection or post-publication removal.
- The workspace already encodes this decision: `NexaDataSmsSender` fails closed, the Data SMS receiver was deleted from the manifest, and the manifest comment states *"Zero-Permission Client-Initiated Reverse SMS uses Android Intent.ACTION_SENDTO (No telephony permissions required)"*.

### 5.2 Android platform constraints this module must design around
| Constraint | Consequence |
| :--- | :--- |
| Sending from NEXA without `SEND_SMS` is impossible | Mode A must hand off to the user's default SMS app via `ACTION_SENDTO` |
| Receiving SMS without `RECEIVE_SMS` is impossible | Incoming NX1 must be **manually imported** (paste/share) — never claim automatic interception |
| `SmsManager.divideMessage()` needs no permission | Real segment counting is available in Mode A |
| `SubscriptionManager.getActiveSubscriptionInfoList()` requires `READ_PHONE_STATE` (or carrier privileges) on modern Android | Bluetooth-quality dual-SIM enumeration is **not available** in Mode A; SIM choice is delegated to the system SMS app. Policy must degrade safely and honestly |
| `SmsManager.sendTextMessage` result callbacks only exist when NEXA sends | Carrier result codes are only classifiable in Mode B → classifier implemented and unit-tested, but unreachable in the shipped path |
| SMS content is visible to the carrier's SMSC and to the relay path | Metadata (sender, recipient, timing, segment count) is exposed. Encryption protects content only. Must be documented in UI copy |
| PDU/encoding limits | GSM-7: 160 chars single, **153** per part concatenated; UCS-2: 70 / **67**. NX1 tokens are pure ASCII base64url, therefore GSM-7 in practice, but the calculator must still handle Unicode text lengths |
| `minSdk 24`, `targetSdk 35`, `compileSdk 37` | No `SmsManager` subscription APIs before API 31 without reflection → capability-gated. No `java.util.Base64` guarantee at API 24 in tests → own strict base64url codec |

### 5.3 Telephony Provider requirement
A default SMS app **must** write inbound messages to the Telephony provider (`content://sms/inbox`) and keep them there; it must also handle `SMS_DELIVER_ACTION`, `WAP_PUSH_DELIVER_ACTION`, and the `ACTION_CHANGE_DEFAULT` role, and it must not delete ordinary SMS on uninstall. Because Mode B is not pursued, NEXA **never becomes the SMS store of record**, so ordinary SMS (bank alerts, OTPs, personal messages) remain in the user's existing messaging app and survive NEXA uninstallation. This is the safest possible platform position and is recorded as the final decision: **NX1 messages bypass nothing, they simply never enter the Telephony provider** (the user's own SMS app owns them, as it already does).

---

## 6. Eligibility conclusions (labels)

| Item | Verdict |
| :--- | :--- |
| Mode A (zero-permission `ACTION_SENDTO` composer + manual import) | **ELIGIBLE — IMPLEMENTED** |
| Mode B (default SMS handler) | **BLOCKED** — policy eligibility not established |
| Smart SMS Inbox | **DEFERRED** (depends on Mode B) |
| Restricted permissions (`READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `WRITE_SMS`) | **NOT REQUESTED** (correct outcome) |
| Dual-SIM *enumeration* by NEXA | **BLOCKED** in Mode A (needs `READ_PHONE_STATE`); selection policy implemented, delegated to system SMS app |
| Carrier send-result classification | **BLOCKED** in Mode A (no callback); classifier implemented for Mode B and unit-tested |
| Automatic ACK | **BLOCKED** in Mode A (no permission-free send); authenticated ACK is generated for the user to send manually |
| Guaranteed delivery / Play approval / zero metadata exposure | **NEVER CLAIMED** |

---

## 7. Security risks identified

1. **Ratchet desync / key burn** if the Internet ratchet is reused for SMS → avoided by an independent session (§4.3).
2. **Nonce reuse** under a fixed key → avoided with `nonce = keyVersion || counter`, where the counter is per-direction, persisted, and never reused.
3. **Replay / reorder** on an unreliable medium → per-direction sliding replay window (64-bit bitmap) + strictly monotonic high-water mark.
4. **Identity spoofing via `NX1:` prefix or sender MSISDN** → both rejected as authentication. Conversation resolution requires a locally-known contact, and decryption must succeed under the session key bound to that contact's NX-ID. A failed AEAD tag = dropped packet, no conversation created.
5. **Forged ACK** → ACK carries an HMAC-SHA256 tag over `messageId|sender|recipient|counter`, verified with a *separate* ACK direction key; ACKs share the replay window machinery.
6. **Multipart memory exhaustion** → hard caps: ≤ 4 parts/message, ≤ 1024 ciphertext bytes/part, ≤ 20 concurrent reassemblies, ≤ 4096 bytes total, 24 h TTL, per-part payload equality check for duplicates.
7. **Key material at rest** → only the Keystore-wrapped private key and *public* values are stored; the derived session root is **never persisted** (re-derived on use), so SQLCipher leakage alone does not yield SMS plaintext.
8. **Logging** → no plaintext, key, counter-material or NX1 body is logged; only packet ids, reasons and lengths.
9. **Group fan-out** → blocked in the repository (`SMS_GROUP_BLOCKED`), not only in UI.
10. **Unknown-sender conversation injection** → rejected before any DB write.
11. **Compression side channel** → compression deliberately **not** implemented (see NX1 spec); no CRIME/BREACH-style oracle on SMS.
12. **Backend leakage** → SMS path performs zero network calls. Only the (already-encrypted) provisioning control packet uses the Internet channel.

---

## 8. Required migrations & dependencies

### 8.1 Migration (Room v27 → v28)
New tables only, all additive and non-destructive:

| Table | Purpose |
| :--- | :--- |
| `sms_sessions` | per-contact SMS session: key version, peer provisioning public key, counters, replay windows, expiry, revocation |
| `sms_key_material` | Keystore-wrapped local SMS provisioning keypair |
| `sms_packets` | NX1 message id ↔ internal message UUID, direction, part count, segment count, handoff/delivery state, retry metadata |
| `sms_inbound_parts` | persistent bounded multipart reassembly buffer (crash recovery) |

No existing table is altered; no destructive migration; `messages`/`chats` untouched.

### 8.2 Dependencies
**None added.** BouncyCastle (ChaCha20-Poly1305), Tink (X25519), Room/SQLCipher, Compose and WorkManager are already declared. HKDF/HMAC use `javax.crypto.Mac`.

### 8.3 Manifest
**No permission changes. No new components.** The module uses `ACTION_SENDTO` (no permission) and clipboard import (no permission). No `READ_SMS`/`RECEIVE_SMS`/`SEND_SMS`/`WRITE_SMS`, no default-SMS intent filters.

---

## 9. Testability constraints of this environment
- JVM unit tests exist (`app/src/test/...`, 75 files) and run with `unitTests.isReturnDefaultValues = true`; Android framework stubs cannot be exercised, so all protocol/crypto/policy logic is written in **pure Kotlin** with injectable randomness, clocks and stores.
- No Android device/emulator or SIM is attached to this workspace, and no carrier account is available. Real-device scenarios (dual-SIM, airplane mode, carrier rejection, delayed multipart over a live network) are therefore **NOT TESTED** and must be reported as such, never as passing.
- `SmsManager.divideMessage()` is used opportunistically behind a capability check with an equivalent pure-Kotlin GSM-7/UCS-2 fallback so unit tests and unavailability both degrade deterministically.

---

## 10. Audit conclusion
NEXA already owns everything Module 3 needs *except* the SMS-specific layer: reviewed AEAD/KDF primitives, Keystore-backed key storage, authenticated peer identity, the `messages`/`chats` model, cross-transport dedup, SQLCipher migrations, transport selector UI and failure sheet. The correct implementation is therefore **a thin, independent SMS transport** — new protocol, new crypto session, new persistence, new capability gating — that reuses those components and changes nothing in authentication, Internet E2EE, or SIM binding.

Approved for implementation: Phases 2–6, 8 (policy-degraded), 9–11, 13.
Blocked/deferred by policy: Phase 7 (default handler), Phase 12 (Smart SMS Inbox).
