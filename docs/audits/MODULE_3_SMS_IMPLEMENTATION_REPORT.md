# NEXA — Module 3: Encrypted SMS Fallback & Smart SMS Engine
## Implementation & Production Readiness Report

**Date:** 2026-11-14
**Scope:** Android client only (`app/src/main/java/com/nexa/app`). No backend change. No authentication, identity, SIM-binding or Internet E2EE code rewritten.
**Companion document:** `docs/audits/MODULE_3_SMS_ARCHITECTURE_AUDIT.md`
**Status labels used:** PASS · FAIL · BLOCKED · NOT TESTED · NEEDS REVIEW

> **Read Part II first for the current verdict.** Part I is the original implementation record. Part II (§1–§9 at the end of this document, dated 2026-11-15) is the NEEDS FIXES remediation: seven key-path/transport defects found and fixed, the v27→v28 DDL moved to a verified single source, Module 3 tests grown from 46 to 74, full suite at 761/0, and the remaining device-dependent gaps recorded as NOT TESTED. Where the two disagree (test counts, DB/migration evidence, schema location), Part II is authoritative.

---

## 1. Architecture audit summary

The mandated `NEXA Brain.zip`, `1architecture.md`, `PRD.md`, `architecture.md`, `design.md`, `rules.md`, `phases.md`, `memory.md`, `tasks.md`, `specs.md`, `testing.md` and `decisions.md` **do not exist in this workspace** (verified by repository-wide glob; zero matches, and zero `*.zip` files). The audit therefore used the real source tree plus the existing documentation authority set (`docs/work/IMPLEMENTATION_STATUS.md`, `docs/audits/MODULE_2_INTERNET_MESSAGING_AUDIT.md`, `docs/work/AUTHENTICATION_ARCHITECTURE.md`, `docs/work/IDENTITY_ARCHITECTURE.md`, the multi-transport/DTN/ACK audits). This documentation gap is a **known limitation** (§21) and does not change the outcome: the architecture was recoverable from source with high confidence.

Key audit findings:

| Area | Finding |
| :--- | :--- |
| Identity / auth | Complete and reusable (Ed25519 identity, X25519 DH identity, Keystore-wrapped at rest). **Not modified.** |
| Internet E2EE | Complete Double Ratchet (X25519/HKDF-SHA256/ChaCha20-Poly1305). **Not modified, not reused for SMS state.** |
| Local DB | Room + SQLCipher, v27 with explicit sequential non-destructive migrations. |
| Existing SMS | Port-8901 *binary* Data SMS exists but is fail-closed (no `SEND_SMS`) and its receiver is deactivated. Not usable for a zero-permission fallback. |
| SIM awareness | **No SIM/subscription code existed at all.** |
| Play policy | Default-SMS route not justifiable from NEXA's documented core function → Mode B blocked. |

---

## 2. Existing functionality reused

- `CryptoKeystoreManager` (AndroidKeyStore AES-256-GCM master key wrapping) — protects the new SMS provisioning private key.
- `ChaChaAeadCipher` (BouncyCastle ChaCha20-Poly1305) — SMS AEAD, unchanged.
- Tink `X25519` — SMS provisioning keypair + pairwise secret.
- `ContactEntity` / `ContactStatus` / `shouldShowPhoneNumber` — identity, acceptance and phone-privacy rules.
- `messages` / `chats` tables, `MessageEntity`, `MessageDao`, `ChatDao`, `MessageStatus`, `TransportType.DATA_SMS` — SMS messages live in the **same NX-ID conversation** as Internet messages.
- `NexaDatabase` migration pattern (`addColumnIfNotExists`, `CREATE TABLE IF NOT EXISTS`).
- `MessageDedupEntity` indirectly (deterministic `packetId` uses the existing unique index as the dedup gate).
- `NexaWirePacket` end-to-end control channel (`sendControlPacket`, `PROFILE_UPDATE`/`PIN_MESSAGE` handling) — carries the SMS provisioning public key.
- `NexaTransportSelector`, `SmsFailureBottomSheet`, `MessageTransportBadge` — existing transport UI language.
- `SecureStorage`, `UserDao`, `NetworkMonitor` — identity and connectivity.
- `SmsSegmentCalculator` platform path uses `SmsManager.divideMessage()` (no permission required).

---

## 3. Files created

**Core protocol / crypto / policy (pure Kotlin, JVM-testable)**
1. `core/sms/nx1/Nx1Protocol.kt` — NX1 constants, strict base64url, message/ACK codecs, body tokenizer.
2. `core/sms/nx1/Nx1Crypto.kt` — HKDF-SHA256 key schedule, direction keys, nonce derivation, AAD, AEAD encrypt/decrypt, ACK tag create/verify.
3. `core/sms/nx1/SmsReplayWindow.kt` — 64-slot sliding replay window with serialisation.
4. `core/sms/nx1/SmsMultipartAssembler.kt` — bounded multipart reassembly + `SmsInboundPartStore` + in-memory store.
5. `core/sms/nx1/SmsSegmentCalculator.kt` — GSM-7/UCS-2 segment accounting (no hardcoded 160).
6. `core/sms/SmsEligibility.kt` — states, block reasons, single-source eligibility evaluator.
7. `core/sms/SmsFailureClassifier.kt` — `SmsManager` result-code mapping + Mode-A honesty message.
8. `core/sms/SmsSubscriptionPolicy.kt` — dual-SIM selection policy (delegated/registered/require-choice/no-SIM).
9. `core/sms/SmsFallbackModels.kt` — plan/outcome/session models, handoff states, phone masking, provisioning protocol constants.

**Persistence**
10. `data/local/entity/SmsEntities.kt` — `sms_sessions`, `sms_key_material`, `sms_packets`, `sms_inbound_parts`.
11. `data/local/dao/SmsDaos.kt` — `SmsSessionDao`, `SmsKeyMaterialDao`, `SmsPacketDao`, `SmsInboundPartDao`.

**Data / transport**
12. `data/repository/sms/SmsKeyVault.kt` — Keystore-wrapped local provisioning keypair.
13. `data/repository/sms/SmsSessionManager.kt` — session provision/rotate/expire/revoke, counters, replay windows.
14. `data/repository/sms/SmsFallbackRepositoryImpl.kt` — the transport engine (encrypt → NX1 → persist → handoff; import → verify → persist → ACK).
15. `data/repository/sms/RoomSmsInboundPartStore.kt` — SQLCipher-backed reassembly buffer.
16. `data/remote/sms/SmsComposerLauncher.kt` — `ACTION_SENDTO` handoff, capability probe, platform segment count, SIM policy adapter.
17. `domain/repository/SmsFallbackRepository.kt` — contract.

