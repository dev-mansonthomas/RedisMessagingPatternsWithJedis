# XNACK explicit failure semantics + Redis 8.8 upgrade (page /dlq)

> From `docs/product/brief-xnack-redis88.md` (validated 2026-07-09). Branch: `blog/dlq-post` or a
> dedicated `feat/xnack-redis88` off it — author's call at implementation.
> **Blocks** the blog post: `docs/specs/blog-dlq-post.md` + `.plan.md` are amended by this slice.

## Purpose

Upgrade the demo from Redis 8.4 to **8.8** and expose the new **`XNACK`** stream command on the
`/dlq` page: three buttons that read the next message and explicitly release it with mode
`FAIL` (explicit failure, immediate retry), `FATAL` (poison, swept to DLQ on the next poll without
waiting `minIdle`) or `SILENT` (returned untouched, failure budget refunded). The existing
`read_claim_or_dlq` Lua function is **compatible unchanged** (verified). This is the prerequisite
for blog post #1, which gains an "explicit failure" section.

## Verified facts (Redis 8.8.0, 2026-07-09 — normative, do not re-derive)

- Syntax: `XNACK key group <SILENT|FAIL|FATAL> IDS numids id [id ...] [RETRYCOUNT n] [FORCE]`.
  Reply: integer = number of messages actually released.
- Released message: stays in PEL, **consumer cleared**, **`idle = -1`**, immediately claimable
  (bypasses `minIdle` in both `XPENDING IDLE` and `XREADGROUP ... CLAIM`).
- Delivery counter after XNACK: `SILENT` → **0**, `FAIL` → **unchanged** (failure counted),
  `FATAL` → **9223372036854775807** (`Long.MAX`).
- `XREADGROUP ... >` does **not** re-deliver released messages — only the claim path does
  (our Lua uses `CLAIM`, so no change needed).
- Edge cases: XNACK on a never-delivered or already-ACKed id → `0` (no-op) unless `FORCE`
  (creates an unowned PEL entry — **we do not use FORCE**); unknown group → `NOGROUP` error;
  double-XNACK → `1` (idempotent); **no ownership check** (any client may release any pending id);
  `RETRYCOUNT n` overwrites the counter.
- `read_claim_or_dlq` on 8.8: `FATAL` message swept to DLQ by the **next** `FCALL` with **no
  `minIdle` wait**; `FAIL` message re-delivered **immediately**, counter increments on re-claim.

## User stories / acceptance criteria

- As an SA running the demo, I can show the difference between a crash (no ACK → wait `minIdle`,
  budget consumed), an explicit failure (immediate retry, budget consumed), a poison declaration
  (immediate DLQ next poll) and a graceful give-back (immediate, budget refunded).
- As a viewer, I can see the released state in the pending view (no consumer, "released" badge)
  and a poison message as **∞/poison**, not `9223372036854775807`.

Testable:

- [ ] Given the repo, `grep -rn "redis:8.4\|8\.4" --include=*.{yml,java,md,lua,sh,xml}` (excluding
      `augmentcode/` legacy and git history) returns no stale reference: compose image, test image,
      README, CLAUDE.md, ADR-0001/0004, `lua/stream_utils.lua` header, `docs/*` all say **8.8**.
- [ ] `mvn test` passes with `AbstractRedisIntegrationTest.IMAGE = "redis:8.8-alpine"` (all 40
      existing tests, incl. TimeSeries — confirms the module ships in 8.8-alpine).
- [ ] `POST /api/dlq/process` with body `{"outcome":"NACK_FAIL"}` reads the next message, sends
      `XNACK <stream> <group> FAIL IDS 1 <id>`, and the integration test observes: entry still
      pending, consumer empty, `deliveries` unchanged, re-claimable immediately by the next
      `FCALL` even with `minIdleMs` = 60000.
- [ ] `{"outcome":"NACK_FATAL"}` → `deliveries = Long.MAX` in XPENDING; the next `FCALL` (no wait)
      returns the `[orig_id, dlq_id]` pair and `XRANGE <dlq>` contains the copy.
- [ ] `{"outcome":"NACK_SILENT"}` → entry pending, consumer empty, `deliveries = 0`.
- [ ] `{"outcome":"ACK"}` and `{"outcome":"NO_ACK"}` behave exactly like today's
      `shouldSucceed=true/false`; legacy body `{"shouldSucceed":bool}` still works (mapped) — no
      frontend breakage during transition.
- [ ] Invalid `outcome` → HTTP 400 with `success:false`.
- [ ] `GET /api/dlq/pending-messages` response items now include `consumer` (string, may be empty)
      and `idleMs` (number, may be `-1`); a Jedis parse of a released entry does not throw.
