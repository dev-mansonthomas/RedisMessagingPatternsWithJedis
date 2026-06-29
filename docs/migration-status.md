# Migration / Implementation Status

> Snapshot as of 2026-06-26. Tracks what exists vs. the documented roadmap.
> Pattern status is verified from code; roadmap rows come from `augmentcode/feature-comparison.md`.

## Patterns implemented (11)

| Pattern | Status | Substrate |
|---------|--------|-----------|
| Dead Letter Queue | ✅ done | Streams + groups + Lua |
| Publish/Subscribe | ✅ done | Pub/Sub |
| Request/Reply | ✅ done | Streams + keyspace expiry |
| Work Queue | ✅ done | Streams + 1 group / N workers |
| Fan-Out | ✅ done | Streams + N groups |
| Topic Routing (stream) | ✅ done | Lua `route_message` + rule hashes |
| Topic Routing (Pub/Sub) | ✅ done | `PSUBSCRIBE` |
| Content-Based Routing | ✅ done | Streams + thresholds |
| Scheduled Messages | ✅ done | Sorted Set + Hash + Stream |
| Per-Key Serialized | ✅ done | Stream + `SET NX` locks |
| Token Bucket | ✅ done | Stream + Lua counter |

## In-flight (uncommitted working tree, 2026-06-26)

- **Mermaid per-pattern diagrams**: new `mermaid-diagram` component + `diagram-definitions.service.ts`
  + `docs/diagrams/*.md`, wired into most pattern components/templates. Not yet committed.
- Dockerization adjustments (`launch-docker.sh`, Dockerfiles) — partly committed (`8b087ef`), partly modified.
- `TokenBucketService.java` minor change.

## DLQ feature roadmap (from RabbitMQ gap analysis)

Source: `augmentcode/feature-comparison.md`. None of the below are implemented yet:

| Feature | Status | Difficulty |
|---------|--------|-----------|
| DLQ reason metadata (`_dlq_reason`) | ❌ not started | Low (quick win) |
| Message transform metadata before DLQ | ❌ not started | Low |
| Native `MAXLEN` on streams | ❌ not started | Low |
| Explicit NACK (`nack_to_dlq` Lua) | ❌ not started | Low |
| Message TTL | ❌ not started | Medium |
| Exponential backoff retry | ❌ not started | Medium |
| Dynamic DLX (per-stream config) | ❌ not started | Medium |
| Priority queues | ❌ not started | High |
| DLQ query/filtering (Redis Query Engine) | ❌ not started | Medium |

## Known doc drift (addressed in this pass)

- `README.md` "Implemented Patterns" listed only 3–4 patterns → now 11 (see doc-sync).
- `augmentcode/CONTEXT.md` / `IMPLEMENTATION_REFERENCE.md` describe only the original 4 patterns and
  use legacy stream names (`test-stream`); superseded by `docs/` but kept for history.

## Engineering gaps

See `docs/TODO.md` — no automated tests, 76 lint errors, demo-grade security (ADR-0008),
Java/Maven absent from this VM (Docker build path only).