**Presentation**
18. `presentation/chat/components/SmsFallbackSheet.kt` — consent/handoff/import sheet + `SmsFallbackUiState`.
19. `presentation/chat/components/SmsTransportStatusChip.kt` — status pill for all required states.

**Tests**
20. `app/src/test/java/com/nexa/app/Module3EncryptedSmsFallbackTests.kt` — 46 tests.

**Documentation**
21. `docs/audits/MODULE_3_SMS_ARCHITECTURE_AUDIT.md`
22. `docs/audits/MODULE_3_SMS_IMPLEMENTATION_REPORT.md` (this file)

---

## 4. Files modified

| File | Change |
| :--- | :--- |
| `data/local/NexaDatabase.kt` | v27 → **v28**; four new entities/DAOs; additive `MIGRATION_27_28` (new tables + indexes only). |
| `domain/repository/ChatRepository.kt` | `sendSmsKeyProvisioning()` default (public key only). |
| `data/repository/ChatRepositoryImpl.kt` | `smsProvisioningPublicKeyProvider` + `sendSmsKeyProvisioning()` using the existing encrypted control packet. |
| `core/transport/NexaUniversalTransportIngest.kt` | Handles `SMS_KEY_PROVISION` control packet from the authenticated peer; stores only the peer's public key via callback. |
| `NexaApplication.kt` | Wires key vault, session manager, part store, SMS repository, ingest handler, provisioning provider. |
| `presentation/chat/ChatViewModel.kt` | SMS fallback state, 9 events, prepare/handoff/mark-sent/import/ack flows, composer request flow, automatic provisioning attempt when Internet is available. |
| `presentation/chat/ChatScreen.kt` | Renders `SmsFallbackSheet`, collects composer requests and launches the external SMS app, clipboard copy for packet/ACK. |
| `presentation/chat/components/NexaTransportSelector.kt` | SMS option is now "Encrypted SMS" (was permanently-disabled "Binary Data SMS"). |
| `presentation/navigation/AuthNavGraph.kt` | Injects `smsFallbackRepository` into `ChatViewModel`. |

**Repairs required to restore a compiling baseline (pre-existing breakage, disclosed):** `ChatRepositoryImpl` was missing imports for `PresencePayload`, `PresenceStatus`, `MessageEditPayload`, `MessageDeletePayload`, `OnlinePrivacyMode`, and referenced the non-existent `EncryptedEnvelope.nonce`; six test files had `FakeMessageDao` classes missing three new `MessageDao` members; and Room DAO methods without explicit return types trigger a known KSP2 bug (`unexpected jvm signature V`) — all new DAO methods now declare `Int`/`Long`/entity/list return types. These are mechanical fixes, not behaviour changes.

---

## 5. Dependencies added or removed

**None in either direction.** ChaCha20-Poly1305 (BouncyCastle), X25519 (Tink), Room/SQLCipher, Compose, `javax.crypto.Mac` for HKDF/HMAC were already present. No new third-party library, no service, no backend call.

---

## 6. Android manifest and permission changes

**No changes.** The manifest is byte-for-byte unchanged.

- No `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `WRITE_SMS`, no `READ_PHONE_STATE`.
- No default-SMS intent filters (`SMS_DELIVER_ACTION`, `WAP_PUSH_DELIVER_ACTION`, `ACTION_CHANGE_DEFAULT`), no role request.
- Sending: `ACTION_SENDTO` (`smsto:`) with prefilled `sms_body`/`EXTRA_TEXT` — zero permissions.
- Receiving: manual paste-import of the NX1 body — zero permissions.

**Status: PASS** (constraint satisfied: no restricted permission is declared or requested anywhere).

---

## 7. Default SMS handler eligibility assessment

| Question | Answer |
| :--- | :--- |
| Is SMS handling central to NEXA's documented core functionality? | **No.** NEXA is a zero-knowledge E2EE messenger with Internet + mesh/DTN transports; SMS is a fallback. |
| Which capabilities would be required? | `RECEIVE_SMS`, `SEND_SMS`, `READ_SMS`, `WRITE_SMS`, Telephony-provider writes, `SMS_DELIVER_ACTION`/`WAP_PUSH_DELIVER_ACTION` receivers, default-role handling, Play Console SMS-permission declaration and justification. |
| Play Console declaration verified? | The declaration requirement exists for restricted SMS permissions; **approval cannot be assumed** and is reviewed independently of the on-device default role. Historical 2019 SMS-access tightening makes an "encrypted fallback" justification high-risk. |
| Behaviour when the role is lost? | Would require immediate halt of restricted-permission use — not implemented because the role is never requested. |
| Decision | **SAFE TO PERMANENTLY BLOCK.** No role request, no restricted permissions, no manifest filters. |

**Status: BLOCKED** (by policy eligibility, deliberately not implemented).

### 7b. SMS Provider decision (documented, per requirement)
Because NEXA never becomes the default SMS app, NX1 messages **never enter or bypass** the Telephony provider: the user's existing messaging app owns and stores those messages exactly as it does today. Ordinary SMS (bank alerts, OTPs, personal messages) remain untouched, and uninstalling NEXA cannot destroy ordinary SMS history. The unavoidable platform limitation is that NEXA also cannot *read* those messages — hence manual import (§13). **Status: PASS** for platform safety, with the limitation explicitly accepted.

---

## 8. Google Play policy risk assessment

| Risk | Assessment |
| :--- | :--- |
| Restricted SMS permission declaration | **Eliminated** — none declared. |
| Default-handler justification gap | **Avoided** — Mode B not implemented. |
| Misleading claims | Sheet copy states the carrier can see number/timing/length, that NEXA cannot confirm carrier delivery, and that delivery is only confirmed by an authenticated ACK. No "Play Store safe", no "guaranteed delivery". |
| Data collection | Zero. SMS plaintext/key material never leaves the device; provisioning transmits a **public** key inside the existing E2EE channel. |
| Smart SMS Inbox / SMS profiling | **Not implemented** (would imply restricted permissions). |

**Status: PASS** for the implemented surface; **NEEDS REVIEW** before any future Mode B attempt.

---

## 9. NX1 protocol specification (implemented)

```
message : NX1:1:<MESSAGE_ID>:<PART_INDEX>/<TOTAL_PARTS>:<BASE64URL_PAYLOAD>
ack     : NX1:2:<MESSAGE_ID>:ACK:<BASE64URL(8-byte COUNTER || 16-byte AUTH_TAG)>
```

- `MESSAGE_ID`: 12 random bytes → 16 unpadded base64url chars (96-bit).
- `BASE64URL`: strict RFC 4648 §5 alphabet, no padding; illegal characters, impossible lengths and padding are rejected.
- Hard limits enforced in both encoder and decoder: ≤ 4 parts, ≤ 1024 ciphertext bytes/part, token ≤ 512 chars, body scan ≤ 4096 chars, ≤ 8 tokens per body.
- Multi-part tokens are newline-joined in the composer body; concatenated multipart SMS arrive space-separated and `extractTokens` recovers each token.
- Full validation: version, field count, message-id shape, part index/total, encoding, payload non-empty/oversized, ACK payload length and counter range.

**Measured overhead (exact, not estimated):**

| Component | Bytes |
| :--- | :--- |
| format version | 1 |
| key version | 2 |
| send counter | 8 |
| AEAD nonce (explicit) | 12 |
| Poly1305 tag | 16 |
| **crypto overhead** | **39** |
| token framing `NX1:1:` + id + `:` + `i/n` + `:` | 28 chars |
| base64url inflation | ×4/3 |

Plaintext budget per message ≈ **280 ASCII characters at 3 network SMS**; the implementation computes the real segment count of the assembled body and blocks (rather than silently sending) above the configurable safety limit (default 3, hard part cap 4). **No promise that a message fits in 160 characters.**

**Compression — evaluated, deliberately not implemented:** for short chat text, deflate header/trailer plus the need to compress *before* AEAD adds ~10–20 bytes and CPU per message while saving nothing on messages under ~100 bytes; it also introduces a compression side-channel (CRIME/BREACH-class oracle) on a medium where an attacker can inject chosen plaintext via SMS. Not worth the risk. **Status: PASS (decision documented).**

**Encoding — Base64URL chosen** over Base91/Base85 because it is strictly ASCII from the GSM-7 basic alphabet (so NX1 bodies stay in the 160/153 septet budget), has a trivially strict decoder, and tolerates line-wrapping by SMS gateways. **Status: PASS.**

---

## 10. Cryptographic session design (implemented)

**Separation.** SMS never touches `ratchet_states`. The Internet Double Ratchet is strictly ordered; using it on a reorderable, lossy medium would burn chain keys and desynchronise Internet messaging.

**Provisioning.** A dedicated X25519 *SMS provisioning* keypair per device. Its private half is AES-256-GCM wrapped by the AndroidKeyStore master key before entering SQLCipher. The public half is sent inside the **existing authenticated E2EE control channel** as `{"type":"SMS_KEY_PROVISION","v":1,"pub":…,"peer":…}` — the same channel that already carries `PIN_MESSAGE`/`PROFILE_UPDATE`. The accepting side uses the *authenticated* peer NX-ID from the envelope, never a client-supplied id.

**Derivation (HKDF-SHA256, RFC 5869):**

```
root            = HKDF(salt="NEXA_SMS_V1_SALT", ikm=X25519(localPriv, peerPub),
                       info="NEXA_SMS_V1|ROOT|<keyVersion>|<sortedNxIds>", 32)