- [ ] UI: the `/dlq` actions panel shows, in order: Generate · Process & Success ·
      Process & Fail (timeout) [renamed] · Process & Explicit Fail · Process & Poison ·
      Process & Release (silent) · Clear — each new button calls `/process` with its outcome and
      shows the returned status line.
- [ ] UI pending view: released entry shows a "released" badge (consumer empty **or** `idleMs`
      < 0); `deliveryCount > 2^53` renders as **∞ poison** (JSON number precision: `Long.MAX`
      arrives rounded in JS — compare with a threshold, e.g. `>= Number.MAX_SAFE_INTEGER`, never
      equality).
- [ ] Jedis upgraded 7.1.0 → **7.5.3** in `pom.xml`; build + tests green.
- [ ] Docs synced in the same change: `docs/specs/dlq.md` (+ XNACK semantics table),
      `docs/diagrams/dlq.md` (sequence gains an explicit-fail lane), `/dlq` page explanation block,
      README DLQ section, `CLAUDE.md` (stack table 8.4→8.8, cross-cutting facts), ADR-0001/0004
      version notes, and a new **ADR-0011: adopt XNACK for explicit failure** (context, the
      3 modes, why direct Jedis command instead of Lua wrapper).
- [ ] Blog docs amended: `docs/specs/blog-dlq-post.md` (word budget **1500–1800**, section plan
      gains §"Explicit failure with XNACK" after the sweep walkthrough, Redis requirement 8.4+ →
      **8.8+**, walkthrough gains XNACK FAIL/FATAL/SILENT demo commands, acceptance boxes updated)
      and `blog-dlq-post.plan.md` (walkthrough task covers XNACK; 3-FCALL sequence re-verified on
      8.8 — expected identical).

## Inputs & outputs

### REST — extend `POST /api/dlq/process`

Request (new canonical form):
```json
{ "outcome": "ACK" | "NO_ACK" | "NACK_FAIL" | "NACK_FATAL" | "NACK_SILENT" }
```
Legacy `{ "shouldSucceed": true|false }` maps to `ACK`/`NO_ACK` (keep until frontend migrated,
then still keep — harmless). Response: existing shape (`success`, `message`, `messageId`,
`deliveryCount`, `wasRetry`) + new `outcome` echo. Message strings:
`"✗ Message %s explicitly NACKed (FAIL) — immediately retryable (deliveryCount: %d)"`,
`"☠ Message %s poisoned (FATAL) — will be swept to DLQ on next poll"`,
`"↩ Message %s released (SILENT) — failure budget refunded (deliveryCount: 0)"`.

### Backend

- `DLQMessagingService.processNextMessage(ProcessOutcome outcome)` (enum in `dto/`); the old
  `processNextMessage(boolean)` delegates to it.
- New private `long xnack(String stream, String group, XNackMode mode, String... ids)` using the
  **Jedis raw-command pattern** (no typed API in any stable Jedis; verified):
  ```java
  enum DlqProtocolCommand implements redis.clients.jedis.commands.ProtocolCommand {
      XNACK;
      private final byte[] raw = redis.clients.jedis.util.SafeEncoder.encode(name());
      @Override public byte[] getRaw() { return raw; }
  }
  // in a try (var jedis = jedisPool.getResource()):
  Long released = (Long) jedis.sendCommand(DlqProtocolCommand.XNACK,
      stream, group, mode.name(), "IDS", "1", messageId);
  ```
  (`Jedis.sendCommand(ProtocolCommand, String...)` is non-deprecated through 7.5.3; RESP `:` reply
  → `Long`. Do NOT use `UnifiedJedis.sendCommand` — deprecated since 7.4.)
- WebSocket: new `DLQEvent.EventType.MESSAGE_NACKED`, broadcast with `details` =
  `"XNACK <MODE>"` and the post-XNACK delivery count; frontend flashes it like
  `MESSAGE_RECLAIMED`.
- `getPendingMessages(...)`: add `consumer` (`info.getConsumerName()`, null-safe → `""`) and
  `idleMs` (`info.getIdleTime()`) to each returned map.

### Frontend

- `dlq-actions.component.ts`: 3 new buttons (colors: explicit fail = existing red family, poison =
  dark/skull, silent = neutral/slate), calling `processMessage(outcome)`; existing two buttons
  switch to the `outcome` body. Tooltips: one line each on budget semantics.
- Pending/stream viewer: "released" badge when `consumer === '' || idleMs < 0`; render
  `deliveryCount >= Number.MAX_SAFE_INTEGER` as `∞ (poison)`.

### Redis keys touched

Unchanged: `test-stream`, `test-stream:dlq`, group/consumer from `DLQConfigService`. No new keys.
`lua/stream_utils.lua`: **no functional change** (header version comment 8.4.0+ → 8.8.0+ only,
since the demo now targets 8.8 and the blog claims it).

