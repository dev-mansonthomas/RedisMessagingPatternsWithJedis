# ADR-0003 — Virtual Threads + `XREAD BLOCK` for the live stream view

- Status: Accepted (reconstructed — verify)
- Date: (inferred) commit `7890e90` "Add a virtual thread to monitor new messages on a stream"

## Context

The UI must reflect Redis stream activity in near-real-time, including messages produced by
external tools (RedisInsight, `redis-cli`) — not just those produced through the API. Polling is
laggy; a blocking read per stream would waste platform threads.

## Decision

Run **one Java 21 Virtual Thread per monitored stream**, each looping on `XREAD BLOCK 1000`
(`RedisStreamListenerService`). On new entries, broadcast `MESSAGE_PRODUCED` over WebSocket.
Worker pools (Work Queue, Fan-Out, Token Bucket, etc.) are likewise Virtual Threads.

## Consequences

- Push-like latency (~≤1s) with trivial thread cost; thousands of blocked threads are cheap.
- Detects all producers, not only API calls.
- Each blocked `XREAD` holds a pooled Jedis connection for up to the block timeout — pool must be
  sized for the number of monitored streams + workers.
- Graceful shutdown must interrupt the loops.
