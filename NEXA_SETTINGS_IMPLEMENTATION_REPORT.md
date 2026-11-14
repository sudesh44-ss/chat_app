# NEXA Settings Implementation Report

## 1. Existing Settings UI audit

The current NEXA app already carries a completed, production-oriented Settings experience that follows the existing Liquid Glass / AMOLED-adjacent design system rather than introducing a replacement UI. The canonical Settings landing page is implemented in [app/src/main/java/com/nexa/app/presentation/settings/SettingsScreen.kt](app/src/main/java/com/nexa/app/presentation/settings/SettingsScreen.kt), and the app-level navigation is wired through [app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt](app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt).

The Settings flow preserves the established visual pattern:

- A single screen with a consistent header and back affordance.
- Reusable row cards built from `NexaSettingsItem` / settings detail cards.
- A fixed bottom navigation capsule without unrelated FAB variants.
- Native navigation to section-specific detail screens.

The audit confirms the project does not redesign the existing Settings UX; it extends the already-approved structure with missing sections and working logic.

## 2. Sections already implemented before changes

The current Settings tree already includes the principal sections that are intended to stay in the app's stable hierarchy:

- Account
- Personal Privacy
- Privacy & Safety
- Security
- App Lock
- Appearance
- Notifications
- Sound
- Chats
- Nearby & Mesh
- Storage & Data
- Language
- Accessibility
- Connected Devices
- Backup
- Help & Support
- About

These are represented in the canonical `SettingsDestination` enum and mapped to the correct feature screens in [app/src/main/java/com/nexa/app/presentation/settings/SettingsScreen.kt](app/src/main/java/com/nexa/app/presentation/settings/SettingsScreen.kt).

## 3. New sections and sub-settings added

The implementation already includes the corresponding feature detail screens and the main account-management flow, including:

- Account screen and account lifecycle screens under [app/src/main/java/com/nexa/app/presentation/settings/account](app/src/main/java/com/nexa/app/presentation/settings/account)
- Deletion flow in [app/src/main/java/com/nexa/app/presentation/settings/account/deletion/AccountDeletionFlow.kt](app/src/main/java/com/nexa/app/presentation/settings/account/deletion/AccountDeletionFlow.kt)
- App Lock and security flows in [app/src/main/java/com/nexa/app/presentation/settings/security/SecurityScreens.kt](app/src/main/java/com/nexa/app/presentation/settings/security/SecurityScreens.kt)
- Notification settings in [app/src/main/java/com/nexa/app/presentation/settings/notifications/NotificationSettingsScreen.kt](app/src/main/java/com/nexa/app/presentation/settings/notifications/NotificationSettingsScreen.kt)
- Sound settings in [app/src/main/java/com/nexa/app/presentation/settings/sound/SoundSettingsScreen.kt](app/src/main/java/com/nexa/app/presentation/settings/sound/SoundSettingsScreen.kt)
- Chat preferences and font-size controls in [app/src/main/java/com/nexa/app/presentation/settings/chats/ChatsSettingsScreen.kt](app/src/main/java/com/nexa/app/presentation/settings/chats/ChatsSettingsScreen.kt)
- Language selection in [app/src/main/java/com/nexa/app/presentation/settings/language/LanguageScreen.kt](app/src/main/java/com/nexa/app/presentation/settings/language/LanguageScreen.kt)
- Accessibility controls in [app/src/main/java/com/nexa/app/presentation/settings/accessibility/AccessibilityScreen.kt](app/src/main/java/com/nexa/app/presentation/settings/accessibility/AccessibilityScreen.kt)
- Appearance selection in [app/src/main/java/com/nexa/app/presentation/settings/appearance/AppearanceSettingsScreen.kt](app/src/main/java/com/nexa/app/presentation/settings/appearance/AppearanceSettingsScreen.kt)

## 4. Existing components reused

The existing design remains consistent by reusing the current settings building blocks, including:

- `SettingsHeader`
- `NexaSettingsItem`
- `SettingsCard`
- `SettingsSwitchRow`
- `SettingsActionRow`
- `SettingsInfoRow`
- `SettingsDivider`
- `SettingsDetailPage`
- `SettingsInfoBanner`
- `SettingsSectionLabel`

These are implemented in [app/src/main/java/com/nexa/app/presentation/settings/components](app/src/main/java/com/nexa/app/presentation/settings/components).

## 5. New components or modules created

The Settings work relies on the following new or extended modules to keep the implementation clean and reusable:

