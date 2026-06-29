# CLAUDE.md — Redis Messaging Patterns (entry map for agents)

> Global engineering standards live in `~/.claude/CLAUDE.md` and apply here unchanged.
> This file is the **project-specific** entry map. Keep it current when behavior changes.
> Tags: items marked **(inferred — verify)** were reconstructed from code, not stated by the author.

## What this is

An **educational demo** that showcases enterprise messaging patterns implemented on **Redis**
(Streams, Pub/Sub, Sorted Sets, Lua Functions) with a **Spring Boot + Jedis** backend and an
**Angular 21** single-page frontend. Each pattern has its own page that visualizes the Redis
data flow in real time over WebSocket. **Not production-ready** — it favors clarity and
observability over hardening.

## Stack (verified from `pom.xml` / `frontend/package.json`)

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 | Virtual Threads used heavily |
| Spring Boot | 3.5.7 | Web, WebSocket, Actuator, Validation |
| Jedis | 7.1.0 | Direct `JedisPool`, no Spring Data Redis |
| Redis | 8.4-alpine | **8.4+ required** for `XREADGROUP ... CLAIM` |
| Angular | 21 | Standalone components, lazy routes, Angular Material |
| Realtime | SockJS + raw WebSocket | endpoint `/api/ws/dlq-events` |
| Diagrams | mermaid 11 | per-pattern flow diagrams in the UI |

## How to run (verified)

- **Docker (only fully-working path in this VM):** `./launch-docker.sh --build`
  → frontend http://localhost:4200, backend http://localhost:8080/api, RedisInsight :5540.
- **Backend locally:** **Java 21 + Maven now installed in this VM** (Temurin/OpenJDK 21.0.11,
  Maven 3.9.16) — `mvn compile` / `mvn package` work directly. Docker remains the canonical path
  (multi-stage `Dockerfile`, `maven:3.9-eclipse-temurin-21-alpine`). Lua functions auto-load on startup.
- **Frontend locally:** `cd frontend && npm ci && npm start` (VM runs **Node 24.16**, npm 11). `npm ci` is
  required in this VM — a host-installed `node_modules` carries the wrong `esbuild` native binary (darwin vs linux).
- **Lua lint:** `luacheck lua/ --globals redis cjson cmsgpack bit` (luacheck 1.2.0, Lua 5.1) →
  0 errors, 5 cosmetic warnings (long line / trailing whitespace).
- **Tests:** none exist yet (no `src/test`, no `*.spec.ts`). See `docs/TODO.md`.
- **Lint:** `cd frontend && npm run lint` → currently **76 errors** (see `docs/TODO.md`).

## Layout

- `src/main/java/com/redis/patterns/` — backend: `controller/`, `service/`, `config/`, `dto/`, `websocket/`
- `lua/stream_utils.lua` — all registered Redis Functions (`read_claim_or_dlq`, `request`, `response`, `route_message`)
- `frontend/src/app/components/<pattern>/` — one Angular component per pattern page
- `frontend/src/app/services/` — `redis-api`, `websocket`, `stream-refresh`, `routing-rules`, `diagram-definitions`
- `docs/` — agent-facing docs (this map points into them)
- `augmentcode/` — **legacy** agent notes (covers only the first 4 patterns; superseded by `docs/`)

## The 11 patterns (route → primary Redis structure)

| Page route | Pattern | Redis structure | Key streams/keys |
|------------|---------|-----------------|------------------|
| `/dlq` | Dead Letter Queue | Streams + Consumer Groups + Lua | `test-stream`, `test-stream:dlq` |
| `/pubsub` | Publish/Subscribe (QoS0) | Pub/Sub channels | `fire-and-forget` |
| `/request-reply` | Request/Reply | Streams + keyspace-expiry timeout | `order.holdInventory.v1(.response)` |
| `/work-queue` | Work Queue (competing consumers) | Streams + 1 group, N workers | `jobs.imageProcessing.v1` |
| `/fan-out` | Fan-Out (broadcast) | Streams + **N groups** | `fanout.events.v1` |
| `/topic-routing` | Topic Routing (stream) | Lua `route_message` + rule hashes | `events.topic.v1` → `events.*` |
| `/pubsub-topic-routing` | Topic Routing (Pub/Sub) | `PSUBSCRIBE` patterns | `order.<region>.<event>` |
| `/content-routing` | Content-Based Routing | Streams + amount thresholds | `payments.incoming.v1` → tiers |
| `/scheduled-messages` | Scheduled/Delayed Messages | Sorted Set + Hash + Stream | `scheduled.messages`, `reminders.v1` |
| `/per-key-serialized` | Per-Key Serialized | Stream + `SET NX` lock per key | `jobs.perkey.v1`, `running:order:{id}` |
| `/token-bucket` | Token Bucket (concurrency cap) | Stream + Lua counter | `token-bucket.jobs.v1` |

Full contracts: `docs/specs/<pattern>.md`. System design: `docs/architecture/overview.md`.
Decisions & rationale: `docs/adr/`. Open issues: `docs/TODO.md`.

## Cross-cutting facts agents must know

- **Context path is `/api`** — every REST path and the WebSocket endpoint are prefixed with it.
- **Lua auto-loads** on startup via `RedisLuaFunctionLoader` (`@PostConstruct`, replaces the library).
- **Stream visualization uses `XREVRANGE`** (read-only, no PENDING side effects); **processing uses
  `XREADGROUP`/Lua**. Don't read groups for display — it creates phantom pending entries.
- **Live UI updates** come from `RedisStreamListenerService` (one Virtual Thread per monitored
  stream, `XREAD BLOCK 1000`) broadcasting `DLQEvent`/`PubSubEvent` over WebSocket.
- **Several services clear their demo streams on startup** (`@Order`-sequenced runners) for a clean slate.
- **No auth, CORS is `*`** — acceptable for a local demo, not for deployment (see ADR-0008 / TODO).
