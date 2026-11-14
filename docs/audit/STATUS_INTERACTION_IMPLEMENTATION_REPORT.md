# Status Interaction Implementation Report

## Result

Status bubbles support long-press actions to mute or unmute updates, open the contact profile, or navigate to the chat. Muted contacts are rendered in a separate **Muted Updates** row, rather than mixed into the regular status row. Muting status updates is distinct from muting chat notifications.

The status ViewModel exposes regular and muted status groups from the repository. The active viewer observes the selected user's status flow and closes or adjusts its selection when the available statuses change.

## Availability and ordering

- The repository groups statuses by stable user ID and excludes the current user's updates from the other-user groups.
- Active status availability is based on creation and expiration timestamps.
- The visible status list is ordered by unread state, most-recent status, then stable user ID.
- Status visibility is checked centrally before status groups or per-user viewer contents are exposed.

## Related implementation

- `app/src/main/java/com/nexa/app/presentation/dashboard/components/NexaStoriesRow.kt`
- `app/src/main/java/com/nexa/app/presentation/status/StatusViewModel.kt`
- `app/src/main/java/com/nexa/app/data/repository/StatusRepositoryImpl.kt`
- `app/src/main/java/com/nexa/app/domain/repository/StatusRepository.kt`

Status availability, privacy, expiry, and persistence details are in [CHAT_STATUS_AVAILABILITY_AND_PERSISTENCE_REPORT.md](./CHAT_STATUS_AVAILABILITY_AND_PERSISTENCE_REPORT.md).
