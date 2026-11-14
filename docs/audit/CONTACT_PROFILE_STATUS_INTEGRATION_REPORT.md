# Contact Profile Status Integration Report

## Result

The contact profile now exposes **See Status** when the selected contact currently has an authorized active status. The profile ViewModel observes status availability for its stable participant NX-ID, and the screen launches the existing sequential status viewer rather than introducing a separate viewing flow.

The existing profile and navigation callbacks are reused. The dashboard and status surfaces can navigate to the contact profile through the established app navigation graph.

## Availability safeguards

- Status availability comes from the shared status repository, not a separate profile-only query.
- Expired, future, blocked, or privacy-ineligible statuses are not presented as available.
- The profile refreshes availability when it becomes active again.
- If availability disappears while the selected status group is observed, the viewer closes or updates its current index.

## Related implementation

- `app/src/main/java/com/nexa/app/presentation/profile/ContactProfileUiState.kt`
- `app/src/main/java/com/nexa/app/presentation/profile/ContactProfileViewModel.kt`
- `app/src/main/java/com/nexa/app/presentation/profile/ContactProfileScreen.kt`
- `app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt`
- `app/src/main/java/com/nexa/app/presentation/status/StatusViewModel.kt`

The central authorization rules and known privacy boundary are documented in [CHAT_STATUS_AVAILABILITY_AND_PERSISTENCE_REPORT.md](./CHAT_STATUS_AVAILABILITY_AND_PERSISTENCE_REPORT.md).
