# NEXA 3-Tier Profile Photo Priority & Fallback Architecture

## 1. Executive Summary & Core Rules

In decentralized and local-first messaging, user identity presentation requires a resilient balance between:
1. **Network Identity Authority:** The peer's self-chosen profile photo uploaded to VEYRA/NEXA storage.
2. **Personal Contact Convenience:** The photo already assigned to that person in the user's local phone address book.
3. **Fail-Safe Deterministic Fallback:** High-contrast alphabet initial avatars when no photo is available or network loading fails.

### The 3-Tier Priority & Fallback Cascade

```text
Jab Person 1, Person 2 ko dekhe:

1️⃣ Person 2 ki VEYRA profile photo hai?
       ↓ YES
   → Person 2 ki official profile photo show (Priority 1)

       ↓ NO

2️⃣ Person 1 ke Contacts mein Person 2 ke liye photo saved hai?
       ↓ YES
   → Person 1 ke local phone contact wali photo show (Priority 2)

       ↓ NO

3️⃣ Dono jagah photo nahi hai (ya network image load fail ho jaye)
   → Person 2 ke effective naam ka alphabet/initial show (Priority 3)
```

---

## 2. Priority Resolution Logic (`NexaAvatar.kt`)

The resolution cascade is modeled as an immutable sealed structure:

```kotlin
sealed class ResolvedAvatar {
    data class NetworkOrLocalUri(val model: Any) : ResolvedAvatar()
    data class InitialAvatar(val char: Char, val backgroundColorHex: Long) : ResolvedAvatar()
}

fun resolveAvatar(
    nexaAvatarPathOrUrl: String?,
    deviceContactPhotoUri: String?,
    effectiveName: String
): ResolvedAvatar {
    // Priority 1: VEYRA / NEXA official profile photo
    if (!nexaAvatarPathOrUrl.isNullOrBlank()) {
        return ResolvedAvatar.NetworkOrLocalUri(nexaAvatarPathOrUrl)
    }

    // Priority 2: Device phone contacts saved photo
    if (!deviceContactPhotoUri.isNullOrBlank()) {
        return ResolvedAvatar.NetworkOrLocalUri(deviceContactPhotoUri)
    }

    // Priority 3: Alphabet Initial Avatar (Deterministic Color Hash)
    val firstChar = effectiveName.trim().firstOrNull()?.uppercaseChar() ?: '?'
    val colorHex = getDeterministicColorForName(effectiveName)
    return ResolvedAvatar.InitialAvatar(firstChar, colorHex)
}
```

### Deterministic Palette Color Mapping
To ensure that an initial avatar always renders with the exact same background color across all screens and restarts without requiring database storage, a deterministic hashing algorithm selects from a curated Material palette:

```kotlin
fun getDeterministicColorForName(name: String): Long {
    val palette = listOf(
        0xFF1E88E5, // Blue
        0xFF43A047, // Green
        0xFF8E24AA, // Purple
        0xFFE53935, // Red
        0xFFFB8C00, // Orange
        0xFF00ACC1, // Cyan
        0xFF3949AB  // Indigo
    )
    val hash = kotlin.math.abs(name.hashCode())
    return palette[hash % palette.size]
}
```

---

## 3. Database Schema & Migration (Room / SQLCipher v16)

To store local address book photo URIs and remote avatar URLs while maintaining zero cloud metadata leakage, `NexaDatabase` was bumped to version **16** with `MIGRATION_15_16`:

```kotlin
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `contacts` ADD COLUMN `deviceContactPhotoUri` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `contacts` ADD COLUMN `nexaAvatarUrl` TEXT DEFAULT NULL")
    }
}
```

### Address Book Photo URI Ingestion (`ContactMatchingRepository.kt`)
Android's `ContactsContract.CommonDataKinds.Phone.PHOTO_URI` is extracted in local memory via `ContentResolver`:

```kotlin
val projection = arrayOf(
    ContactsContract.CommonDataKinds.Phone.NUMBER,
    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
    ContactsContract.CommonDataKinds.Phone.PHOTO_URI
)
```

---

## 4. Jetpack Compose Universal Components

### A. High-Performance Alphabet Initial Avatar (`InitialAvatarView.kt`)
Draws a centered letter scaled proportionally to 45% of the circle diameter:

```kotlin
@Composable
fun InitialAvatarView(
    initial: Char,
    backgroundColor: Long,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(backgroundColor)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45).sp,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
```

### B. Universal Production Avatar (`NexaAvatar.kt`)
Combines Coil image loading, disk/memory caching, crossfade, and multi-tier fallbacks:

