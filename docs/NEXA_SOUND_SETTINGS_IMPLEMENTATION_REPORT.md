# NEXA Sound Settings — Implementation Report

## 1. Existing Sound Architecture Audit

### Pre-existing Components

- **NexaSoundManager** (`core/sound/NexaSoundManager.kt`): Centralized playback engine responsible for
  preview playback (single-category, synchronized), looping call ringtone management via MediaPlayer
  (`isLooping = true`), one-shot event sound playback with per-event deduplication and cooldown maps,
  transient/permanent audio focus management (API 26+ `AudioFocusRequest`; `requestAudioFocus` on older
  APIs), safe resource cleanup (`releaseAll()`), and a `previewingCategory: StateFlow<SoundCategory?>`
  for reactive UI feedback. Event cooldowns: `NEARBY_DEVICE` = 30 s; `CONNECTION_REQUEST` / `MESH_MESSAGE`
  = 5 s; unique-per-ID for transfer events.

- **AudioFileValidator** (`core/sound/AudioFileValidator.kt`): Validates user-selected audio files from
  Storage Access Framework (SAF) `Uri`s. Steps: (1) attempt `takePersistableUriPermission`; (2) query
  display name and file size from `ContentResolver`; (3) check extension (mp3, wav, m4a, ogg) and MIME
  type against an allowed set; (4) enforce a 15 MB size cap (`MAX_FILE_SIZE_BYTES`); (5) copy to
  `filesDir/sounds/custom_<category>.<ext>` (app-private, zero server upload); (6) verify decodability
  and enforce a 10-minute duration limit via `MediaMetadataRetriever`. Returns a sealed
  `AudioValidationResult` — either `Success(cachedUri, displayName, durationMs, fileSize)` or
  `Error(reason)`.

- **SoundPreferences** (`data/local/preferences/SoundPreferences.kt`): On-device persistent store backed
  by `SharedPreferences` ("nexa_sound_preferences"). Manages 12 `SoundCategory` entries with 4 keys each
  (`sound_mode_<cat>`, `sound_uri_<cat>`, `sound_name_<cat>`, `sound_updated_<cat>`). Exposes
  `preferencesState: StateFlow<Map<SoundCategory, SoundConfig>>` for reactive UI updates and provides
  `getConfig`, `updateConfig`, `resetToDefault`, and `resetAllToDefault`. Invalid stored mode strings
  fall back to `SoundMode.DEFAULT`.

- **NexaNotificationChannelManager** (`core/notification/NexaNotificationChannelManager.kt`): Creates and
  registers 6 Android 8.0+ (API 26+) notification channels idempotently via
  `createAllNotificationChannels(context)`:
  1. `channel_messages_direct` — Direct Messages (IMPORTANCE_HIGH)
  2. `channel_messages_group` — Group Chats (IMPORTANCE_DEFAULT)
  3. `channel_calls_incoming` — Incoming Calls (IMPORTANCE_HIGH, ringtone, full-screen intent capable)
  4. `channel_file_transfer` — File Transfer Alerts (IMPORTANCE_DEFAULT)
  5. `channel_mesh_discovery` — Mesh & Nearby Discovery (IMPORTANCE_LOW)
  6. `channel_connection_requests` — Connection Requests (IMPORTANCE_HIGH)

### Pre-existing Gap

- No Sound Settings UI screen existed; no Composable or ViewModel for per-category sound configuration.
- No `SettingsDestination.SOUND` enum value and no navigation route to a Sound Settings screen.
- `soundPreferences` and `soundManager` were not registered in `NexaApplication` (dependency graph gap).

---

## 2. Settings Navigation

A `SettingsDestination.SOUND` entry was added to the settings navigation graph directly after the
existing `NOTIFICATIONS` entry. In `SettingsScreen`, a tappable row using the established
`SettingsCategoryCard` pattern and `R.drawable.ic_nexa_music` icon navigates to `SoundSettingsScreen`.
The route is registered in the `NavHost` as `sound_settings`, passing no arguments (all state is loaded
from `SoundPreferences`).

---

## 3. Sound Category Configuration

All 12 categories and their section groupings:

