# Spec — Dead Letter Queue (DLQ)

Route `/dlq` · `DLQController` (`/api/dlq`) · `DLQMessagingService` · Lua `read_claim_or_dlq`.

## Goal
Demonstrate at-least-once delivery with bounded retries: messages that fail processing
`maxDeliveries` times are moved to a DLQ stream instead of looping forever.

## Redis
- Stream `test-stream`, DLQ `test-stream:dlq`, group `test-group`/`mygroup`, consumer `consumer-1`/`worker`.
- Runtime config in `DLQConfigService` (in-memory): `maxDeliveries` (default 2), `minIdleMs` (default 100), `count` (default 100).

## REST (selected)
- `POST /produce` — `XADD` a message.
- `POST /process` (`/claim`) — `FCALL read_claim_or_dlq stream dlq group consumer minIdle count maxDeliver`.
- `POST /ack` — `XACK`; broadcasts `MESSAGE_DELETED`.
- `GET /stream/{name}` — `XREVRANGE` (display). `GET /stats` — `XLEN`/`XINFO`/`XPENDING`.
- `POST /init` — create consumer group (`MKSTREAM`).

## Flow
`XPENDING` finds messages whose delivery count > `maxDeliveries` → `XCLAIM`+`XADD` to DLQ +`XACK` →
then `XREADGROUP ... CLAIM minIdle` reads claimable + new. Returns `[toProcess[], dlqIds[]]`.

## Edge cases / acceptance
- A message ACK'd disappears from the live view (`MESSAGE_DELETED`).
- After `maxDeliveries` failures the message appears in `test-stream:dlq` and leaves the main stream.
- Re-claim only after `minIdleMs` has elapsed.

## Inferred — verify
Default consumer naming and whether `mystream`/`test-stream` are both reachable from the UI.