- **During Loading:** Displays the Priority 3 Initial Avatar in place of an empty white void.
- **On Error:** If Priority 1 network load fails (e.g. offline or broken CDN link), it automatically falls back to Priority 2 (Device Contact Photo); if Priority 2 also fails or is absent, it seamlessly renders Priority 3 (Initial Avatar).

---

## 5. Real-Time Update Pipeline (Zero-Lag Sync)

When User B updates or deletes their profile photo:

1. **Sender Action:** Photo is compressed to clean WebP format via `MediaSanitizer` and stored on Cloudflare R2 blob storage.
2. **Control Packet Broadcast:** Sender's client dispatches a lightweight encrypted control packet:
   ```json
   {
     "type": "PROFILE_UPDATE",
     "nxId": "NX-7F82K9A4",
     "newAvatarUrl": "https://pub-r2.veyra.app/avatars/hash_123.webp",
     "timestamp": 1731568200000
   }
   ```
3. **Recipient Local DB Ingestion (`ChatRepositoryImpl.kt`):**
   ```kotlin
   if (decryptedText.startsWith("{") && decryptedText.contains("\"type\":\"PROFILE_UPDATE\"")) {
       handleProfileUpdateControlPacket(decryptedText)
       return@withContext // Consumed silently without writing raw JSON to chat history
   }
   ```
   Updates `contacts` table: `contactDao.updateAvatarUrl(nxId, newAvatarUrl)`.
4. **Instant UI Recomposition:** Jetpack Compose observers (`remember(nexaAvatarUrl, ...)`) recompose the avatar at 0ms latency without requiring screen reload.
5. **Photo Removal:** If `newAvatarUrl` is `null` or omitted, the client sets `nexaAvatarUrl = null` in Room DB, automatically dropping the display to Priority 2 (Contact Photo) or Priority 3 (Initial Avatar).

---

## 6. Universal UI Consistency

The `NexaAvatar` component is deployed across the entire application interface:

| Screen / Component | Location | Avatar Behavior |
| :--- | :--- | :--- |
| **Chat Conversation List** | [`MainChatDashboardScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt) | 44dp avatar per conversation item |
| **Chat Screen Top Bar** | [`NexaChatTopBar.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/chat/components/NexaChatTopBar.kt) | 38dp clickable avatar opening Peer Profile Dialog |
| **Mutual Contacts List** | [`ContactListScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/ContactListScreen.kt) | 46dp avatar clickable to open 3-Tier Identity Details |
| **Device Contacts List** | [`ContactListScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/ContactListScreen.kt) | 46dp avatar using phone address-book `PHOTO_URI` |
| **Peer Identity Dialog** | [`PeerProfileDialog.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/components/PeerProfileDialog.kt) | 36dp header avatar & 72dp Hero profile photo |
| **Inbound Friend Requests** | [`RequestItemCard.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/components/RequestItemCard.kt) | 44dp avatar in request cards |

---

## 7. Verification & Automated Test Results

The architecture is covered by automated unit tests in [`ProfilePhotoPriorityUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/ProfilePhotoPriorityUnitTests.kt):

| Test Case | Description | Result |
| :--- | :--- | :---: |
| `case1_nexaAvatarPresent_priority1WinsOverContactPhoto` | Verifies Priority 1 (VEYRA Photo A) wins over Contact Photo B | **PASSED** |
| `case2_nexaAvatarAbsent_priority2ContactPhotoWins` | Verifies Priority 2 (Contact Photo B) is selected when VEYRA photo is null/blank | **PASSED** |
| `case3_neitherPhotoExists_priority3InitialAvatarGenerated` | Verifies Priority 3 ('R' for Rahul, 'A' for Amit, 'P' for Priya, '?' for empty) | **PASSED** |
| `deterministicColorPalette_isStableAndConsistent` | Verifies identical color output for identical names from Material palette | **PASSED** |
| `profileUpdateControlPacket_jsonSerializationAndParsing` | Validates E2EE control packet serialization, parsing, and photo removal | **PASSED** |
| `contactEntity_effectiveAvatarUrlResolvesCorrectly` | Verifies remote CDN URL taking precedence over local cached path | **PASSED** |

### Compilation & Build Verification:
- **Unit Tests:** `gradlew.bat testDebugUnitTest --tests com.nexa.app.ProfilePhotoPriorityUnitTests` $\rightarrow$ **BUILD SUCCESSFUL** (28/28 tasks executed/up-to-date).
- **Application Assembly:** `gradlew.bat assembleDebug` $\rightarrow$ **BUILD SUCCESSFUL** (40 actionable tasks completed, debug APK packaged).
