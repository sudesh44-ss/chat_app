# NEXA — Phase B Security Test Report: Threat Vectors & Automated Test Results

**Date:** November 6, 2026  
**Execution Status:** 100% PASSING (Zero failures, Zero regressions)  

---

## 1. Automated Test Execution Summary

### 1.1 Backend WebSocket Authentication Suite (`nexa/wire/internal/connection`)

Command: `go test -v ./internal/connection`

```
=== RUN   TestSendChallenge
--- PASS: TestSendChallenge (0.00s)
=== RUN   TestVerifyAuthResponse_Valid
--- PASS: TestVerifyAuthResponse_Valid (0.02s)
=== RUN   TestVerifyAuthResponse_InvalidSignature
2026/11/06 22:06:45 [Session NX-BOB-456] Auth failed: cryptographic Ed25519 signature verification failed
--- PASS: TestVerifyAuthResponse_InvalidSignature (0.08s)
=== RUN   TestVerifyAuthResponse_ReplayAttack
2026/11/06 22:06:45 [Session NX-REPLAY-USER] Auth failed: no active challenge (replay or already used)
--- PASS: TestVerifyAuthResponse_ReplayAttack (0.01s)
=== RUN   TestVerifyAuthResponse_ExpiredChallenge
2026/11/06 22:06:45 [Session NX-TIMEOUT-USER] Auth failed: challenge expired
--- PASS: TestVerifyAuthResponse_ExpiredChallenge (0.01s)
=== RUN   TestVerifyAuthResponse_FailClosedWhenStoreNil
2026/11/06 22:06:45 [Session NX-UNTRUSTED] Auth failed: identity store unavailable (fail-closed, untrusted public key)
--- PASS: TestVerifyAuthResponse_FailClosedWhenStoreNil (0.00s)
=== RUN   TestVerifyAuthResponse_UnknownNxID
2026/11/06 22:06:45 [Session NX-NONEXISTENT] Auth failed: NX-ID NX-NONEXISTENT not found in authoritative identity store
--- PASS: TestVerifyAuthResponse_UnknownNxID (0.01s)
=== RUN   TestVerifyAuthResponse_UnknownDevice
2026/11/06 22:06:45 [Session NX-DEVICE-USER] Auth failed: unknown device dev_rogue_unknown for registered NX-ID NX-DEVICE-USER
--- PASS: TestVerifyAuthResponse_UnknownDevice (0.01s)
=== RUN   TestVerifyAuthResponse_RevokedDevice
2026/11/06 22:06:45 [Session NX-REVOKED-USER] Auth failed: device dev_compromised is revoked
--- PASS: TestVerifyAuthResponse_RevokedDevice (0.01s)
=== RUN   TestVerifyAuthResponse_CrossAccountDeviceImpersonation
2026/11/06 22:06:45 [Session NX-BOB] Auth failed: device dev_alice_phone belongs to NX-ALICE, not NX-BOB (impersonation rejected)
--- PASS: TestVerifyAuthResponse_CrossAccountDeviceImpersonation (0.01s)
=== RUN   TestVerifyAuthResponse_IdentityStoreValidation
2026/11/06 22:06:45 [Session NX-BOB-REGISTERED] Auth failed: unknown device dev_attacker for registered NX-ID NX-BOB-REGISTERED
--- PASS: TestVerifyAuthResponse_IdentityStoreValidation (0.01s)
PASS
ok  	nexa/wire/internal/connection	2.970s
```

---

### 1.2 Android Client Cryptographic Suite (`com.nexa.app.PhaseBIdentityAndWebSocketTests`)

Command: `.\gradlew testDebugUnitTest --tests "com.nexa.app.PhaseBIdentityAndWebSocketTests"`

```xml
<testsuite name="com.nexa.app.PhaseBIdentityAndWebSocketTests" tests="4" skipped="0" failures="0" errors="0" time="0.196">
  <testcase name="testEd25519ChallengeSigningAndVerification" classname="com.nexa.app.PhaseBIdentityAndWebSocketTests" time="0.178"/>
  <testcase name="testAuthoritativeEd25519DerivationMatchesBouncyCastle" classname="com.nexa.app.PhaseBIdentityAndWebSocketTests" time="0.002"/>
  <testcase name="testLegacyKeyMigrationLogic" classname="com.nexa.app.PhaseBIdentityAndWebSocketTests" time="0.008"/>
  <testcase name="testInMemorySeedZeroingSecurity" classname="com.nexa.app.PhaseBIdentityAndWebSocketTests" time="0.001"/>
</testsuite>
```

---

## 2. Threat Vector Verification Matrix

| Threat Vector | Attack Scenario | Defensive Action Taken | Result |
| :--- | :--- | :--- | :--- |
| **Arbitrary Public Key Impersonation** | Attacker connects with self-signed key claiming victim's NX-ID | Server compares against authoritative `identityStore` public key | **BLOCKED (4001)** |
| **Replay Attack** | Attacker replays previously intercepted `AUTH_RESPONSE` | Server clears `ChallengeNonce` on first read; rejects reused token | **BLOCKED (4001)** |
| **Stale / Delayed Response** | Attacker delays challenge response past 60s timeout | Server enforces `ChallengeExpiry`; rejects expired token | **BLOCKED (4001)** |
| **Rogue / Unknown Device** | Unregistered hardware attempts to open connection | Server inspects `GetDevicesForAccount`; rejects unknown device | **BLOCKED (4001)** |
| **Revoked Device Connection** | Stolen or decommissioned device connects | Server checks `device.IsRevoked == true`; terminates session | **BLOCKED (4001)** |
| **Cross-Account Impersonation** | Device A tries to authenticate on Device B's session | Server checks `device.NxID == session.NxId`; rejects mismatch | **BLOCKED (4001)** |
| **Pre-Auth Data Injection** | Client sends messages before cryptographic auth completes | Server drops packet and closes with code 4001 | **BLOCKED (4001)** |
| **Volatile Memory Seed Leak** | Private key seed left in JVM heap after signing | `withDecryptedPrivateKey` executes `Arrays.fill(raw, 0)` in `finally` | **MITIGATED** |
