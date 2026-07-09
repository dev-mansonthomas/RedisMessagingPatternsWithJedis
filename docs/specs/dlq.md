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
- `POST /process` — body `{"outcome": "ACK"|"NO_ACK"|"NACK_FAIL"|"NACK_FATAL"|"NACK_SILENT"}`
  (legacy `{"shouldSucceed": bool}` maps to ACK/NO_ACK; invalid outcome → 400). Reads the next
  message via `FCALL read_claim_or_dlq`, then applies the outcome. NACK_* broadcasts
  `MESSAGE_NACKED`.
- `POST /claim` — raw `FCALL read_claim_or_dlq stream dlq group consumer minIdle count maxDeliver`.
- `POST /ack` — `XACK`; broadcasts `MESSAGE_DELETED`.
- `GET /stream/{name}` — `XREVRANGE` (display). `GET /stats` — `XLEN`/`XINFO`/`XPENDING`.
- `GET /pending-messages` — PEL entries with `deliveryCount`, `consumer` (empty = released),
  `idleMs` (-1 = released).
- `POST /init` — create consumer group (`MKSTREAM`).

## Explicit failure — XNACK (Redis 8.8+, ADR-0011)

| Outcome | XNACK mode | Counter | Re-claimable | Story |
|---|---|---|---|---|
| `NO_ACK` | — (no XNACK) | consumed | after `minIdleMs` | crash / silent failure |
| `NACK_FAIL` | `FAIL` | consumed (kept) | **immediately** | "I tried and failed" |
| `NACK_FATAL` | `FATAL` | → `Long.MAX` | swept to DLQ **next poll, no wait** | poison message |
| `NACK_SILENT` | `SILENT` | **refunded** (→ 0) | immediately | "I didn't try" (shutdown) |

Released entries: in PEL, unowned (`consumer` empty, `idle = -1`). UI renders a `released` badge
and `∞ poison` when `deliveryCount >= Number.MAX_SAFE_INTEGER` (JSON rounds `Long.MAX` — threshold
compare only). Backend calls XNACK via raw `Jedis.sendCommand` (no typed API in stable Jedis).

## Flow
`XPENDING` finds messages whose delivery count ≥ `maxDeliveries` → `XCLAIM`+`XADD` to DLQ +`XACK` →
then `XREADGROUP ... CLAIM minIdle` reads claimable + new. Returns `[toProcess[], dlqIds[]]`.
The DLQ check runs **before** the re-read, so it only sees counts from previous calls: a poison
message is delivered `maxDeliveries` times, then swept by the **next** `FCALL` — `maxDeliveries`+1
calls in total, each ≥ `minIdleMs` apart. `XREADGROUP ... CLAIM` **does** increment the delivery
counter (verified empirically on Redis 8.4, 2026-07-09; re-proven on 8.8 by
`DLQXnackIntegrationTest`). Exception: **XNACK-released** messages bypass the `minIdleMs` wait
entirely (see below).

## Edge cases / acceptance
- A message ACK'd disappears from the live view (`MESSAGE_DELETED`).
- After `maxDeliveries` failed deliveries, the **next** poll moves the message to `test-stream:dlq`
  and clears it from the main stream's PENDING list.
- Re-claim only after `minIdleMs` has elapsed.

## Naming (resolved 2026-07-09)
The effective defaults are `DLQConfigService.DEFAULT_CONFIG`: stream `test-stream`, DLQ
`test-stream:dlq`, group `test-group`, consumer `consumer-1`, `minIdleMs=100`, `count=100`,
`maxDeliveries=2`. The `mystream`/`mygroup`/`worker` values in `DLQProperties`/`DLQParameters`
are **dead builder defaults** — the UI path always goes through `DLQConfigService`.
