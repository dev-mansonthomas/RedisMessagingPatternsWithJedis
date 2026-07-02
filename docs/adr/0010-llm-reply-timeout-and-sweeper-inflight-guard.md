# ADR-0010 — LLM reply timeout via keyspace notifications + recovery-sweeper in-flight guard

- Status: Accepted
- Date: 2026-07-01
- Relates to: Pattern #12 (`docs/specs/llm-chat.md`), ADR-0007 (keyspace notifications for
  Request/Reply timeout), ADR-0009 (LlmClient abstraction), ADR-0006 (XREVRANGE for display)

## Context

Two problems surfaced while hardening the LLM Chat demo (#12):

1. **Telling the user a message failed.** A message can fail to get a reply (a worker crash that never
   recovers, or a "poison" message that fails every attempt and lands in the DLQ). Polling for "did a
   reply arrive?" is wasteful and the DLQ is an ops-facing view, not a user-facing one.
2. **The recovery sweeper double-produced on slow replies.** `LlmRecoverySweeper` reclaims *stale
   pending* entries via `XAUTOCLAIM` after `min-idle-ms` (3.25s). But a legitimately **slow** generation
   (e.g. the long-text demo, ~6.8s) stays `PENDING` — un-ACKed — well past `min-idle-ms`, so the
   sweeper reclaimed a still-healthy message and generated a **second** reply.

## Decision

1. **Reply timeout with Redis keyspace notifications** (reuse the Request/Reply mechanism, ADR-0007).
   On send, set a key `llm:timeout:{msgId}` with a TTL (`llm.timeout-seconds`, default 8s) and a shadow
   hash `llm:timeout:shadow:{msgId}` = `{cid, content}` that outlives the timeout key. The responder
   **deletes both** when the reply completes. If the timeout key **expires** first, Redis publishes a
   keyspace `expired` event (`notify-keyspace-events Ex`); the shared `KeyspaceNotificationListener`
   reads the shadow and posts a `role=system` "⏱ your message failed" notice into `chat:{cid}`. The
   timeout is chosen larger than a long generation and the recovery window so it fires only on genuine
   failures. No new subscriber — it rides the existing `__keyevent@0__:expired` PSUBSCRIBE.

2. **In-flight guard for the sweeper.** The responder records each entry it is actively generating
   (`cid|entryId`) and clears it in a `finally`. Before regenerating or DLQ-routing a reclaimed entry,
   the sweeper **skips** any that is still in-flight. A killed/poison message aborts immediately, so it
   is *not* in-flight when reclaimed → it is correctly recovered or routed to the DLQ.

## Alternatives considered

- **Client-side timeout only** — rejected: doesn't survive a page reload and misses the "showcase a
  Redis feature" goal.
- **Raise `min-idle-ms` above the worst-case generation time** — rejected: couples the recovery latency
  to reply length and makes the crash-recovery demo sluggish.
- **Refresh the pending entry's idle (periodic `XCLAIM` to self) during generation** — rejected as
  hacky vs. a simple in-memory in-flight set (the single-JVM demo can track this; a real multi-node
  deployment would rely on `min-idle-ms` tuning or heartbeats).

## Consequences

- Users get a prompt, push-based failure notice; the DLQ remains the ops view. Both appear for the
  same poison message in quick succession: `min-idle-ms` (3.25s) with a 500ms `sweep-interval-ms`
  paces the two reclaim cycles to ~3.5s and ~7.0s, so the DLQ lands at ~7s (three deliveries), and
  `timeout-seconds` (8s) is set just above it so the timeout notice lands ~1s later — both comfortably
  above the worst-case legitimate generation (the ~6.8s long-text demo), so a healthy reply never
  trips a false timeout.
- The in-flight set is in-memory, so it only guards a *live* JVM — exactly the intent (a truly dead JVM
  loses the set and its messages are reclaimed, which is the recovery path).
- Two more keys per message (`llm:timeout:*`), both short-lived / shadow-TTL'd. Deleted on success.
