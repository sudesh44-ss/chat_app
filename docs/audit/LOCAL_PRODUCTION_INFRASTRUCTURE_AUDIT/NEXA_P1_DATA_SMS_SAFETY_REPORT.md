# NEXA P1-B Blocker Remediation Report: Data SMS Safety Gating

## Executive Summary
In previous audits, binary Data SMS on Port 8901 was identified as a critical risk factor:
1. `SmsManager.sendDataMessage()` requires `android.permission.SEND_SMS`.
2. In production release builds, telephony permissions (`SEND_SMS`, `RECEIVE_SMS`, `READ_PHONE_STATE`) were deliberately removed from `AndroidManifest.xml` to comply with Google Play Protect policies and Android 15 Enhanced Confirmation Mode (ECM).
3. If an end-user switched transport to Data SMS and attempted to send a message, the runtime would throw an unhandled `SecurityException`, causing the application to crash.

P1-B has been remediated by implementing safety gating at both repository and dispatcher layers, preventing crashes, transitioning message state cleanly to `FAILED`, alerting the user, and providing visual disabled cues in the UI transport selector.

---

## 1. Compliance Architecture & Distinction from Reverse SMS
It is critical to distinguish between the two SMS mechanisms in NEXA:
- **Reverse SMS Auto-Verification (Onboarding):** Uses Android system `Intent.ACTION_SENDTO` with `smsto:+91...`. The user's default system SMS app handles transmission. Zero telephony permissions required; 100% Google Play Protect compliant.
- **Binary Data SMS (Port 8901 Messaging):** Uses direct background `SmsManager.sendDataMessage()`. Requires sensitive runtime permissions. Blocked on production Google Play builds without an approved SMS declaration exception.

---

## 2. Changes Implemented

### A. Sender Safety Gating
In `app/src/main/java/com/nexa/app/data/remote/sms/NexaDataSmsSender.kt`:
- Added `isSupported(): Boolean`:
  ```kotlin
  fun isSupported(): Boolean {
      return ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.SEND_SMS
      ) == PackageManager.PERMISSION_GRANTED
  }
  ```
- Gated `sendDataSms(...)`:
  ```kotlin
  if (!isSupported()) {
      Log.w(TAG, "Data SMS transport blocked: SEND_SMS permission is omitted in this build for security compliance")
      return Result.failure(
          UnsupportedOperationException(
              "Data SMS transport is disabled in this build for Google Play Protect compliance. Please use Internet or DTN Mesh."
          )
      )
  }
  ```
  This eliminates unhandled `SecurityException` runtime crashes.

### B. Repository Fail-Closed Handling
In `app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt`:
- In `sendMessage(...)` under `TransportType.DATA_SMS`:
  ```kotlin
  if (!dataSmsSender.isSupported()) {
      messageDao.updateMessageStatus(packetId, MessageStatus.FAILED.name)
      val error = UnsupportedOperationException(
          "Data SMS transport is disabled in this build for Google Play Protect compliance. Please use Internet or DTN Mesh."
      )
      Log.w(TAG, "Data SMS transmission blocked for packet $packetId: transport is disabled")
      return@withContext Result.failure(error)
  }
  ```
  The message is stored locally and immediately marked as `FAILED`, triggering the existing retry / transport switch UI.

### C. UI Transport Selection Safety
- In `app/src/main/java/com/nexa/app/presentation/chat/components/NexaTransportSelector.kt`:
  - Added `isSmsSupported: Boolean = false` parameter.
  - When `false`, the SMS option is rendered with reduced opacity (`alpha = 0.5f`), an explicit `(Disabled)` badge, and click events are disabled (`clickable(enabled = false)`).
- In `app/src/main/java/com/nexa/app/presentation/chat/components/TransportToggleBar.kt` and `TransportSelectorMenu.kt`:
  - Added `isSmsSupported` gating and disabled visual states.

---

## 3. Verification & Unit Tests
Implemented JVM unit test suite in `app/src/test/java/com/nexa/app/DataSmsSafetyGatingUnitTests.kt`:
1. `testDataSmsSendingFailsClosedWhenUnsupported`: Confirms `Result.failure(UnsupportedOperationException)` is returned instead of crashing.
2. `testBlankDestinationPhoneNumberFailsValidation`: Verifies validation before SMS dispatch.
3. `testMessageStatusTransitionsToFailedOnUnsupportedTransport`: Confirms message state updates to `MessageStatus.FAILED`.
4. `testUiTransportOptionDisabledState`: Validates UI option gating.
5. `testPayloadCodecChunkingAndReassemblyRemainsCompatible`: Validates 133-byte chunking math and reassembly.
6. `testDataSmsPortConstantPreserved`: Confirms Port 8901 and 133-byte limits are strictly preserved.

---

## 4. Status
- **Status:** REMEDIATED & VERIFIED
- **Residual Risk:** None. The application operates safely without telephony permissions, and Reverse SMS onboarding is completely unaffected.
