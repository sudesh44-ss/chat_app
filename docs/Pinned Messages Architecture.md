# NEXA Pinned Messages Architecture & Engineering Specification

## 1. Executive Summary & Core Guarantees

The NEXA Pinned Messages Architecture is built to combine the best UX conventions of Telegram and WhatsApp while strictly preserving NEXA's core engineering tenets: **Local-First**, **Zero Server Metadata**, **End-to-End Encryption (E2EE)**, and **Zero Background Battery/CPU Drain**.

### Core Guarantees:
1. **Zero Background CPU Drain (Lazy SQL Engine):** No perpetual background threads or periodic alarms check message timestamps. Expired pins are filtered at SQLite query time:
   ```sql
   SELECT * FROM messages
   WHERE conversationId = :conversationId
     AND isPinned = 1
     AND (pinnedExpiresAt IS NULL OR pinnedExpiresAt > :currentTimeMillis)
   ORDER BY pinnedAt DESC
   LIMIT 4
   ```
   Expired pins are also lazily and silently cleaned up (`isPinned = 0`) when a chat screen is initialized (`clearExpiredPins`), without ever deleting the message content.
2. **Hard Limit of Max 4 Pinned Messages:** A chat can hold at most 4 active pins. Tapping "Pin" when 4 pins are active triggers an immediate, non-intrusive Snackbar warning:
   > *"Pin limit reached: You can pin up to 4 messages in this chat. Unpin an existing message to pin another one."*
3. **End-to-End Encrypted Control Packets:** Pin/unpin state synchronization is sent as an invisible, encrypted Double Ratchet control packet (`PIN_MESSAGE` and `UNPIN_MESSAGE`). The central servers and relays have **zero knowledge** of what messages are pinned or for how long.
4. **Multi-Transport Interoperability:** Control packets travel transparently over any active transport: Zero-Disk WebSocket RAM Relay, Port 8901 Binary SMS, or Offline Store-Carry-Forward Mesh (DTN).

---

## 2. Database Schema & Migration (Room / SQLCipher v15)

To maintain database integrity, `NexaDatabase` was bumped to version **15** with an automated migration `MIGRATION_14_15`:

```kotlin
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN pinnedExpiresAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN pinnedAt INTEGER DEFAULT NULL")
    }
}
```

### Data Access Object (`MessageDao.kt`)
The DAO exposes real-time reactive Flow observation, direct queries, and atomic pin status updates:

```kotlin
@Query("""
    SELECT * FROM messages 
    WHERE conversationId = :conversationId 
      AND isPinned = 1 
      AND (pinnedExpiresAt IS NULL OR pinnedExpiresAt > :currentTimeMillis)
    ORDER BY pinnedAt DESC 
    LIMIT 4
""")
fun getPinnedMessagesFlow(conversationId: String, currentTimeMillis: Long): Flow<List<MessageEntity>>

@Query("""
    UPDATE messages 
    SET isPinned = :isPinned, 
        pinnedExpiresAt = :pinnedExpiresAt, 
        pinnedAt = :pinnedAt 
    WHERE packetId = :packetId
""")
suspend fun updatePinStatus(packetId: String, isPinned: Boolean, pinnedExpiresAt: Long?, pinnedAt: Long?)

@Query("""
    UPDATE messages 
    SET isPinned = 0, 
        pinnedExpiresAt = NULL, 
        pinnedAt = NULL 
    WHERE conversationId = :conversationId 
      AND isPinned = 1 
      AND pinnedExpiresAt IS NOT NULL 
      AND pinnedExpiresAt <= :currentTimeMillis
""")
suspend fun clearExpiredPins(conversationId: String, currentTimeMillis: Long): Int
```

---

## 3. Cryptographic E2EE Peer Synchronization (Wire Protocol)

When a user pins or unpins a message, the action is synchronized with the peer using encrypted payload packets. The peer's client intercepts these packets prior to Room DB insertion, updating the target message's metadata without polluting the chat stream with raw JSON strings.

### A. Pin Control Packet Schema (`PIN_MESSAGE`)
```json
{
  "type": "PIN_MESSAGE",
  "targetMessageId": "d9812730-b912-4cf0-9d3e-908129841203",
  "expiresAt": 1700086400000,
  "pinnedAt": 1700000000000,
  "pinnedBy": "NX-ALICE-77"
}
```

