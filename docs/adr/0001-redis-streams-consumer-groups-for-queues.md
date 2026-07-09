# ADR-0001 — Redis Streams + Consumer Groups as the queue backbone

- Status: Accepted (reconstructed — verify)
- Date: (inferred) early in project history — see commits `c21fcfe`, `23d5d63`

## Context

The project must demonstrate durable, replayable, at-least-once messaging with retry and DLQ
semantics — capabilities a fire-and-forget channel cannot provide.

## Decision

Use **Redis Streams** with **Consumer Groups** as the default substrate for queue-like patterns
(DLQ, Work Queue, Fan-Out, Content-Based Routing, Per-Key Serialized, Token Bucket). Pub/Sub is
reserved for explicitly fire-and-forget patterns.

## Consequences

- Get PENDING tracking, `XACK`, `XCLAIM`/`XAUTOCLAIM`, and delivery counts for free.
- Enables the DLQ pattern (route messages exceeding `maxDeliveries`).
- Requires **Redis 8.4+** for the `XREADGROUP ... CLAIM` option used by `read_claim_or_dlq` (ADR-0004);
  the XNACK explicit-failure demo raises the project baseline to **Redis 8.8+** (ADR-0011).
- Fan-Out diverges by using **one consumer group per worker** so every worker sees every message.