## Behavior & edge cases

Happy paths: see acceptance. Edge cases (all with expected handling):

- **No message available** → existing `success:false, "No messages available to process"` for all
  outcomes.
- **XNACK returns 0** (message ACKed/claimed-and-acked between read and nack — race): respond
  `success:false`, message `"Message %s was no longer pending — nothing released"`; no WS event.
- **`NOGROUP`**: impossible in practice (`initializeConsumerGroup` runs first) but keep the
  existing NOGROUP-tolerant catch pattern.
- **Poison then config change**: `deliveries=Long.MAX` always satisfies `>= maxDeliveries`
  whatever the config — document in ADR-0011, no special handling.
- **SILENT loop**: repeatedly clicking Silent re-reads the same message (immediately claimable,
  counter stays 0) — acceptable demo behavior; the status line makes it observable. Note it in the
  page explanation.
- **JSON precision**: `deliveryCount` serialized as JSON number; `Long.MAX` > 2^53 arrives rounded
  (`9223372036854776000`). Frontend threshold-compare only. Backend may alternatively cap the
  value it serializes (e.g. send `-1` for poison) — **decision: send the raw number**, keep the
  API honest; the UI owns the rendering.
- **Jedis parse of released PEL entries**: `XPENDING` extended reply now has empty consumer +
  idle `-1`; integration test must cover `getPendingMessages` over a released entry (risk:
  NPE/parse error inside Jedis 7.5.3 — if it throws, fallback is a raw `sendCommand(XPENDING …)`
  parse in our service; only implement the fallback if the test proves it necessary).
- **Rollback**: if 8.8 image breaks anything unrelated (TimeSeries, keyspace notifications), stop
  and report — do not pin mixed versions.

## Out of scope

- XNACK on other pattern pages (work-queue, fan-out, llm-chat DLQ…) — candidate follow-up.
- `RETRYCOUNT` / `FORCE` in UI or REST (documented in ADR-0011 only).
- Per-message NACK from the pending list (button acts on *next* message only, like today).
- Automated graceful-shutdown SILENT (lifecycle hook) — demo stays button-driven.
- Writing the blog post itself (separate, already specced — this slice only amends its spec/plan).
- Frontend test runner (still none — unchanged, `docs/TODO.md`).

## Test plan

Backend (extend the existing integration-test infra, `support/AbstractRedisIntegrationTest`,
image bumped to `redis:8.8-alpine` — these are the **first DLQ tests**, TDD them):

1. `DLQXnackIntegrationTest` (new): for each mode — produce, read via service, XNACK via the new
   path, assert XPENDING state (consumer empty, idle -1, counter 0/unchanged/Long.MAX), then:
   FAIL → immediate `FCALL` re-delivers despite `minIdle=60000`; FATAL → immediate `FCALL` sweeps
   to DLQ (assert `[orig,dlq]` pair + DLQ content + PEL clean); SILENT → immediate `FCALL`
   re-delivers with counter back to 1.
2. `processNextMessage(outcome)` unit-ish integration: the 5 outcomes + legacy boolean mapping +
   invalid outcome → 400 (controller test or `@WebMvcTest`).
3. Race case: ACK the id behind the service's back, then NACK_* → `success:false`, XNACK reply 0.
4. `getPendingMessages` over a released + a poisoned entry → fields present, no exception.
5. Full `mvn test` on 8.8 (existing 40 tests) — proves the upgrade alone breaks nothing.

Frontend (manual + Playwright smoke via `webapp-testing` if time permits): the 6 buttons drive the
expected status lines; poison entry renders ∞; released badge visible. (No test runner exists —
manual checklist in the PR description, consistent with project status.)

Docs: `luacheck` unchanged; `grep` acceptance for stale 8.4 refs; blog spec/plan diffs reviewed.

## Dependencies & risks

- **Jedis 7.5.3** (latest stable, verified on Maven Central 2026-07-09). Typed `xnack()` exists
  only in 8.0.0-beta1 (and lacks RETRYCOUNT/FORCE) — do not adopt a beta; revisit at 8.0.0 GA.
- **`redis:8.8-alpine`** image (exists, XNACK verified against it).
- **Riskiest**: Jedis 7.5.3 parsing of released PEL entries in `xpending` (idle -1 / empty
  consumer) — de-risked by test #4 before UI work; fallback identified. Second: JSON `Long.MAX`
  precision in the UI — de-risked by threshold-compare acceptance box.

## Next step

`/plan-feature xnack-redis88` — suggested order: image/docs bump + `mvn test` (safety net) →
Jedis bump → failing `DLQXnackIntegrationTest` → service/enum → controller → WS event → UI →
doc-sync + ADR-0011 → blog spec/plan amendments.