message key d   = HKDF(salt="NEXA_SMS_V1_DIR", ikm=root,
                       info="NEXA_SMS_V1|MSG|<sender>|<recipient>", 32)
ack key d       = HKDF(salt="NEXA_SMS_V1_ACK", ikm=root,
                       info="NEXA_SMS_V1|ACK|<sender>|<recipient>", 32)
nonce           = uint32_be(keyVersion) || uint64_be(counter)   (12 bytes)
AAD             = "NX1|1|<messageId>|<sender>|<recipient>|<keyVersion>|<counter>"
```

- **Direction independence:** `MSG|A|B` ≠ `MSG|B|A`; A's sending key equals B's receiving key and nothing else.
- **Nonce uniqueness:** a persisted per-direction counter, never reused; the nonce is additionally transmitted and compared constant-time, so a stale or substituted nonce fails before AEAD.
- **Authenticated sender identity:** sender and recipient NX-IDs are in the AAD, so a packet cannot be re-attributed to another contact even by someone who knows the key.
- **Replay:** 64-slot sliding window per direction (messages and ACKs have separate counter spaces), persisted in SQLCipher.
- **Versioning/rotation:** re-provisioning with a changed peer key bumps `keyVersion`; old ciphertext then fails `KEY_VERSION_MISMATCH`.
- **Expiry/revocation:** sessions carry a 30-day validity and a revocation flag; expired or revoked sessions refuse to encrypt/decrypt, and sending is blocked with the required message.
- **At rest:** the derived root and direction keys are **never persisted**; they are re-derived on use from the Keystore-wrapped private key. SQLCipher leakage alone therefore does not yield SMS plaintext.
- **No custom algorithms:** HKDF/HMAC via `javax.crypto.Mac`, AEAD via the project's existing ChaCha20-Poly1305 wrapper, DH via Tink X25519.

**Status: PASS (unit-verified). NOT TESTED on two physical devices.**

---

## 11. Multipart SMS design (implemented)

- Segmentation is driven by measured encoding: `SmsSegmentCalculator` (GSM-7 160/153 with two-septet extension characters; UCS-2 70/67 for anything outside the default alphabet). On-device, `SmsManager.divideMessage()` is authoritative and requires no permission.
- Ciphertext is produced **once** for the whole message and then sliced into parts, so one authentication tag covers the entire message; reassembly concatenates and the complete packet is authenticated **before** any plaintext exists.
- Reassembly is persistent (`sms_inbound_parts`), bounded (≤ 4 parts, ≤ 1024 B/part, ≤ 4096 B/message, ≤ 20 concurrent messages), duplicate-idempotent, conflict-rejecting (same index + different bytes discards the message), TTL-expired (24 h) and crash-surviving.
- Out-of-order, duplicate, delayed, missing, corrupted and incomplete cases all have dedicated tests.
- Exactly one chat message is produced per authenticated reassembly; a duplicate import returns "Already imported".

**Status: PASS (unit-verified). Real-network delay/reorder behaviour NOT TESTED.**

---

## 12. Dual-SIM implementation

- `SmsSubscriptionPolicy` implements the required identity model: subscription id (stable) is used, never the slot number; explicit preference → last used → system default; a disappeared subscription yields `REQUIRES_USER_CHOICE` with `mustAskUserBeforeSending = true` (never a silent switch); zero subscriptions yields `NO_SIM`.
- `SmsComposerLauncher.simSelection()` reads `SubscriptionManager` **only** when `READ_PHONE_STATE` is available; NEXA does not declare it, so in the shipped Mode A path the policy correctly reports `DELEGATED_TO_SYSTEM_SMS_APP` and the user's own SMS app performs SIM selection.
- UI copy says SIM selection is handled by the SMS app — no false claim about which SIM was used.

**Status: PASS (policy + unit tests) / BLOCKED (in-app subscription enumeration and per-subscription `SmsManager`), because the required permission is deliberately absent.**

---

## 13. ACK and delivery-state behaviour

Three distinct states, never conflated:

| State | Where stored | UI |
| :--- | :--- | :--- |
| **A. Local queued** | `messages` (PENDING) + `sms_packets` (QUEUED) before any handoff | clock |
| **B. Handed off / sent (carrier unconfirmed)** | `sms_packets` HANDED_OFF → SENT_UNCONFIRMED (`messages` SENT) | "SMS sent — delivery confirmation unavailable." |
| **C. Application delivered** | only after a valid NX1 ACK: `sms_packets` DELIVERED_APP_ACK + `messages` DELIVERED | "SMS delivered ✓✓" |

- ACK = `NX1:2:<id>:ACK:<counter||tag>` where `tag = HMAC-SHA256(ackKey, "NX1|ACK|<id>|<ackSender>|<ackRecipient>|<counter>")[0..16]`, verified with a **separate** ACK direction key.
- Forged tags, wrong sender, wrong message id, replayed counter and duplicate ACK are all handled (duplicate ACK is idempotent success; replay is rejected).
- Mode A cannot send automatically, so the receiver's ACK is surfaced as a copyable token; the sender only shows delivered after importing it. Missing ACK = "delivery confirmation unavailable" — nothing is fabricated.

**Status: PASS (unit-verified). Automatic ACK: BLOCKED** (requires `SEND_SMS`).

---

## 14. Failure and recovery behaviour

- `SmsFailureClassifier` maps the real `SmsManager` codes (`RADIO_OFF`, `NO_SERVICE`, `NULL_PDU`, `FDN_CHECK_FAILURE`, `LIMIT_EXCEEDED`, `RATE_LIMITED`, `INVALID_ARGUMENT`, `INVALID_SMSC_ADDRESS`, `MODEM_ERROR`, `NETWORK_ERROR`, `INTERNAL_ERROR`, `INVALID_UDH`, generic) to neutral user messages. It never asserts recharge exhaustion or quota exhaustion — generic failures say "Check your network, SIM, SMS plan, or carrier restrictions."
- Mode A has no send callback by construction, so the only honest failure state is `MODE_A_UNCONFIRMED`.
- Recovery: retry via SMS (re-encrypts with a fresh counter, same message UUID preserved), queue for Internet, retry via mesh, or cancel. Existing `SmsFailureBottomSheet` remains the remediation UI.
- Duplicate-transport safety: each attempt is keyed by the original `packetId`; NX1 `sendCounter`/`messageId` are allocated once per message, so a resend over another transport cannot fork a second conversation entry.

**Status: PASS (classification + unit tests) / BLOCKED (carrier callback path unreachable in Mode A).**

---

## 15. SQLCipher changes

Migration **v27 → v28**, additive only:

| Table | Key columns |
| :--- | :--- |
| `sms_sessions` | `peerNxId` PK, peer/local provisioning public key hex, `keyVersion`, `establishedAt`, `expiresAt`, `isRevoked`, message/ACK send counters, receive highest + bitmap per direction, `lastUsedAt` |
| `sms_key_material` | `keyId` PK, `publicKeyHex`, Keystore-wrapped `encryptedPrivateKeyHex`, `ivHex` |
| `sms_packets` | `messageId` PK, `packetId`, `peerNxId`, `direction`, `keyVersion`, `sendCounter`, `totalParts`, `segmentCount`, `handoffState`, `failureKind`, `deliveryChannel` |
| `sms_inbound_parts` | auto id, `messageId`, `senderNxId`, `partIndex`, `totalParts`, `payloadBase64`, `receivedAt`, unique `(messageId, senderNxId, partIndex)` |

No existing table or column altered; no destructive fallback. SMS bubbles land in the normal NX-ID conversation (`messages.transportType = DATA_SMS`), so existing ordering, search, pin, star and reply features apply unchanged.

**Status: PASS (migration compiles and is additive) / NOT TESTED on a device that already has a populated v27 database.**

---

## 16. UI changes

- **Transport selector:** the previously permanently-disabled "SMS" entry is now **"Encrypted SMS"** — "When Internet and mesh are unavailable. Text only, sent by your SMS app."
- **New sheet** (`SmsFallbackSheet`): eligibility message, explicit privacy disclosure (carrier sees number/timing/length; only content is encrypted), "Encrypt current message", packet summary (masked destination, NX1 parts, SMS segments), "Open SMS app to send", "Copy packet", "I sent it", manual **import** (sender number + NX1 body), and a copyable ACK token.
- **Status chip** (`SmsTransportStatusChip`) covers all requested states: Internet online, mesh available, offline SMS ready, key not ready, phone unavailable, no SIM, group SMS off, sending, sent·unconfirmed, delivered ✓✓, failed.
- Existing `MessageTransportBadge` shows the `✉️ SMS` marker on bubbles; raw NX1 payloads are never rendered in chat.
- Phone numbers are masked (`maskPhoneNumber`, last two digits) in the plan summary.
- `ChatViewModel` gains 9 events and a one-shot composer-request flow; `ChatScreen` collects it and launches `ACTION_SENDTO`, showing a snackbar if no SMS app exists.

**Status: PASS (compiles; not visually reviewed).**

---

## 17. Smart SMS Inbox status

**DEFERRED.** It depends on being a compliant default SMS application (normal inbox, bank/OTP/operator categorisation, Telephony-provider ownership), which §7 concluded is not justifiable. No restricted permission, no inbox UI, no SMS content analysis was added. Ordinary SMS stays entirely in the user's existing messaging app.

---

## 18. Unit and integration test results

`./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **733 tests, 0 failures**.

