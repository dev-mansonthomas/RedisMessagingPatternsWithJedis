# ADR-0004 — Redis Functions (Lua) for atomic multi-step operations

- Status: Accepted (reconstructed — verify)
- Date: (inferred) commits `5901111`, `c2366e5`

## Context

Several patterns need multiple Redis commands to execute atomically server-side: claim-or-DLQ
(XPENDING → XCLAIM → XADD → XACK), request/reply with timeout setup, dynamic topic routing, and
the token-bucket acquire check. Doing these client-side risks races and extra round-trips.

## Decision

Register a single Functions library **`stream_utils`** (`lua/stream_utils.lua`) auto-loaded at
startup (`RedisLuaFunctionLoader`, `FUNCTION LOAD REPLACE`). Functions: `read_claim_or_dlq`,
`request`, `response`, `route_message`. Token Bucket uses a small inline `EVAL` acquire script.

## Consequences

- Atomicity and fewer round-trips; logic lives next to the data.
- `read_claim_or_dlq` depends on the Redis **8.4+** `XREADGROUP ... CLAIM` option; the demo
  baseline is **Redis 8.8+** since XNACK (ADR-0011). XNACK itself is deliberately NOT wrapped in
  Lua — a single O(1) command has no atomicity need (ADR-0011).
- Lua is a second language to maintain; changes require reloading the library (handled on restart).
- Verify with `FUNCTION LIST` if behavior looks stale.
