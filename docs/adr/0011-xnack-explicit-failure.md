# ADR-0011 — Adopt XNACK (Redis 8.8) for explicit failure semantics on the DLQ demo

- Status: Accepted
- Date: 2026-07-09

## Context

Until Redis 8.8, a consumer had no way to *explicitly* reject a message: the only failure path was
implicit — don't ACK, wait for `minIdleMs` to elapse, get re-claimed, and consume one unit of the
failure budget (the PEL delivery counter checked against `maxDeliveries`). That conflates three
very different situations: "I tried and failed", "this message is poison", and "I didn't even try
(graceful shutdown / backpressure)". Redis 8.8 introduces
`XNACK key group <SILENT|FAIL|FATAL> IDS n id... [RETRYCOUNT n] [FORCE]`.

## Decision

Raise the demo baseline to **Redis 8.8+** and expose the three modes on the `/dlq` page
(`POST /api/dlq/process {"outcome": ...}`, buttons *Explicit Fail* / *Poison* / *Release silent*).

Call XNACK as a **direct Jedis raw command** (`Jedis.sendCommand` with a local `ProtocolCommand`),
not through a Lua wrapper and not via a typed client API:

- No Lua: ADR-0004 reserves Functions for atomic multi-step sequences; XNACK is one O(1) command.
- No typed API: no **stable** Jedis has `xnack()` (only 8.0.0-beta1, which also lacks
  `RETRYCOUNT`/`FORCE`). Revisit when Jedis 8 goes GA. Jedis was bumped 7.1.0 → 7.5.3 (latest
  stable) in the same change.

`read_claim_or_dlq` is **unchanged**: XNACK-released messages are picked up by its existing
`XPENDING`/`XREADGROUP ... CLAIM` path.

## Semantics (verified empirically, 2026-07-09, redis:8.8-alpine)

| Mode | Delivery counter | Effect |
|---|---|---|
| `SILENT` | reset to **0** | returned untouched — failure budget refunded |
| `FAIL` | **kept** | explicit failure — budget consumed |
| `FATAL` | forced to **Long.MAX** | poison — swept to the DLQ by the next poll |

- Released entries stay in the PEL but **unowned**: `consumer` empty, `idle = -1`, and are
  **immediately** re-claimable (both `XPENDING IDLE` and `XREADGROUP ... CLAIM` treat them as
  eligible regardless of `minIdle`).
- `XREADGROUP ... >` does **not** re-deliver released messages — only the claim path sees them.
- XNACK on a non-pending/ACKed id → `0` (no-op) unless `FORCE` (which *creates* an unowned PEL
  entry — not used here). No ownership check: any client may release any pending id. Reply =
  count actually released. Idempotent on already-released entries.

## Consequences

- The demo distinguishes implicit failure (crash → `minIdleMs` latency) from explicit failure
  (immediate), matching how modern brokers express NACK.
- `Long.MAX` delivery counts flow through JSON and **lose precision in JS** — the UI detects
  poison with `>= Number.MAX_SAFE_INTEGER`, never equality, and renders "∞ poison".
- `RETRYCOUNT` and `FORCE` are documented but not exposed in the UI (no demo story yet).
- First backend tests for the DLQ pattern: `DLQXnackIntegrationTest`, `DLQProcessControllerTest`.