### B. Unpin Control Packet Schema (`UNPIN_MESSAGE`)
```json
{
  "type": "UNPIN_MESSAGE",
  "targetMessageId": "d9812730-b912-4cf0-9d3e-908129841203",
  "unpinnedBy": "NX-ALICE-77"
}
```

### C. Client Interception Engine (`ChatRepositoryImpl.kt`)
Incoming message streams (both WebSocket and Carrier Data SMS) inspect decrypted strings:
```kotlin
if (decryptedBytes != null) {
    if (decryptedText.startsWith("{") && 
        (decryptedText.contains("\"type\":\"PIN_MESSAGE\"") || decryptedText.contains("\"type\":\"UNPIN_MESSAGE\""))) {
        handlePinControlPacket(decryptedText)
        return@withContext // Consumed as control packet; not added to message history
    }
}
```

---

## 4. Jetpack Compose UI/UX Presentation Layer

### A. Pin Duration Dialog (`PinDurationDialog.kt`)
Allows the user to select from 5 predefined durations:
- **24 hours** (`86,400,000 ms`)
- **48 hours** (`172,800,000 ms`)
- **7 days** (`604,800,000 ms`)
- **30 days** (`2,592,000,000 ms`)
- **Until I unpin it** (`null` / Indefinite)

### B. Multi-Pin Banner (`PinnedMessageBanner.kt`)
A single compact surface pinned right below the chat top bar and contextual banner:
- Left: Material `PushPin` vector icon tinted with `MaterialTheme.colorScheme.primary`.
- Center: `AnimatedContent` cross-fading preview text truncated to 60 characters.
- Right: Sequential badge indicator (e.g., `1/4`, `2/3`, `1/1`).
- Tapping the banner triggers cyclic jumping:
  ```kotlin
  val curr = uiState.currentPinnedIndex.coerceIn(0, pinnedUiModels.lastIndex)
  val target = pinnedUiModels[curr]
  coroutineScope.launch {
      listState.animateScrollToItem(target.originalListIndex)
  }
  viewModel.onEvent(ChatUiEvent.OnPinnedBannerClicked)
  ```
  On banner tap, `currentPinnedIndex` increments cyclically (`(curr + 1) % size`), the LazyColumn smoothly scrolls to the original message position, and a 2.dp primary border highlights the target bubble for 1200ms.

### C. Message Bubble Integration (`MessageBubble.kt`)
- **Visual Indicator:** If `message.isPinned`, a small `PushPin` icon is displayed adjacent to the timestamp in the bubble footer.
- **Highlight Glow:** If `isHighlighted == true`, a 2.dp primary accent border is rendered around the bubble.
- **Long-Press Menu:** Long-pressing any bubble launches the Contextual Action Dialog, allowing instant Pinning, Unpinning, or Text Copying.

---

## 5. Verification & Test Suite Summary

The architecture is covered by automated unit tests in [`PinnedMessagesUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/PinnedMessagesUnitTests.kt):

| Test Case | Description | Result |
| :--- | :--- | :---: |
| `pinDuration_durationMillisMatchesSpecification` | Validates millisecond accuracy for 24h, 48h, 7d, 30d, and indefinite durations | **PASSED** |
| `pinExpiry_activeAndExpiredFilterEvaluation` | Tests that past timestamps are excluded while future and null expirations are retained | **PASSED** |
| `pinLimit_max4PinsEnforced` | Enforces that a 5th pin is rejected when 4 pins already exist | **PASSED** |
| `pinControlPacket_jsonSerializationAndParsing` | Validates E2EE wire payload round-trip and order-independent JSON parsing | **PASSED** |
| `messageEntity_toDomainAndToEntityRoundTrip` | Verifies Room database entity to domain model mapping with pin fields | **PASSED** |
| `bannerCyclicNavigation_indexWrapsCorrectly` | Verifies modulo navigation wrapping across 1..4 pins | **PASSED** |
| `bannerPreviewText_truncation` | Verifies safe 60-character preview truncation | **PASSED** |

### Compilation & Build Verification:
- **Unit Tests:** `gradlew.bat testDebugUnitTest --tests com.nexa.app.PinnedMessagesUnitTests` $\rightarrow$ **BUILD SUCCESSFUL** (28/28 tasks up-to-date/executed).
- **Application Packaging:** `gradlew.bat assembleDebug` $\rightarrow$ **BUILD SUCCESSFUL** (40 actionable tasks completed, outputting `app-debug.apk`).