- New Module 3 suite: **46 tests, all PASS** (`Module3EncryptedSmsFallbackTests`).

| Coverage area | Tests |
| :--- | :--- |
| NX1 encode/parse round trip, unsupported version, malformed base64, padding/illegal alphabet, oversized token, bad part index, too many parts, oversized payload, token extraction from a wrapped SMS body, ACK round trip + malformed length, 96-bit id uniqueness | 11 |
| Direction-key independence/mirroring, cross-device encrypt/decrypt, plaintext absence in payload, modified ciphertext, modified tag, tampered nonce, counter-in-AAD, wrong sender identity, wrong recipient identity, key-version mismatch, rotation, nonce uniqueness | 11 |
| Replay window: duplicate, out-of-order, window boundary, serialisation | 2 |
| ACK: valid/forged, cross-message/cross-sender reuse | 2 |
| Multipart: out-of-order completion, duplicate idempotency, conflicting duplicate discard, missing part, too many/oversized parts, TTL expiry + concurrency bound, inconsistent totals | 6 |
| Segmentation: GSM-7/UCS-2 limits, NX1 is GSM-7 | 2 |
| Eligibility: group block, accepted-contact requirement, phone/privacy, session missing/expired/revoked/payload/SIM, ready + Internet state, phone masking | 5 |
| Failure classification honesty, Mode-A unconfirmed | 2 |
| Dual-SIM: delegated, no SIM, preference order, removed-SIM requires choice | 4 |

