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
│  Functions library `stream_utils`: read_claim_or_dlq · request · response · route_message │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

Containers (`docker-compose.yml`): `redis`, `backend`, `frontend` (nginx), `redis-insight`.

## Backend building blocks

- **`config/RedisConfig` + `RedisProperties`** — builds the `JedisPool` (pool sizes/timeouts from
  `application.yml`, overridable by `REDIS_*` env vars). All services borrow/return connections.
- **`RedisLuaFunctionLoader`** — on startup loads `lua/stream_utils.lua` via `FUNCTION LOAD REPLACE`
  and verifies the 4 functions are registered.
- **`RedisStreamListenerService`** — the live-view engine. One **Virtual Thread per monitored
  stream** runs `XREAD BLOCK 1000`; on new entries it broadcasts `MESSAGE_PRODUCED`. Detects messages
  regardless of source (API, worker, or RedisInsight). `StreamMonitorService` is the deprecated
  `XREADGROUP`-based predecessor (Spring profile `disabled`).
- **`WebSocketEventService`** — thread-safe fan-out to all sessions (`CopyOnWriteArraySet`, per-session
  sync). Broadcasts `DLQEvent` and `PubSubEvent`.
- **`KeyspaceNotificationConfig` + `KeyspaceNotificationListener`** — enables `notify-keyspace-events Ex`
  and subscribes to `__keyevent@0__:expired` to drive Request/Reply **timeouts**.
- **`RedisPubSubConfig` + `RedisPubSubListener`** — blocking `SUBSCRIBE`/`PSUBSCRIBE` on daemon threads
  for the two pub/sub patterns.
- **`config/WebSocketConfig` + `websocket/DLQEventWebSocketHandler`** — registers `/ws/dlq-events`
  (→ `/api/ws/dlq-events`) with SockJS fallback and `allowedOriginPatterns("*")`.
- **`config/CorsConfig`** — permits all origins/methods/headers (demo only).

## Thread model

| Component | Threads | Mechanism |
|-----------|---------|-----------|
| Stream live-view | 1 Virtual Thread / monitored stream | `XREAD BLOCK 1000` |
| Work Queue | 4 Virtual Thread workers | poll 100ms, `FCALL read_claim_or_dlq` |
| Fan-Out | 4 Virtual Thread workers (1 group each) | poll 100ms, `FCALL read_claim_or_dlq` |
| Content-Based Routing | 1 Virtual Thread router | poll 500ms, `FCALL read_claim_or_dlq` |
| Per-Key Serialized | 3 Virtual Thread workers | poll 500ms, `SET NX` lock + `XAUTOCLAIM` |
| Token Bucket | 18 Virtual Thread workers (6/type) | poll 10ms, Lua acquire-token `EVAL` |
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
| `route_message` | `[exchange_stream]` | `[routingKey, payload]` | Loads rules from `routing:rules:{stream}`, sorts by priority, Lua-pattern matches, fans out via `XADD` to matched target streams (stop-on-match supported). Returns `[exchangeId, routedTo[], rulesEvaluated, rulesMatched]`. |

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