| # | Enum Constant | Section | Default Display Name |
|---|--------------|---------|---------------------|
| 1 | `MESSAGE` | Messages | Default Notification Chime |
| 2 | `GROUP_MESSAGE` | Messages | Default Notification Chime |
| 3 | `CALL_RINGTONE` | Calls | Default Ringtone |
| 4 | `CONNECTION_REQUEST` | Connection Requests | Default Connection Chime |
| 5 | `TRANSFER_STARTED` | File Transfers | Default Transfer Start |
| 6 | `TRANSFER_COMPLETED` | File Transfers | Default Transfer Complete |
| 7 | `TRANSFER_FAILED` | File Transfers | Default Transfer Alert |
| 8 | `NEARBY_DEVICE` | Nearby & Mesh | Default Discovery Ping |
| 9 | `MESH_MESSAGE` | Nearby & Mesh | Default Mesh Chime |
| 10 | `MESH_TRANSFER_COMPLETED` | Nearby & Mesh | Default Mesh Complete |
| 11 | `NEW_STATUS` | Status | Default Status Chime |
| 12 | `STATUS_INTERACTION` | Status | Default Status Reaction |

---

## 4. Default and Local Sound Handling

| Mode | Behaviour |
|------|-----------|
| `DEFAULT` | `NexaSoundManager.resolveSoundUri()` calls `RingtoneManager.getDefaultUri(TYPE_RINGTONE)` for `CALL_RINGTONE`; `TYPE_NOTIFICATION` for all other categories. Always resolves to a valid system URI. |
| `LOCAL` | URI stored in preferences is first validated as a reachable `file://` path (`File.exists()`) or accessible `content://` URI. On success the file URI is returned directly. On failure (file moved/deleted), falls back to system default and logs a warning. |
| `SILENT` | `resolveSoundUri()` returns `null`; callers skip MediaPlayer creation entirely — no audio plays, no focus is requested. |

Validation and caching for LOCAL mode is performed by `AudioFileValidator.validateAndCacheAudio()`, which
copies the file to `filesDir/sounds/` making it immune to external storage revocation.

---

## 5. URI Permission and Local Storage Strategy

- **File picker**: Uses `ActivityResultContracts.OpenDocument()` with MIME filter
  `["audio/mpeg","audio/wav","audio/x-wav","audio/mp4","audio/ogg","audio/*"]`.
- **Persistable permission**: `ContentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
  is attempted for `content://` URIs but is handled gracefully if the provider does not grant it.
- **Private copy**: The selected file is copied to `context.filesDir/sounds/custom_<category>.<ext>`.
  This app-private path survives SAF permission revocation and is accessible without any storage
  permissions.
- **No public storage**: Files are never written to external/shared storage.
- **No server upload**: All audio data remains strictly on-device (Zero Server Upload architecture).

---

## 6. Sound Persistence

| Item | Detail |
|------|--------|
| Store | `SharedPreferences` file: `nexa_sound_preferences` (MODE_PRIVATE) |
| Keys per category | `sound_mode_<cat>` (String), `sound_uri_<cat>` (String\|null), `sound_name_<cat>` (String), `sound_updated_<cat>` (Long) |
| Reactive updates | `_preferencesState: MutableStateFlow` updated synchronously on every `updateConfig()` call |
| Reset | `resetToDefault(category)` writes DEFAULT config; `resetAllToDefault()` calls `clear()` and reloads |
| Invalid data handling | `SoundMode.valueOf()` wrapped in try/catch; unknown strings fall back to `DEFAULT` |

---

## 7. Central Playback Architecture

`NexaSoundManager` owns all MediaPlayer lifecycle management:

- **Preview subsystem**: `previewSound(category, customConfig?, onFinished?)` — stops any active preview,
  resolves URI, requests transient audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for notifications;
  `AUDIOFOCUS_GAIN` for ringtones), creates a `MediaPlayer`, and updates `previewingCategory` StateFlow.
  `onCompletion` and `onError` listeners release the player and clear state.
- **Call ringtone subsystem**: `startCallRingtone()` / `stopCallRingtone()` — creates a looping
  `MediaPlayer` with `USAGE_NOTIFICATION_RINGTONE`. Requests permanent audio focus. Pauses on transient
  focus loss; resumes on `AUDIOFOCUS_GAIN`.
- **Event sound subsystem**: `playEventSound(category, eventKey?)` — lightweight one-shot playback on
  `Dispatchers.IO`. Checks deduplication map before playing; records timestamp on play. Players are
  self-releasing via `onCompletion`/`onError`.
- **Audio focus**: API 26+ uses `AudioFocusRequest.Builder`; older APIs use deprecated
  `requestAudioFocus(listener, stream, gain)`. Focus is released in `abandonAudioFocus()` called from
  every player release path.