**Repository-level integration** (prepare → handoff → import → ACK against a real SQLCipher DB) is **NOT TESTED**: it requires an instrumented/Robolectric harness that this project does not have, and the JVM test configuration stubs the Android framework.

---

## 19. Real-device test results

**NOT TESTED.** No Android device, emulator, SIM, or carrier account is attached to this workspace, and no second device exists for a two-party exchange. Consequently all 20 mandatory device scenarios remain unexecuted:

1 Internet available — NOT TESTED · 2 Internet down, SMS available — NOT TESTED · 3 No Internet, no cellular — NOT TESTED · 4 Airplane mode — NOT TESTED · 5 SIM 1 active — NOT TESTED · 6 SIM 2 active — NOT TESTED · 7 Dual-SIM — NOT TESTED · 8 Registered SIM removed — NOT TESTED · 9 Recipient offline — NOT TESTED · 10 Delayed SMS — NOT TESTED · 11 Out-of-order multipart on a live network — NOT TESTED · 12 Partial multipart — NOT TESTED · 13 Sender killed mid-send — NOT TESTED · 14 Receiver killed before receipt — NOT TESTED · 15 Receiver restart — NOT TESTED · 16 Carrier rejection — NOT TESTED (needs a live SIM/plan) · 17 Delivery receipt unavailable — NOT TESTED · 18 Application ACK unavailable — NOT TESTED · 19 NEXA not default SMS app — **structurally guaranteed** (no restricted permissions), still NOT TESTED on device · 20 NEXA default SMS app — BLOCKED (not implemented).

**Blockers:** no device/emulator, no SIM/eSIM with an active SMS plan, no second NEXA device, and the Mode-B scenarios are blocked by policy (§7).

---

## 20. Security review

| Control | Result |
| :--- | :--- |
| Independent SMS key state | PASS — never touches `ratchet_states` |
| No plaintext fallback | PASS — every path either encrypts or blocks; unsupported content is refused |
| AEAD + AAD binding | PASS — message id, both NX-IDs, key version and counter authenticated |
| Unique nonces | PASS — persisted per-direction counter, nonce transmitted and constant-time compared |
| Replay / reorder | PASS — 64-slot window per direction, persisted |
| Forged ACK | PASS — separate ACK key, tag over id+sender+recipient+counter |
| Identity spoofing via `NX1:` prefix or MSISDN | PASS — rejected; requires a locally-known accepted contact **and** successful AEAD under that contact's session |
| Unknown number → new conversation | PASS — rejected before any DB write |
| Group SMS | PASS — blocked in the repository evaluator, not just the UI |
| Multipart DoS | PASS — parts/bytes/concurrency/TTL caps, conflicting duplicates discard |
| Key material at rest | PASS — only Keystore-wrapped private key + public values; root never stored |
| Logging | PASS — logs contain packet ids, counts, reasons and key-version/fingerprint prefixes only; no plaintext, keys, bodies or counters |
| Backend / cloud leakage | PASS — zero network calls in the SMS path; provisioning transmits a public key only |
| Internet E2EE regression | PASS — 733/733 unit tests pass; ratchet code untouched |

Residual risks: the SMS carrier and every network element on the SMS path observe sender/recipient/timing/segment count; a compromised device with an unlocked Keystore can derive SMS keys; SMS is inherently delay- and loss-prone, so out-of-order delivery beyond the 64-packet window is treated as a replay and dropped (fail-closed).

---

## 21. Known platform limitations

1. **No automatic SMS receive** without `RECEIVE_SMS` → import is manual (paste). Stated plainly in the UI.
2. **No send confirmation** in Mode A → carrier delivery receipts are unavailable to NEXA by construction.
3. **No automatic ACK** → the receiver copies an authenticated token back.
4. **No subscription enumeration** without `READ_PHONE_STATE` → SIM choice delegates to the system SMS app; `SmsManager`-per-subscription is unreachable.
5. **No carrier result codes** in Mode A → only unit-tested, not user-visible.
6. **Cellular signal ≠ Internet** — the transport never equates them; Internet state comes from `NetworkMonitor`, SMS capability from `TelephonyManager.isSmsCapable`.
7. **Capacity** ≈ 280 ASCII characters at 3 segments; the limit is enforced with a visible block, never silently truncated.
8. **Metadata exposure** — carriers can always see routing metadata; content is the only protected part.
9. **Documentation gap** — the mandated NEXA Brain archive/`1architecture.md` set is absent from the workspace.
10. **No instrumented test harness** in this project → repository/DB integration is untested at that level.

---

## 22. Remaining tasks

1. Real-device execution of the 19 runnable scenarios (§19) on at least one dual-SIM phone with an active plan, plus a second NEXA device.
2. Instrumented/Robolectric integration tests for `SmsFallbackRepositoryImpl` (prepare → persist → import → ACK) and for `MIGRATION_27_28` against a populated v27 database.
3. Visual review of the sheet, chip and bubble badge against the NEXA design system.
4. Provisioning UX polish: retry/backoff and an explicit "provision now" affordance when both peers are online; currently provisioning is attempted on sheet open while connected.
5. Optional: opportunistic `SmsManager.divideMessage`-based pre-send preview in the composer (already implemented as a capability; wire the number into the composer hint).
6. Restore the missing NEXA Brain documentation into the repository so future modules have their stated authority set.
7. Decide whether to attempt Mode B / Smart SMS Inbox at all; until then keep both BLOCKED/DEFERRED.

---

## 23. Production readiness assessment

