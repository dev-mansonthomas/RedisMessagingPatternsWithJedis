# Spec — Scheduled / Delayed Messages

Route `/scheduled-messages` · `ScheduledMessagesController` (`/api/scheduled-messages`) ·
`ScheduledMessagesService`.

## Goal
Deliver a message at a future time (delayed execution) using a Sorted Set as a time-ordered queue.

## Redis
- Sorted Set `scheduled.messages` (score = epoch ms due time, member = `message:{id}`).
- Payload Hash `scheduled:message:{id}` (`id`, `title`, `description`, `scheduledFor`, `createdAt`).
- Output stream `reminders.v1` (executed messages with `executedAt`, `scheduledFor`).

## REST
- `GET /` list · `POST /` schedule · `PUT /{id}` update · `DELETE /{id}` · `DELETE /clear` · `GET /streams`.

## Flow
1 Virtual Thread scheduler polls every 500ms: `ZRANGEBYSCORE scheduled.messages -inf now` (batch 10);
for each due item `HGETALL` payload → `XADD reminders.v1` with timestamps → `ZREM` → `DEL` hash.
Frontend polls the API for updates (no push for this page).

## Acceptance
- A message scheduled for T appears in `reminders.v1` at ~T (±poll interval), and leaves the Sorted Set.
- Updating/deleting a pending message changes/removes its score before firing.

## Inferred — verify
No-push (poll-only) on this page; confirm against the component.
