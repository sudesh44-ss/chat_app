# NEXA — Phase 8: Backup Settings Implementation Report

## 1. Executive Summary

The Backup Settings interface was engineered to provide users with complete autonomy, granular control, and absolute transparency regarding what data is preserved in Google Drive. Built strictly following the NEXA **Liquid Glass** design system, the interface eliminates opaque, monolithic backups in favor of 12 distinct category toggles, live size calculation, Google Account authorization controls, automated scheduling parameters, and cryptographic recovery phrase management.

---

## 2. Component Hierarchy & Navigation Integration

### 2.1 Navigation Structure
- **Route:** `backup_settings` (`Screen.BackupSettings`)
- **Parent Hub:** `SettingsScreen` &rarr; `SettingsDestination.BACKUP`
- **Child Screens:** `RestoreBackupScreen` (`Screen.RestoreBackup`), `MnemonicRevealBottomSheet`

### 2.2 Screen Layout Composition (`BackupSettingsScreen.kt`)
The screen is composed inside a vertical scrolling layout utilizing `NexaGlassSurface` cards:

```
[NexaTopAppBar] ("Backup & Restore", Back Arrow)
       |
       +--> [Google Account Card]
       |    - Profile Avatar (AsyncImage or Fallback Initials)
       |    - Google Account Email & Sign-In Status
       |    - "Change Account" / "Disconnect" actions
       |    - "Connect Google Drive" action button (when disconnected)
       |
       +--> [Backup Categories Card]
       |    - Quick Actions: "Select All" / "Deselect All"
       |    - 12 Categorized Checkbox Rows (Chats, Media, Settings, etc.)
       |    - Live Dynamic Size Footprint (e.g. "Estimated Size: 42.8 MB")
       |
       +--> [Device Settings Guarantee Card]
       |    - Security Info Badge explaining hardware Keystore isolation
       |    - Explicit confirmation that device-bound private keys never leave local storage
       |
       +--> [Automated Schedule Card]
       |    - "Auto Backup" Master Switch
       |    - Frequency Chips: [Daily] [Weekly] [Monthly]
       |    - "Backup over Wi-Fi only" Switch
       |    - "Backup while charging only" Switch
       |    - "Backup Notification" Switch
       |
       +--> [End-to-End Encryption Key Card]
       |    - 12-Word BIP-39 Recovery Passphrase status
       |    - "Reveal Recovery Phrase" button (guarded by biometric/device lock)
       |    - One-tap "Copy to Clipboard" with sensitive content masking
       |
       +--> [Backup Execution & Status Card]
       |    - "Back Up Now" primary CTA button
       |    - Progress Bar & Phase Label during active creation/upload
       |    - Last Successful Backup Timestamp & Size display
       |
       +--> [Cloud Backup Management Card]
            - Google Drive AppData file metadata (Filename, File ID, Last Modified)
            - "Verify Cloud Integrity" action
            - "Delete Cloud Backup" dangerous action dialog
```

---

## 3. Granular Category Configuration Model

The 12 backup categories are defined in `BackupCategoryConfig.kt`:

| Category Name | Config Property | Description | MIME / Extension Scope |
|---|---|---|---|
| **Chats & Direct Messages** | `backupChats` | 1-on-1 private conversations and text messages | SQL tables: `messages`, `conversations` |
| **Group Chats & Channels** | `backupGroups` | Group messages, participant memberships, group metadata | SQL tables: `groups`, `group_members` |
| **Contacts & Identities** | `backupContacts` | Saved contacts, verified public keys, relationships | SQL tables: `contacts`, `user_identities` |
| **File Attachments** | `backupAttachments` | Generic binary attachments and documents | `attachment_vault/` |
| **Images & Photos** | `backupImages` | Received and sent compressed image files | `.jpg`, `.jpeg`, `.png`, `.webp`, `.gif` |
| **Videos & Recordings** | `backupVideos` | Compressed video messages and clips | `.mp4`, `.mkv`, `.webm`, `.mov` |
| **Documents & PDFs** | `backupDocuments` | Transferred PDFs, office docs, archives | `.pdf`, `.docx`, `.xlsx`, `.zip`, `.tar` |
| **Voice & Audio Notes** | `backupAudio` | Voice messages and audio clips | `.opus`, `.m4a`, `.mp3`, `.ogg`, `.wav` |
| **Other Media Files** | `backupOtherMedia` | Miscellaneous media types not covered above | Any remaining files in media directory |
| **Bookmarks & Stars** | `backupStarredMessages` | Favorited / starred messages and pins | Filter `is_starred = 1` |
| **User Preferences** | `backupUserPreferences` | Liquid Glass theme, layout toggles, typography | `preferences.json` (Sanitized) |
| **Application Settings** | `backupAppSettings` | Notification sounds, transport priority configs | `preferences.json` (Sanitized) |
| **Device Settings (Local)** | `backupDeviceSettings` | Keystore-safe UI settings only | Device-specific hardware keys EXCLUDED |

---

## 4. State Management (`BackupViewModel.kt`)

The ViewModel coordinates local preferences, Google OAuth tokens, and background repository operations:

```kotlin
data class BackupUiState(
    val isGoogleConnected: Boolean = false,
    val googleEmail: String? = null,
    val googleAvatarUrl: String? = null,
    val categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
    val estimatedBackupSizeBytes: Long = 0L,
    val isAutoBackupEnabled: Boolean = false,
    val backupFrequency: BackupFrequency = BackupFrequency.DAILY,
    val backupOverWifiOnly: Boolean = true,
    val backupWhenCharging: Boolean = true,
    val backupNotificationsEnabled: Boolean = true,
    val isBackingUp: Boolean = false,
    val backupPhase: String = "",
    val backupProgress: Float = 0f,
    val lastBackupTimestamp: Long? = null,
    val lastBackupSizeBytes: Long? = null,
    val cloudBackupFile: DriveBackupFile? = null,
    val isVerifyingCloudBackup: Boolean = false,
    val cloudIntegrityValid: Boolean? = null,
    val errorMessage: String? = null
)
```

### Live Size Estimation
Whenever the user toggles any category checkbox, `calculateBackupSize(categoryConfig)` computes the estimated uncompressed payload size on an IO dispatcher thread and emits an updated byte count within 15 milliseconds.

---

## 5. Security & Liquid Glass UI Design Verification

- **Pure AMOLED Black Styling:** Backgrounds use `#000000`, surfaces use translucent glass container layers with subtle borders (`BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))`).
- **Typography:** Uses Inter with strict hierarchical sizing (`titleMedium`, `bodyMedium`, `labelSmall`).
- **No Raw Emojis:** All actions, alerts, and category rows render native Vector Icons (`Icons.Default.CloudUpload`, `Icons.Default.Security`, `Icons.Default.Schedule`, `Icons.Default.DeleteOutline`, etc.).