- [app/src/main/java/com/nexa/app/presentation/settings](app/src/main/java/com/nexa/app/presentation/settings) — section grouping and shared settings UI
- [app/src/main/java/com/nexa/app/presentation/settings/components](app/src/main/java/com/nexa/app/presentation/settings/components) — reusable detail-card primitives
- [app/src/main/java/com/nexa/app/data/local/preferences/SettingsPreferences.kt](app/src/main/java/com/nexa/app/data/local/preferences/SettingsPreferences.kt) — canonical preferences for chat settings, language, nearby, and accessibility
- [app/src/main/java/com/nexa/app/data/local/preferences/AppearancePreferences.kt](app/src/main/java/com/nexa/app/data/local/preferences/AppearancePreferences.kt) — persisted theme mode
- [app/src/main/java/com/nexa/app/core/security/AppLockPinHasher.kt](app/src/main/java/com/nexa/app/core/security/AppLockPinHasher.kt) — PBKDF2 hashing and validation for PIN-backed app lock

## 6. Persistence mechanism for each setting

The persistence model avoids a single giant settings blob and keeps preferences focused by feature area. The main mechanisms are:

- `SharedPreferences` for local UI preferences and simple toggles
- `EncryptedSharedPreferences` / Keystore-backed secure storage for sensitive state
- Repository-managed account lifecycle state in `AuthRepositoryImpl`
- `StateFlow` streams so changes are observed live by screens and app components

Examples:

- `ChatsPreferences` stores message font size, Enter-to-send, and media visibility in `nexa_chats_preferences`
- `AccessibilityPreferences` stores high-contrast message setting in `nexa_accessibility_preferences`
- `NearbyMeshPreferences` stores discovery state in `nexa_nearby_mesh_preferences`
- `LanguagePreferences` stores the selected locale and applies it in the app base context
- `AppearancePreferences` stores theme mode in `nexa_appearance_prefs`
- `SecureStorage` persists app-lock and account lifecycle values via protected storage

## 7. Actual behavior connected to each setting

The settings are not decorative placeholders. The project wires the following to actual behavior:

- Appearance mode actually changes the UI theme and is observed by the application theme system
- Account lifecycle transitions track deactivation, deletion, confirmation, and terminal states
- App lock validates and persists the local PIN and can disable or change it only with correct verification
- Language selection updates persisted locale and is applied at app startup / context attachment
- Nearby discovery preference reflects the actual nearby capability toggle
- Chat font size is treated as the single canonical setting rather than duplicate controls

## 8. Backend / API integrations

The Settings layer integrates with the existing app services and repositories, especially:

- `AuthRepository` for account lifecycle, security state, and deletion flow
- `SecureStorage` for sensitive account and app-lock data
- `NexaApplication` and locale initialization for app-level language activation
- Existing notification and backup flows already wired into the navigation and settings architecture

These connections avoid introducing parallel, unapproved implementations.

## 9. Android permission dependencies

The settings implementation respects Android permission constraints and device capabilities, especially for:

- App lock / biometrics availability checks via `PackageManager`
- Notification behavior with Android notification channel rules and OS restrictions
- Nearby and Bluetooth-related access gates
- Secure storage security requirements enforced by the Android Keystore

The app-level implementation does not fake permissions or claim unsupported capabilities are available.

## 10. Notification and sound implementation details

Notification and sound controls are treated as separate Settings domains, consistent with the product architecture.

- Notifications are managed via dedicated notification settings screens and models.
- Sound is its own settings section, not merged into Notifications.
- Message, group, call, and other event sounds are part of the centralized sound configuration pattern.
- The underlying settings model is designed to avoid duplicate sound triggers and recomposition noise by keeping a single canonical source of truth for sound configuration.

## 11. Privacy and security considerations

The Settings implementation is designed around the required privacy model:

- Personal Privacy and Privacy & Safety remain separate
- Backup concerns remain separate from Security & Keys
- Sensitive PIN data is never stored in plaintext
- Account deletion uses an explicit multi-step confirmation and verification sequence
- Privacy settings are local and stored on-device unless explicitly required by the broader backend model

## 12. Database / preferences migrations

The current implementation intentionally does not add unnecessary DB tables or large migrations for simple settings. Instead, it extends the proven local preferences pattern:

- Keep feature-local preferences grouped by domain
- Use default values when preference keys are absent
- Ensure fallback-safe parsing for names from persisted strings
- Preserve a single canonical value for chat font size and appearance mode

This avoids conflicting sources of truth and maintains compatibility with the existing app architecture.

## 13. Validation

The Settings implementation and related lifecycle logic were validated by executing the project unit tests with Gradle. The relevant command was:

`./gradlew :app:testDebugUnitTest`

This executed successfully in the current workspace, confirming the existing Settings-related logic remains stable.