| Criterion | Status |
| :--- | :--- |
| Secure 1-to-1 offline text messaging | **PASS (implementation + unit)** / device proof NOT TESTED |
| Independent SMS cryptographic state | **PASS** |
| No plaintext fallback | **PASS** |
| Correct NX-ID conversation mapping | **PASS (logic)** / DB integration NOT TESTED |
| Correct dual-SIM behaviour | **NEEDS REVIEW** — policy PASS, in-app enumeration BLOCKED |
| Reliable multipart reassembly | **PASS (unit)**, live-network behaviour NOT TESTED |
| Replay and tampering protection | **PASS** |
| Accurate sent/delivered states | **PASS** — carrier vs application states strictly separated |
| Safe failure and retry handling | **PASS (logic)**, carrier callbacks BLOCKED |
| Local SQLCipher persistence | **PASS (migration compiles)** / upgrade-from-v27 NOT TESTED |
| No accidental group SMS | **PASS** |
| No unnecessary backend dependency | **PASS** — zero network calls |
| No unauthorized SMS permission access | **PASS** — manifest unchanged |
| No regression in Internet E2EE | **PASS** — 733/733 tests, ratchet untouched |
| Accurate Play policy documentation | **PASS** — Mode B BLOCKED, no approval claimed |

**Overall: NEEDS REVIEW.** The module is implemented to its policy-safe ceiling (Mode A), compiles, and its security-critical logic is covered by 46 passing unit tests with no regressions in the 733-test suite. It is **not** production-ready until the real-device matrix is executed and the repository/DB integration tests exist. No Google Play approval, delivery guarantee, or zero-metadata claim is made or implied anywhere in the code or copy.
---

# Part II — NEEDS FIXES remediation

**Date:** 2026-11-15
**Trigger:** Module 3 verdict `NEEDS FIXES — Solid, tested SMS layer, but key paths and the DB are unproven on any device.`
**Method:** investigation first, then fix only demonstrated defects. Existing architecture and security decisions were treated as authoritative: no new permissions, no automatic sending, no Double Ratchet reuse, no server-side plaintext, no group SMS, Mode B and Smart SMS Inbox remain blocked/deferred.

Part I remains accurate as the implementation record. Where it is contradicted below (test counts, DB/migration verification, schema location, known gaps), Part II wins.

---

## 1. Original NEEDS FIXES findings

| ID | Finding from the review |
| :--- | :--- |
| NF-1 | No physical device or emulator verification of any key path (generate → persist → restart → recover). |
| NF-2 | Repository/DB integration unproven: no test opened the real database and exercised the SMS DAOs. |
| NF-3 | Migration v27→v28 never executed against the actual previous schema. |
| NF-4 | Replay-window and counter persistence claimed but only tested in memory within one process. |
| NF-5 | Key rotation / expiry / revocation / key-loss behaviour asserted by design, not by test. |
| NF-6 | Hardware-backed Keystore protection implied by the copy, never verified against a device capability. |
| NF-7 | UI never visually reviewed. |

Additionally the review flagged the DB as "unproven", which turned out to hide two genuine defects (§2: D-8, D-9).

---

## 2. Root cause of every finding (and the defects found while investigating)

The investigation produced **seven real code defects**, not just missing tests. Each is listed with its root cause and the fix that was applied.

### D-1 (critical) — nonce reuse after re-provisioning
**Root cause.** `SmsSessionManager.registerPeerProvisioningKey()` kept the existing `keyVersion` whenever the *peer's* key was unchanged, even though the re-created session resets `sendCounter`/`ackSendCounter` to `0`. Two paths reached that branch with counters reset: re-provisioning after session **expiry**, and re-provisioning after the **local** provisioning key was regenerated. Same key + same counter = same nonce = catastrophic ChaCha20-Poly1305 failure.
**Fix.** Every path that recreates a session now bumps the key version; only the unchanged-and-still-usable fast path keeps counters and version. The version is bound into the nonce and the AAD, so stale ciphertext is rejected with `KEY_VERSION_MISMATCH`.
**Evidence.** `expiryThenReprovisionBumpsVersionInsteadOfReusingNonces`, `regeneratedLocalKeyBumpsVersion`, `reprovisionWithNewPeerKeyBumpsVersionAndInvalidatesOldCiphertext`.

### D-2 (high) — key-version exhaustion reused a version
**Root cause.** `coerceAtMost(65535)` silently kept the old version once the space was exhausted — the same nonce-reuse failure by another route.
**Fix.** Fail closed: throw `IllegalStateException` before any write; the stored session is untouched.
**Evidence.** `exhaustedKeyVersionSpaceFailsClosedInsteadOfReusingAVersion`.

### D-3 (high) — counter overflow
**Root cause.** `counter + 1` was unguarded. At `Long.MAX_VALUE` the persisted counter wraps negative and `deriveNonce` either throws or, worse, a negative/duplicate value could reach the cipher.
**Fix.** `MAX_COUNTER = Long.MAX_VALUE - 2`; allocation returns `null` (send blocked) instead of wrapping.
**Evidence.** `exhaustedSendCounterFailsClosed`, `exhaustedAckCounterFailsClosed`.

### D-4 (medium) — counter allocation ignored session state
**Root cause.** `allocateSendCounter`/`allocateAckCounter` checked only `isRevoked`; expiry and (new) key mismatch were enforced only in `loadDirectionKeys`. Any caller that allocated first would consume nonces for an unusable session.
**Fix.** Allocation now applies the same fail-closed preconditions as key loading (revoked, expired, negative counter, local-key mismatch).
**Evidence.** `expiredSessionRefusesKeysAndCounters`, `lostLocalKeyMakesSessionUnusableRatherThanDerivingWrongKeys` (this test actually caught the gap).

### D-5 (medium) — TOCTOU race when generating the provisioning keypair
**Root cause.** `KeystoreSmsKeyVault.ensureKeyMaterial()` did an unlocked `get → generate → write`. Two concurrent first uses (provisioning arriving while the user opens the sheet) could each generate a keypair; one would be overwritten while a peer session had already been established against its public half, permanently breaking that session.
**Fix.** Double-checked `Mutex` around creation.
**Evidence.** `concurrentAllocationsNeverCollide` (covers the shared mutex path), plus code review.

### D-6 (high) — key loss went undetected
**Root cause.** If the database was restored without the KeyStore master key (or the key entry was lost), `loadDirectionKeys` derived keys from a *different* private key against the old session's public key, producing garbage keys rather than an error. Status still reported the session as ready.
**Fix.** Every session records the local provisioning public key it was derived from. A mismatch is surfaced as `SmsSessionStatus.localKeyMismatch`, `ready = false`, and all key/counter access is refused.
**Evidence.** `lostLocalKeyMakesSessionUnusableRatherThanDerivingWrongKeys`.