- **Cleanup**: `releaseAll()` stops preview and ringtone, clears deduplication map — called from
  `SoundSettingsViewModel.onCleared()`.

---

## 8. Event Integration

| SoundCategory | Application Event | Integration Layer |
|--------------|-------------------|------------------|
| `MESSAGE` | Incoming direct E2EE message (foreground) | Message receive handler / NotificationDispatcher |
| `GROUP_MESSAGE` | Incoming group chat message (foreground) | Group message receive handler |
| `CALL_RINGTONE` | Incoming P2P call | Call state machine (ringing state entry) |
| `CONNECTION_REQUEST` | Peer sends connection/handshake request | Handshake protocol layer |
| `TRANSFER_STARTED` | File transfer begins (P2P or swarm) | File transfer session manager |
| `TRANSFER_COMPLETED` | File transfer completes + passes security scan | File transfer session manager |
| `TRANSFER_FAILED` | File transfer fails or is security-blocked | File transfer session manager |
| `NEARBY_DEVICE` | New nearby peer node detected (BLE/WiFi-Direct scan) | Peer discovery engine (30 s cooldown) |
| `MESH_MESSAGE` | Offline DTN/store-and-forward mesh message arrives | DTN bundle ingestion layer |
| `MESH_TRANSFER_COMPLETED` | Offline bundle relay transfer completes | Mesh transfer manager |
| `NEW_STATUS` | Contact publishes encrypted 24 h status story | Status ingestion handler |
| `STATUS_INTERACTION` | Contact reacts/replies to user's status story | Status reaction handler |

> [!IMPORTANT]
> `NexaSoundManager.playEventSound()` must be called from each corresponding transport or event-dispatch
> layer. These call sites are **not modified** in this implementation; they are pre-existing integration
> points that must be wired up separately.

---

## 9. Android Notification Channel Limitations

> [!CAUTION]
> Android notification channels (API 26+) have **system-controlled** sound settings. Once a channel is
> created, its sound can only be changed by the user via Android Settings — not programmatically by the
> app.

### Channels where in-app sound setting takes immediate effect

- Foreground in-app event sounds for `MESSAGE`, `GROUP_MESSAGE` (played by `NexaSoundManager`) ✅

### Channels requiring versioned migration or user action for sound changes

| Channel ID | Purpose | Issue |
|-----------|---------|-------|
| `channel_messages_direct` | Direct message background notifications | Sound locked after first creation |
| `channel_messages_group` | Group chat background notifications | Sound locked after first creation |
| `channel_calls_incoming` | Incoming call notifications | Ringtone locked after first creation |

**Mitigation strategy**: To change background notification sounds programmatically, create versioned
channel IDs (e.g. `channel_messages_direct_v2`) on the next app update. The old channel can then be
deleted after migration.

The Sound Settings screen manages in-app audio for foreground events. For background notifications,
`NexaNotificationChannelManager.openChannelSettings(context, channelId)` can deep-link the user to the
Android system channel settings page.

---

## 10. Android Version Compatibility

| Feature | Min API | Notes |
|---------|---------|-------|
| Notification channels | API 26 (O) | Guarded by `Build.VERSION.SDK_INT >= O` |
| `AudioFocusRequest` (new API) | API 26 (O) | Falls back to deprecated `requestAudioFocus` on < 26 |
| `ACTION_OPEN_DOCUMENT` SAF | API 19 (KitKat) | Used via `ActivityResultContracts.OpenDocument()` |
| `POST_NOTIFICATIONS` permission | API 33 (T) | Required to post notifications on Android 13+ |
| `MediaMetadataRetriever` | API 10+ | Used in `AudioFileValidator` for duration check |
| `takePersistableUriPermission` | API 19+ | Gracefully ignored if provider does not support it |

---

## 11. Security and Resource Management

| Concern | Approach |
|---------|---------|
| Server upload | **Zero** — no audio data leaves the device |
| Storage permissions | `READ_EXTERNAL_STORAGE` is **not** requested; SAF provides scoped access |
| File storage | App-private `context.filesDir/sounds/` — not visible to other apps or media scanners |
| Size limit | 15 MB maximum enforced during both metadata query and streaming copy |
| Duration limit | 10 minutes (600,000 ms) enforced via `MediaMetadataRetriever.METADATA_KEY_DURATION` |
| MediaPlayer leaks | Released in `onCompletion`, `onError`, `stopPreview()`, `stopCallRingtone()`, `releaseAll()` |
| Audio focus | Requested before playback; abandoned in every release path; `AudioFocusRequest` stored for API 26+ abandon call |
| Corrupted files | `MediaMetadataRetriever` exception → `Error` result, copied file deleted |

