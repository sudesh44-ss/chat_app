# NEXA — Dedicated Sound Settings & Local Custom Audio System
## Architectural Audit, Technical Specification & Implementation Report

**Document Version:** 1.0.0  
**Status:** Audit Complete — Ready for Implementation  
**Target Environment:** NEXA Android (Jetpack Compose / Android Keystore / Room / SQLCipher / Audio HAL)  
**Settings Route:** `sound_settings`  

---

## 1. Executive Summary & Mandatory Project Audit

NEXA is a local-first, zero-knowledge privacy messenger. Currently, audio alerts and notifications are tightly coupled to system defaults or scattered across notification channels without user-configurable local sound options or ringtones.

### Existing System Audit:
1. **Settings Navigation:** Main `SettingsScreen.kt` has 13 menu items (`ACCOUNT`, `PRIVACY_AND_SAFETY`, `SECURITY`, `APPEARANCE`, `NOTIFICATIONS`, `LANGUAGE`, etc.). There is **no dedicated Sound section**; sound was previously referenced in `NotificationSettingsScreen.kt` as an external intent to Android system settings (`Configure Ringtone in Android Settings`).
2. **Notifications Architecture:** `NotificationSettingsScreen.kt` and `NotificationPrefs.kt` govern behavioral delivery policies (direct messages, groups, calls, mentions, lock-screen visibility, quiet hours, badge counts). They do **not** configure sound assets, audio modes, or custom file selections.
3. **Android Notification Channels:** `NexaNotificationChannelManager.kt` registers 6 channels (`CHANNEL_MESSAGES_DIRECT`, `CHANNEL_MESSAGES_GROUP`, `CHANNEL_CALLS_INCOMING`, `CHANNEL_FILE_TRANSFER`, `CHANNEL_MESH_DISCOVERY`, `CHANNEL_CONNECTION_REQUESTS`). On Android 8.0+ (API 26+), notification channel sound settings are immutable once created. An app cannot programmatically alter an existing channel's sound without versioned channel migration or directing the user to system settings.
4. **Audio Playback Capabilities:** No centralized sound manager exists. No preview mechanism, audio validator, or SAF audio file picker exists in the codebase.
5. **Event Pipelines:**
   - Inbound messages (`NexaUniversalTransportIngest.kt`) trigger `notificationDispatcher.postDirectMessageNotification`.
   - File transfers (`P2PTransferRepositoryImpl.kt`) emit progress without distinct start/completion/failure audio chimes.
   - Nearby mesh discovery (`LanDiscoveryBridge.kt`) and connection requests trigger silent or default notifications.
   - Status updates are purely visual without notification chimes.

---

## 2. Architectural Design & Separation of Concerns

### Invariant 1: Sound Must Remain Distinct from Notifications
- **Notifications Screen:** Controls *when* and *how* notifications are shown (enable/disable, preview, lock screen, vibration, quiet hours).
- **Sound Screen:** Controls *what audio plays* (Default, Local custom file, Silent) across 12 distinct event categories.

### Invariant 2: Independent Categories
Configuring a sound in one category (e.g. Group Message Sound) has zero impact on direct message sounds or other categories.

### Invariant 3: Zero Server Knowledge & Zero Leakage
Local audio files are never uploaded to the server or synced across devices. Audio files reside in private app storage (`filesDir/sounds/`) or accessed via persistent SAF URI permissions.

---

## 3. Sound Categories & Selection Modes

Each category supports 3 modes:
1. **DEFAULT:** Uses system-provided default notification chime / ringtone or bundled sound asset.
2. **LOCAL:** Uses user-selected local audio file (MP3, WAV, M4A, OGG) from device storage via Android Storage Access Framework (`ACTION_OPEN_DOCUMENT`).
3. **SILENT:** Mutes audio for that event. Vibration and visual notifications remain active per Notification Settings.

### Configurable Categories (12 Total):
1. **Message Sound:** Direct message alerts.
2. **Group Message Sound:** Group chat alerts.
3. **Call Ringtone:** Incoming call ringtone (loops, stops on answer/decline/end, manages audio focus).
4. **Connection Request Sound:** Incoming peer connection request.
5. **Transfer Started Sound:** File transfer initiated.
6. **Transfer Completed Sound:** File transfer finished (only at final state, never per chunk).
7. **Transfer Failed Sound:** File transfer failed or aborted due to threat.
8. **Nearby Device Detected:** Local peer discovered (deduplicated with 30s cooldown).
9. **Mesh Message Received:** Offline DTN message received.
10. **Mesh Transfer Completed:** Offline file transfer completed.
11. **New Status Sound:** Peer posted 24h status story (ignores current user's own status).
12. **Status Interaction Sound:** Peer reacted to your status.

---

## 4. Android Notification Channel Migration Strategy

On Android 8.0+ (API 26+), notification channel sounds cannot be changed once created.
To ensure user-selected sounds apply to system notifications:
1. `NexaNotificationChannelManager` maintains versioned channel IDs tagged by sound configuration hash (e.g. `channel_messages_direct_v{soundHash}`).
2. When the user updates a sound in Settings, the old channel is deleted and the new channel is created with the updated sound URI and audio attributes.
3. For in-app events, `NexaSoundManager` plays the sound directly with audio focus.

---

## 5. Implementation Roadmap

1. **Phase 1: Persistence & Configuration Data Models**
   - Create `SoundCategory`, `SoundMode`, `SoundConfig`.
   - Create `SoundPreferences` (persists settings independently in SharedPreferences).
2. **Phase 2: Audio Validation & Sound Manager Subsystem**
   - Create `AudioFileValidator` (validates MP3, WAV, M4A, OGG; enforces size limits; verifies decodability; persists SAF URI).
   - Create `NexaSoundManager` (centralized audio player, preview controller, call ringtone loop, event sound player, audio focus manager).
3. **Phase 3: Settings UI & Navigation**
   - Add `SOUND` to `SettingsDestination` in `SettingsScreen.kt`.
   - Add `Screen.SoundSettings` to `AuthNavGraph.kt`.
   - Create `SoundSettingsScreen.kt` with expandable/separated categories, preview controls, and SAF picker.
4. **Phase 4: Event Pipeline Integration**
   - Wire `NexaSoundManager` into message ingest, file transfers, nearby mesh, and status events.
5. **Phase 5: Automated Testing & Verification**
   - Create `NexaSoundSettingsTests.kt`.
   - Run full Android test suite, Go backend suite, and APK assembly.