### D-7 (high) — group-protection hole in the business layer
**Root cause.** `SmsFallbackRepositoryImpl.prepareOutbound()` re-derived the conversation id from the NX-IDs instead of using the caller's real chat id, so the group check inside it was evaluated against a synthetic direct-chat id. A group chat id would have been written to a direct thread, and the group check could not see the caller's context. The UI block was the only protection.
**Fix.** `prepareOutbound` accepts the real `conversationId` (passed from `ChatViewModel`), refuses group ids with `SMS_GROUP_DISABLED` before any DB write, and both the repository and eligibility share one pure rule, `SmsConversationGuard`.
**Evidence.** `groupConversationsAreRecognisedByTheSharedGuard`; compile-level proof the ViewModel passes `state.conversationId`.

### D-8 (verification defect) — migration DDL was unverifiable
**Root cause.** The v28 DDL was inline inside `MIGRATION_27_28`, so it could drift from the Room entities with nothing to catch it (Room only validates at runtime, on a device).
**Fix.** The DDL moved to `core/sms/SmsSchemaDdl.kt`; the migration executes exactly those statements, and a JVM test derives the expected columns from the entities and compares them.
**Evidence.** `Module3SmsSchemaTests` (7 tests).

### D-9 (coverage defect) — the "persistence" claims were untested
**Root cause.** Counters, replay windows, rotation and revocation were only exercised through pure codec tests in one process; nothing simulated a restart or a second manager instance.
**Fix.** `Module3SmsKeyPathTests` runs the real `SmsSessionManager` against a persistent in-memory DAO and a rotatable fake vault, so "restart" is a new manager over surviving state.
**Evidence.** 21 tests, §4.

### NF-1, NF-3, NF-6, NF-7 — still genuine environment blockers
No device, emulator, AVD or system image exists on this machine (`adb devices` empty; `emulator -list-avds` empty; `$SDK/system-images` absent). `adb` and WHPX acceleration *are* installed, so the blocker is the missing system image/AVD, not tooling. These findings remain **NOT TESTED**; nothing was simulated or claimed.

---

## 3. Actual code changes

**Modified**

| File | Change |
| :--- | :--- |
| `data/repository/sms/SmsSessionManager.kt` | Version bump on every session recreation (D-1); fail-closed version exhaustion (D-2); counter ceiling (D-3); full state preconditions on allocation (D-4); `localKeyMismatch` detection and refusal (D-6). |
| `data/repository/sms/SmsKeyVault.kt` | Double-checked `Mutex` around keypair creation (D-5). |
| `core/sms/SmsFallbackModels.kt` | `SmsSessionStatus.localKeyMismatch` (default `false`, source-compatible). |
| `core/sms/SmsSchemaDdl.kt` | **New** — single source of truth for the v28 DDL. |
| `core/sms/SmsConversationGuard.kt` | **New** — one pure group rule for repository + eligibility. |
| `data/local/NexaDatabase.kt` | `MIGRATION_27_28` now executes `SmsSchemaDdl.statements`; behaviour identical, SQL de-duplicated. |
| `data/repository/sms/SmsFallbackRepositoryImpl.kt` | `prepareOutbound` takes the caller's conversation id, blocks groups, uses the shared guard. |
| `domain/repository/SmsFallbackRepository.kt` | `requestedConversationId: String? = null` (defaulted, existing callers unaffected). |
| `presentation/chat/ChatViewModel.kt` | Passes `state.conversationId`. |

**Added tests**

| File | Purpose |
| :--- | :--- |
| `app/src/test/java/com/nexa/app/Module3SmsKeyPathTests.kt` | 21 key-path tests (restart, counters, replay, rotation, exhaustion, integrity, group guard). |
| `app/src/test/java/com/nexa/app/Module3SmsSchemaTests.kt` | 7 schema/migration tests. |
| `app/src/androidTest/java/com/nexa/app/SmsDatabaseInstrumentedTest.kt` | 6 real-SQLCipher integration tests (device required). |

**Not changed.** No manifest edit, no dependency added or removed, no restricted permission, no automatic send, no ratchet/session-sharing change, no backend call, no plaintext path, no group-SMS enablement, no Mode B / Smart SMS Inbox.

---

## 4. Tests added and exact results

Commands and outcomes (all run in this workspace):

| Command | Exit | Result |
| :--- | :--- | :--- |
| `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` | 0 | BUILD SUCCESSFUL |
| `./gradlew :app:testDebugUnitTest --tests "com.nexa.app.Module3SmsKeyPathTests" --tests "com.nexa.app.Module3SmsSchemaTests"` | 0 | 28 tests, 0 failures |
| `./gradlew :app:testDebugUnitTest` (full suite) | 0 | **761 tests, 0 failures** (was 733) |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | 0 | APKs produced (`app-debug.apk`, `app-debug-androidTest.apk`) |
| `./gradlew :app:connectedDebugAndroidTest` | 1 | `DeviceException: No connected devices!` — environment blocker |

Per class (from the JUnit XML):

| Class | Tests | Failures |
| :--- | :--- | :--- |
| `Module3EncryptedSmsFallbackTests` (unchanged) | 46 | 0 |
| `Module3SmsKeyPathTests` (new) | 21 | 0 |
| `Module3SmsSchemaTests` (new) | 7 | 0 |
| Module 3 total | **74** | **0** |
| Whole project | **761** | **0** |

Key-path coverage: recovered session decrypts pre-restart ciphertext and re-derives byte-identical keys; counters continue (0,1,2 → 3) with the persisted counter already at the next value; 50 consecutive nonces distinct; message/ACK counter spaces independent; 32 concurrent allocations → 32 distinct counters; replay window survives restart (duplicates rejected, gaps accepted); ACK window separate; peer-key rotation → v2 and old ciphertext `KEY_VERSION_MISMATCH`; expiry + reprovision → v2 with counters at 0; local-key rotation → v2; key loss → not ready + all access refused; revoked/expired/missing → fail closed; counter and version exhaustion → fail closed; tampered tag, wrong message id, wrong sender and wrong recipient all rejected.

---

## 5. Database migration verification evidence

