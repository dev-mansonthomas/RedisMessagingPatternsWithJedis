# Spec — Dead Letter Queue (DLQ)

Route `/dlq` · `DLQController` (`/api/dlq`) · `DLQMessagingService` · Lua `read_claim_or_dlq`.

## Goal
Demonstrate at-least-once delivery with bounded retries: messages that fail processing
`maxDeliveries` times are moved to a DLQ stream instead of looping forever. The move is a
**sweep performed by the next poll** (see Flow), not an immediate reaction to the last failure.

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
`XPENDING` finds messages whose delivery count ≥ `maxDeliveries` → `XCLAIM`+`XADD` to DLQ +`XACK` →
then `XREADGROUP ... CLAIM minIdle` reads claimable + new. Returns `[toProcess[], dlqIds[]]`.
The DLQ check runs **before** the re-read, so it only sees counts from previous calls: a poison
message is delivered `maxDeliveries` times, then swept by the **next** `FCALL` — `maxDeliveries`+1
calls in total, each ≥ `minIdleMs` apart. `XREADGROUP ... CLAIM` **does** increment the delivery
counter (verified empirically on Redis 8.4, 2026-07-09).

## Edge cases / acceptance
- A message ACK'd disappears from the live view (`MESSAGE_DELETED`).
- After `maxDeliveries` failed deliveries, the **next** poll moves the message to `test-stream:dlq`
  and clears it from the main stream's PENDING list.
- Re-claim only after `minIdleMs` has elapsed.

## Inferred — verify
Default consumer naming and whether `mystream`/`test-stream` are both reachable from the UI.
