# Architecture Overview

> How the pieces fit together. Reconstructed from code; **(inferred — verify)** marks deductions.

## High-level

```
┌─────────────────────────── Angular 21 SPA (port 4200) ───────────────────────────┐
│  11 lazy-loaded standalone components (one per pattern)                            │
│  services: redis-api (REST), websocket (SockJS), stream-refresh, routing-rules,   │
│            diagram-definitions (mermaid)                                           │
└───────────────┬──────────────────────────────────────────┬───────────────────────┘
        REST /api/*                                  WebSocket /api/ws/dlq-events
                │                                              │ (DLQEvent / PubSubEvent)
┌───────────────▼──────────────────────────────────────────────▼────────────────────┐
│                       Spring Boot 3.5.7 backend (port 8080, context-path /api)      │
│  controllers ── services ── JedisPool ──┐        WebSocketEventService (broadcast)  │
│  RedisLuaFunctionLoader (@PostConstruct loads lua/stream_utils.lua)                 │
│  RedisStreamListenerService (Virtual Thread per stream, XREAD BLOCK 1000)           │
│  KeyspaceNotificationListener (Virtual Thread, __keyevent@0__:expired)              │
│  RedisPubSubListener (daemon thread, SUBSCRIBE / PSUBSCRIBE)                         │
└───────────────────────────────────────┬────────────────────────────────────────────┘
                                         │ Jedis (RESP)
┌────────────────────────────────────────▼───────────────────────────────────────────┐
│  Redis 8.4-alpine (AOF on)                                                           │
│  Streams + Consumer Groups · Pub/Sub channels · Sorted Sets · Hashes · String locks  │
│  Functions library `stream_utils`: read_claim_or_dlq · request · response · route_message  │
│                                    · acquire_token · release_token · release_lock          │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

Containers (`docker-compose.yml`): `redis`, `backend`, `frontend` (nginx), `redis-insight`.

## Backend building blocks

- **`config/RedisConfig` + `RedisProperties`** — builds the `JedisPool` (pool sizes/timeouts from
  `application.yml`, overridable by `REDIS_*` env vars). All services borrow/return connections.
- **`RedisLuaFunctionLoader`** — on startup loads `lua/stream_utils.lua` (classpath-first so it works
  inside a packaged jar, with a filesystem fallback for local dev) via `FUNCTION LOAD REPLACE`, then
  verifies **each** of the 7 expected functions is registered via `FUNCTION LIST` (fail-fast).
- **`RedisStreamListenerService`** — the live-view engine. One **Virtual Thread per monitored
  stream** runs `XREAD BLOCK 1000`; on new entries it broadcasts `MESSAGE_PRODUCED`. Detects messages
  regardless of source (API, worker, or RedisInsight). `StreamMonitorService` is the deprecated
  `XREADGROUP`-based predecessor (Spring profile `disabled`).
- **`WebSocketEventService`** — thread-safe fan-out to all sessions (`CopyOnWriteArraySet`, per-session
  sync). Broadcasts `DLQEvent`, `PubSubEvent` and `LlmChatEvent` (the last filtered per subscribed cid).
- **`KeyspaceNotificationConfig` + `KeyspaceNotificationListener`** — enables `notify-keyspace-events Ex`
  and subscribes to `__keyevent@0__:expired` to drive **timeouts** for both Request/Reply and the
  LLM Chat reply timeout (`llm:timeout:*`, pattern #12 / ADR-0010).
- **`RedisPubSubConfig` + `RedisPubSubListener`** — blocking `SUBSCRIBE`/`PSUBSCRIBE` for the two
  pub/sub patterns, each on a **dedicated connection** (built from `RedisProperties`, not borrowed
  from the shared pool) inside a **reconnect loop** with backoff, stopped cleanly via `@PreDestroy`.
- **`config/WebSocketConfig` + `websocket/DLQEventWebSocketHandler`** — registers `/ws/dlq-events`
  (→ `/api/ws/dlq-events`) with SockJS fallback and an explicit origin **allow-list**
  (`app.cors.allowed-origins`, same source as CORS).
- **`config/CorsConfig`** — explicit origin allow-list (default local frontend/backend, overridable
  via `app.cors.allowed-origins` / `APP_CORS_ALLOWED_ORIGINS`); all methods/headers, credentials on.

## Thread model

| Component | Threads | Mechanism |
|-----------|---------|-----------|
| Stream live-view | 1 Virtual Thread / monitored stream | `XREAD BLOCK 1000` |
| Work Queue | 4 Virtual Thread workers | poll 100ms, `FCALL read_claim_or_dlq` |
| Fan-Out | 4 Virtual Thread workers (1 group each) | poll 100ms, `FCALL read_claim_or_dlq` |
| Content-Based Routing | 1 Virtual Thread router | poll 500ms, `FCALL read_claim_or_dlq` |
| Per-Key Serialized | 3 Virtual Thread workers | poll 500ms, `SET NX` lock + `XAUTOCLAIM` (idle 10s); owner-checked `FCALL release_lock` |
| Token Bucket | 18 Virtual Thread workers (6/type) | poll 10ms, `FCALL acquire_token` / `release_token` |
| Scheduled Messages | 1 Virtual Thread scheduler | poll 500ms, `ZRANGEBYSCORE` |
| Keyspace timeouts | 1 Virtual Thread | `psubscribe __keyevent@0__:expired` |
| Pub/Sub subscribers | daemon threads / cached pool | `SUBSCRIBE` / `PSUBSCRIBE` |

Jedis is borrowed per-operation from the pool; blocking listeners hold a dedicated connection.

## Redis Functions (`lua/stream_utils.lua`)

| Function | KEYS | ARGS | Purpose |
|----------|------|------|---------|
| `read_claim_or_dlq` | `[stream, dlq]` | `[group, consumer, minIdle, count, maxDeliver]` | `XPENDING` → route over-delivered msgs to DLQ (`XCLAIM`+`XADD`+`XACK`), then `XREADGROUP ... CLAIM` (Redis 8.4+) to claim idle + read new. Returns `[toProcess[], dlqIds[]]`. |
| `request` | `[timeout_key, shadow_key, stream]` | `[corrId, businessId, respStream, timeoutSec, payload]` | Sets expiring timeout key + shadow metadata, `XADD`s request. Returns msg id. |
| `response` | `[timeout_key, stream]` | `[corrId, businessId, payload]` | `DEL`s timeout key (cancel timeout), `XADD`s response. Returns msg id. |
| `route_message` | `[exchange_stream]` | `[routingKey, payload]` | Loads rules from `routing:rules:{stream}`, sorts by priority, Lua-pattern matches (each match wrapped in `pcall` so one malformed rule can't abort the function), fans out via `XADD` to matched target streams (stop-on-match supported). Returns `[exchangeId, routedTo[], rulesEvaluated, rulesMatched]`. |
| `acquire_token` | `[runningKey]` | `[maxConcurrency]` | Token Bucket: atomic check-and-`INCR` — returns 1 if `running < max` (token taken), else 0. |
| `release_token` | `[runningKey]` | — | Token Bucket: `DECR` the running counter, **floored at 0** so a redelivery can't drive it negative. |
| `release_lock` | `[lockKey]` | `[token]` | Per-Key Serialized: compare-and-delete — deletes the lock only if it still holds the caller's `token` (owner-safe release). |

## Key-naming conventions

- Streams: `{domain}.{entity}.v{n}` (e.g. `payments.incoming.v1`); DLQ suffix `:dlq`.
- Consumer groups: `{domain}-group` (Fan-Out is the exception: `fanout-group-{n}`, one per worker).
- Per-worker done streams: `*.done.worker-{n}` / `*.worker{n}.done`.
- Config/rules hashes: `routing:rules:{stream}`, `routing:config:{stream}`, `token-bucket:config`.
- Locks: `running:{entity}:{id}` (Per-Key) ; counters `token-bucket:running:{type}` / `:completed:{type}`.
- Scheduling: Sorted Set `scheduled.messages` (score = epoch ms), payload Hash `scheduled:message:{id}`.

## WebSocket event types

`DLQEvent.EventType`: `MESSAGE_PRODUCED`, `MESSAGE_DELETED`, `MESSAGE_RECLAIMED`,
`MESSAGE_PROCESSED`, `MESSAGE_TO_DLQ`, `ERROR`, `INFO`, `TEST_STARTED`, `TEST_COMPLETED`,
`PROGRESS_UPDATE`.
`PubSubEvent.EventType`: `MESSAGE_PUBLISHED`, `MESSAGE_RECEIVED`, `INFO`, `ERROR`.
Frontend display rule: `MESSAGE_PRODUCED` adds, `MESSAGE_DELETED` removes; processing events are
informational.

## Visualization vs processing (important invariant)

Display always uses **`XREVRANGE`** (read-only). Consumer-group reads (`XREADGROUP`/Lua) are reserved
for actual processing. Mixing them would create phantom PENDING entries in the UI — see ADR-0006.