**What was verified (JVM, `Module3SmsSchemaTests`):**
1. The migration creates all four tables (`sms_sessions`, `sms_key_material`, `sms_packets`, `sms_inbound_parts`).
2. Column sets are **exactly** equal to the Room entity fields, derived by reflection — a renamed/added/removed entity property now fails the build.
3. Column types match (TEXT/INTEGER mapping from the Kotlin field type).
4. Primary keys are declared for all four tables (inline `AUTOINCREMENT` for `sms_inbound_parts`).
5. `sms_inbound_parts` has the unique `(messageId, senderNxId, partIndex)` index — the basis of multipart dedup.
6. `sms_packets` declares all four query indexes (`packetId`, `peerNxId`, `handoffState`, `direction`).
7. The migration is additive and idempotent: 4 `CREATE TABLE` statements, every statement `IF NOT EXISTS`, and **no** `DROP`, `DELETE`, `ALTER` — so existing user data cannot be destroyed by 27→28.
8. `failureKind` is the only nullable column; everything else is `NOT NULL`, so a partial row cannot lose a key version or counter.

**What was NOT verified:** executing 27→28 on a real populated v27 SQLCipher database. There is no device, so this stays **NOT TESTED**. `SmsDatabaseInstrumentedTest` is written for it (schema as actually created via `PRAGMA table_info`, DAO CRUD, unique-part dedup, part survival across close/reopen, packet state transitions, purge semantics) and is ready to run with `./gradlew :app:connectedDebugAndroidTest` on any attached device.

---

## 6. Cryptographic verification evidence

| Property | Evidence | Status |
| :--- | :--- | :--- |
| X25519 provisioning key generated once and reused | `sessionKeysAreRecoveredAfterSimulatedProcessDeath` (identical keys across manager instances) | PASS (JVM) |
| Keystore wrapping / unwrapping | Uses existing `CryptoKeystoreManager` (`AndroidKeyStore`, AES-256-GCM); exercised on device only | NOT TESTED |
| Private key never stored in plaintext | Code path: wrap → hex → SQLCipher; root never persisted, wiped with `Arrays.fill` | PASS (review) |
| Public key exchange inside E2EE control channel | `sendSmsKeyProvisioning` / `SMS_KEY_PROVISION` ingest, unchanged; only public bytes leave | PASS (review) |
| HKDF-SHA256 direction keys | `roundTripAcrossRecoveredSessionUsesExpectedCounter`, key-independence tests (Part I) | PASS |
| Nonce uniqueness across restarts and concurrency | 50 sequential + 32 concurrent counters → all distinct nonces | PASS |
| Counter persistence | 0,1,2 → restart → 3; DB field already 4 | PASS |
| Counter overflow | `MAX_COUNTER` blocks, never wraps | PASS |
| Replay-window persistence | Window survives restart; duplicates stay rejected | PASS |
| Rotation / expiry / revocation | New key version each; old ciphertext rejected; revoked refuses all | PASS |
| Key loss (restored DB, missing KeyStore entry) | `localKeyMismatch`, not ready, no keys, no counter consumption | PASS |
| AAD binds message id + sender + recipient + key version + counter | Tampered tag, wrong message id, wrong sender, wrong recipient all `AUTHENTICATION_FAILED`; stale version `KEY_VERSION_MISMATCH` | PASS |
| Hardware-backed security | `CryptoKeystoreManager` requests the `AndroidKeyStore` provider only; no StrongBox/attestation check exists and a device capability check was impossible | **NOT CLAIMED / NOT TESTED** |

---

## 7. Physical-device test results

**NOT TESTED — no device, emulator, AVD or installed system image exists in this environment.**

- `adb devices` → empty device list (platform-tools present).
- `emulator -list-avds` → empty; `$LOCALAPPDATA/Android/Sdk/system-images` → absent.
- `emulator -accel-check` → WHPX available, so acceleration is not the blocker.
- `./gradlew :app:connectedDebugAndroidTest` → exit 1, `DeviceException: No connected devices!`

Therefore these remain unexecuted and unclaimed: key generation + persistence on device, app close/reopen key recovery, NX1 create/import on a real device, `ACTION_SENDTO` handoff, manual packet import, multipart reassembly, duplicate/out-of-order parts on device, ACK generation/verification on device, SQLCipher persistence after process death, and the entire 19-scenario matrix from Part I §19 (dual-SIM, airplane mode, carrier rejection, delayed/out-of-order multipart).

---

## 8. Remaining risks and blockers

1. **Device verification (blocker).** All on-device behaviour is unverified; the instrumented suite is written but cannot run here.
2. **Real 27→28 upgrade (blocker).** Additive-by-construction and schema-parity-checked, but not executed against a populated v27 DB.
3. **Hardware-backed Keystore (not claimed).** Without a device, "hardware-backed" cannot be asserted; the copy in the report and code says only "AndroidKeyStore-wrapped".
4. **Keystore invalidation.** If the master key is invalidated (lock-screen change on some devices, restore to a new device), SMS sessions become unusable by design (`localKeyMismatch`) and require re-provisioning over the Internet. The recovery path exists but is untested on device.
5. **Replay window depth.** Legitimate SMS reordering beyond 64 packets is treated as a replay and dropped (fail-closed). Documented, not device-tested.
6. **Mode A has no automatic ACK** — the receiver must copy the token; unchanged and correct, but manual.
7. **UI not visually reviewed** (NF-7).
8. **Provisioning UX** still has no explicit retry/backoff control when both peers are offline.
9. **Documentation gap** — the mandated NEXA Brain archive still does not exist in the repository.

---

## 9. Final verdict

**NEEDS REVIEW.**

Progress since the review: seven real defects in the key paths and one in transport gating were found and fixed (nonce reuse on re-provisioning, key-version reuse, counter overflow, state-blind allocation, keypair creation race, undetected key loss, group-chat bypass), the migration DDL is now executable from a single verified source, and Module 3 test coverage grew from 46 to 74 tests with the full suite at 761/0.

Why not PASS: the explicit instruction is that unit tests alone must not lift the verdict, and the two remaining gaps are exactly the device-dependent ones — no on-device execution of the key lifecycle or the real SQLCipher migration/DAO paths. Those are environmental blockers, not code claims, and are recorded as NOT TESTED rather than inferred.

What would make it PASS: run `./gradlew :app:connectedDebugAndroidTest` on a real device or an installed emulator image (all six DB integration tests), plus the key-lifecycle and handoff matrix — generate/persist keys, kill the process, reopen, recover, create and import an NX1 packet, hand off via `ACTION_SENDTO`, reassemble multipart with duplicate and out-of-order parts, verify an authenticated ACK, and confirm SQLCipher state after process death.
