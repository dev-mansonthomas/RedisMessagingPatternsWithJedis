# Spec — Fan-Out (Broadcast, QoS 1)

Route `/fan-out` · `FanOutController` (`/api/fan-out`) · `FanOutService` · Lua `read_claim_or_dlq`.

## Goal
Broadcast: **every** worker receives **every** message (vs Work Queue where one worker wins).
Achieved by giving each worker its **own consumer group** on the same stream.

## Redis
- Stream `fanout.events.v1`, DLQ `fanout.events.v1:dlq`.
- Groups `fanout-group-1..4` (one per worker — the crux of the pattern).
- Per-worker done streams `fanout.done.worker-{1..4}`.

## REST
- `POST /produce` — `XADD` one event (delivered to all 4 groups).
- `GET /streams` · `DELETE /clear` (recreate groups).

## Flow
4 Virtual Thread workers, each bound to its own group, poll every 100ms via `read_claim_or_dlq`
(maxDeliver 2). Each independently processes (≈100ms) and ACKs within its group; failures → DLQ.

## Edge cases / acceptance
- One produced event shows up processed by all 4 workers.
- A failing worker's retries/DLQ don't affect the others' delivery.

## Inferred — verify
QoS-1 framing (at-least-once per group) labeling in the UI.