---

## 12. Tests

The following unit tests are located in
`app/src/test/java/com/nexa/app/sound/SoundSettingsTest.kt`.
All tests are local JVM unit tests (no Android instrumentation required) using an `InMemorySharedPrefs`
implementation to avoid Android framework dependencies.

### SoundPreferencesTest

| # | Test Method | Description |
|---|------------|-------------|
| 1 | `defaultMode_allCategoriesDefaultOnFirstLoad()` | Verifies all 12 `SoundCategory` entries load with `SoundMode.DEFAULT` when `SharedPreferences` is empty |
| 2 | `updateConfig_persistsAndEmitsCorrectly()` | Sets `MESSAGE` to `SILENT`, asserts `getConfig` returns `SILENT` |
| 3 | `updateConfig_categoriesAreIndependent()` | Confirms updating `MESSAGE` leaves `GROUP_MESSAGE` at `DEFAULT` |
| 4 | `resetToDefault_restoresDefaultMode()` | Sets `CALL_RINGTONE` to `LOCAL`, calls `resetToDefault`, asserts back to `DEFAULT` with null URI |
| 5 | `loadConfigFromPrefs_invalidModeString_fallsBackToDefault()` | Pre-seeds prefs with `"GARBAGE"` as the mode string; asserts `getConfig` returns `DEFAULT` |
| 6 | `localMode_missingUri_displayNamePreserved()` | Stores `LOCAL` mode with null URI; asserts display name is preserved after round-trip |

### SoundConfigTest

| # | Test Method | Description |
|---|------------|-------------|
| 7 | `soundConfig_defaultValues_areCorrect()` | `SoundConfig(category)` has `DEFAULT` mode, null URI, and category's `defaultDisplayName` |
| 8 | `soundMode_silentMeansNoPlayback()` | `SoundMode.SILENT.label == "Silent"`, `DEFAULT.label == "Default Sound"`, `LOCAL.label == "Local Sound File"` |
| 9 | `soundCategory_allTwelvePresent()` | `SoundCategory.values()` returns exactly 12 entries; all 12 names spot-checked |

### AudioValidationResultTest

| # | Test Method | Description |
|---|------------|-------------|
| 10 | `audioValidationResult_successHasCorrectFields()` | Constructs `AudioValidationResult.Success`, asserts `displayName`, `durationMs`, `fileSize`, and `cachedUri` |
| 11 | `audioValidationResult_errorHasReason()` | Constructs `AudioValidationResult.Error`, asserts `reason` string and that it is not a `Success` |

> [!NOTE]
> Instrumented tests covering `MediaPlayer` preview playback and `AudioManager` focus behaviour require
> an Android device or emulator and are outside the scope of local unit tests.

---

## 13. Remaining Limitations

1. **Android notification channel sounds cannot be changed programmatically** after a channel is first
   created. Background notification sounds for `channel_messages_direct`, `channel_messages_group`, and
   `channel_calls_incoming` require versioned channel IDs (e.g. `_v2`) to migrate users to new sounds on
   app update, plus a user action to dismiss the old channel.

2. **Local file access after app clear or reinstall**: If the user clears app data or reinstalls, all
   files in `filesDir/sounds/` are deleted. The stored URI in preferences will become stale. Copying to
   `filesDir` mitigates external-storage permission revocation but not app data wipes.

3. **SoundPool not used**: `MediaPlayer` is used for all event sounds. This is acceptable for
   low-frequency events (messages, transfers, nearby discovery) but may introduce a small latency on
   first play. `SoundPool` would be more suitable for extremely rapid repeated triggers but adds
   complexity for 10+ concurrent sound slots.

4. **No bundled OGG/audio assets**: NEXA does not ship custom default sound files in the APK `assets/`
   directory. The `DEFAULT` mode falls back to the Android system notification or ringtone URI, which
   varies by device manufacturer and Android version. Bundled assets (e.g., `nexa_message.ogg`) would
   provide a consistent brand identity across all devices.

5. **Event playback integration not wired**: Calling `NexaSoundManager.playEventSound(category, key)`
   from transport and event-dispatch layers (message receive, transfer state machine, DTN bundle
   ingestion, peer discovery engine, status handlers) is **not implemented** in this delivery. The
   integration hook points are pre-existing in the transport/notification infrastructure; wiring them up
   requires a separate integration task to avoid unintended regressions in those layers.
