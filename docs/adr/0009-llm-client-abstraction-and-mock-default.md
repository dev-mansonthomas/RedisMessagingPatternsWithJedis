# ADR-0009 — Pluggable LlmClient with a mock default for the LLM Chat pattern

- Status: Accepted
- Date: 2026-07-01
- Relates to: Pattern #12 (`docs/specs/llm-chat.md`), ADR-0003 (Virtual Threads), ADR-0008 (demo-grade security)

## Context

Pattern #12 ("LLM Chat with Redis Streams") demonstrates why Redis Streams fit an LLM conversation:
durable/replayable log, context reconstruction, fan-out, and crash-recovery. The Redis mechanics —
not the model — are the teaching point. A solution-architect demo must **always work**: at a keynote,
a booth, offline, with no API key, no per-call cost, and no outbound network. It must also be
reproducible so the same input yields the same on-screen behavior.

We also decided how tokens reach the browser and how the responder consumes messages; those
decisions are recorded here because they shape the code an implementer writes.

## Decision

1. **Abstract the model behind a small `LlmClient` interface** (`generate(context, onToken,
   onComplete)` + `modelName()`), selected by the `llm.client` property.
2. **Ship only `MockLlmClient` in Slice 1**, and make it the default. It is deterministic (reply is a
   pure function of the context), streams tokens with a configurable delay for a credible "typing"
   effect, needs no secret, and makes no network call.
3. **Defer `OllamaLlmClient`** (local, no key/cost/egress) to a later slice; the interface is ready.
4. **Reject an Anthropic/remote-API client** for this demo: it introduces API-key/secret-management
   surface that ADR-0008 and the isolated-VM posture (no outward credentials) deliberately avoid, for
   little demo value. Not planned.
5. **Token transport = one stream per conversation** `chat:{cid}:tok` (capped), each token carrying
   `msgId`; a single long-lived `LlmTokenListenerService` per `cid` broadcasts them and the frontend
   demultiplexes by `msgId`. This avoids the per-response listener spawn/teardown churn (and the
   "which key do I listen to?" discovery problem) of a stream-per-response design.
6. **Responder group created before the first `XADD`, at `$` (`XGROUP_LAST_ENTRY`, `MKSTREAM`).**
   Because the group's baseline precedes every user message, `XREADGROUP >` cannot miss a message; on
   restart the persisted last-delivered-id prevents replay. A `role != user → XACK & skip` guard stops
   the worker from regenerating on its own assistant turns (which the group re-delivers).

## Post-review hardening (2026-07-01)

Following `/code-review` + `/security-review`, Slice 1 also adopted:
- **Bounded workers**: per-cid workers share `AbstractPerCidWorker`; `LlmChatService` enforces an LRU
  cap + idle reaper + `@PreDestroy`, and `reset()` stops the workers — so distinct cids can't leak
  threads/streams unboundedly.
- **WebSocket confidentiality without auth**: LLM events are delivered only to sessions that sent
  `{"type":"subscribe","cid":...}`, and the frontend uses an **unguessable `crypto.randomUUID()`**
  cid (bearer capability). This removes the passive "every client sees every conversation" leak;
  true isolation still requires auth (out of scope per ADR-0008).
- **Token stream `EXPIRE`** after completion + listener tails from the last-id captured at start
  (never replays a prior reply); context is bounded by the triggering entry id (no cross-message
  echo); a mid-generation failure still emits a terminal event so the UI never hangs.

## Consequences

- `launch-docker.sh` runs the full pattern with zero external dependencies; CI is offline-safe.
- Adding Ollama later is a new `LlmClient` bean + a `llm.client=ollama` branch — no orchestration
  change (worker, token stream, listener, controller all stay).
- The demo is reproducible: identical input → identical token stream.
- Because generation is mocked, the demo proves the **Redis** guarantees (ordering, replay, fan-out,
  recovery), not model quality — which is the intent.
- Integration tests run against a real Redis via the `docker` CLI (see `AbstractRedisIntegrationTest`);
  Testcontainers' bundled docker-java negotiates Docker API v1.32, which this engine (min v1.40)
  rejects, so we drive the container with the CLI instead.
