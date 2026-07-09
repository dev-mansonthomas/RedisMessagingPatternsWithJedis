# Plan — xnack-redis88 (test-first)

> Spec: `docs/specs/xnack-redis88.md`. All XNACK behaviors + Jedis facts were verified 2026-07-09
> (empirical probes on redis:8.8-alpine; Jedis 7.5.3 = latest stable, raw `sendCommand` pattern
> verified in tagged source — no re-derivation needed).
> Toolchain: everything needed (JDK21/Maven, Node, docker, luacheck) is present in this session.

## Verified constants (reuse, don't re-guess)

- Raw command: `jedis.sendCommand(DlqProtocolCommand.XNACK, stream, group, mode, "IDS", "1", id)`
  → `Long` (count released). `ProtocolCommand` impl = enum with `getRaw()` via `SafeEncoder`.
- Released PEL entry: consumer empty, idle `-1`, counter per mode `SILENT`→0 / `FAIL`→kept /
  `FATAL`→`Long.MAX_VALUE`; immediately claimable (bypasses `minIdle`).
- `read_claim_or_dlq` unchanged; FATAL swept by next `FCALL` without wait.

## Ordered tasks

**Task 0 — Redis 8.8 upgrade with existing tests as safety net** *(migration step, net = 40 tests)*
Modify: `docker-compose.yml:17` (`redis:8.4-alpine`→`redis:8.8-alpine`),
`src/test/java/com/redis/patterns/support/AbstractRedisIntegrationTest.java:33` (`IMAGE`) + its
line-22 javadoc, `src/test/java/com/redis/patterns/service/LlmAnalyticsWorkerTest.java:70` comment.
Run `mvn test` → **all pass on 8.8** (also proves TimeSeries ships in 8.8-alpine). If anything
fails → stop, report (spec's rollback rule: no mixed pins).

**Task 1 — Jedis 7.1.0 → 7.5.3** *(isolated from task 0 to keep failure sources separable)*
Modify: `pom.xml`. Run `mvn test` → green. Watch for deprecation warnings (none expected on the
`Jedis` class APIs we use).

**Task 2 — RED: the riskiest thing first — Jedis parsing of released PEL entries**
Create `src/test/java/com/redis/patterns/service/DLQXnackIntegrationTest.java` extending
`support/AbstractRedisIntegrationTest` (same wiring style as `LlmRecoverySweeperTest`). The test
defines its own tiny `ProtocolCommand XNACK` helper (test-local) to put Redis in the released
state before the service supports it. First methods:
- `getPendingMessages_returnsConsumerAndIdle_forOwnedEntry` — asserts the two NEW fields
  (`consumer`, `idleMs`) → **red** (fields absent today).
- `getPendingMessages_parsesReleasedEntry_noThrow` — XNACK FAIL then call
  `DLQMessagingService.getPendingMessages` → asserts no exception, `consumer == ""`,
  `idleMs == -1`, counter kept. If Jedis 7.5.3 throws on the nil/empty consumer, this is where we
  find out (fallback: raw `XPENDING` via `sendCommand` + manual parse — implement only if needed).
- `getPendingMessages_poisonEntry_counterIsLongMax` — XNACK FATAL → `deliveryCount == Long.MAX_VALUE`.

**Task 3 — GREEN: extend `getPendingMessages`**
Modify `src/main/java/com/redis/patterns/service/DLQMessagingService.java:349` — add
`message.put("consumer", info.getConsumerName() == null ? "" : info.getConsumerName())` and
`message.put("idleMs", info.getIdleTime())`. Run `mvn test -Dtest=DLQXnackIntegrationTest` → green.

**Task 4 — RED→GREEN: `ProcessOutcome` + service XNACK path**
Red — add to `DLQXnackIntegrationTest`, one method per acceptance box (all call the not-yet-
existing `processNextMessage(ProcessOutcome)` → compile error = red):
- `nackFail_keepsCounter_immediatelyRetryable` — outcome NACK_FAIL with `minIdleMs=60000` config;
  assert pending+unowned+counter kept, then an immediate `FCALL` re-delivers (no wait).
- `nackFatal_sweptToDlq_onNextPollWithoutWait` — assert counter `Long.MAX`, then immediate `FCALL`
  returns the `[orig,dlq]` pair, DLQ contains the copy, PEL clean.
- `nackSilent_refundsCounter` — counter 0, re-delivered by immediate `FCALL` with counter back to 1.
- `nack_afterExternalAck_reportsNothingReleased` — ACK behind the service's back → response
  `success:false`, no `MESSAGE_NACKED` broadcast.
- `legacyBoolean_mapsToAckAndNoAck`.
Green — create `src/main/java/com/redis/patterns/dto/ProcessOutcome.java` (enum, 5 values);
modify `DLQMessagingService.java`: private enum `DlqProtocolCommand implements ProtocolCommand`,
private `long xnack(stream, group, mode, id)`, `processNextMessage(ProcessOutcome)` (existing
`:750` boolean overload delegates), reuse the existing WS broadcast style with new
`MESSAGE_NACKED` added to `src/main/java/com/redis/patterns/dto/DLQEvent.java:73` enum.
Refactor — extract the produce/read/assert-XPENDING helpers repeated across test methods.

**Task 5 — RED→GREEN: controller contract**
Red — create `src/test/java/com/redis/patterns/controller/DLQProcessControllerTest.java`
(`@WebMvcTest` style like `LlmChatControllerTest`): `outcome` accepted (5 values), legacy
`shouldSucceed` mapped, `outcome` echoed in response, invalid outcome → **400** `success:false`.
Green — modify `src/main/java/com/redis/patterns/controller/DLQController.java:375` `/process`:
parse `outcome` (fallback legacy key), `IllegalArgumentException` → 400.

**Task 6 — Frontend: the 3 buttons** *(no FE test runner — gate = `npm run build` + manual list)*
Modify `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`: `processMessage(outcome:
string)`; buttons in order Generate · Success · **Fail (timeout)** [renamed] · **Explicit Fail** ·
**Poison** · **Release (silent)** · Clear; tooltips = one budget-semantics line each; existing
red/green styles reused, poison = darker variant, silent = slate. `npm run build` green.

**Task 7 — Frontend: released/poison badges in the stream viewer**
Modify `frontend/src/app/components/stream-viewer/stream-viewer.component.ts` (738 lines): when
`group` input is set, merge PEL data via the **currently-unused**
`redis-api.service.ts:65 getPendingMessages()` wrapper into displayed entries; badge `released`
when `consumer === '' || idleMs < 0`; render `deliveryCount >= Number.MAX_SAFE_INTEGER` as
`∞ (poison)` (threshold, never equality — JSON rounds `Long.MAX`). Refresh on `MESSAGE_NACKED`
WS event (extend `frontend/src/app/services/websocket.service.ts` event union if typed).
`npm run build` + Playwright screenshot of `/dlq` after one FAIL + one FATAL (webapp-testing /
chromium headless, as done for the mermaid check).

**Task 8 — doc-sync + ADR-0011 + blog amendments** *(same change as behavior, per standards)*
Modify: `docs/specs/dlq.md` (XNACK semantics table + released-state note),
`docs/diagrams/dlq.md` (sequence gains explicit-fail lane; nuance «≥ minIdleMs apart» = implicit
path only), `frontend/src/app/components/dlq/dlq.component.ts` explanation block, `README.md`
(DLQ section + version claims), `CLAUDE.md` (stack table Redis 8.4→8.8, cross-cutting facts),
`docs/adr/0001…` & `docs/adr/0004…` (version notes), `lua/stream_utils.lua` header comment
(8.4.0+→8.8.0+; run `luacheck`), `docs/TODO.md`.
Create: `docs/adr/0011-xnack-explicit-failure.md`.
Amend: `docs/specs/blog-dlq-post.md` (budget 1500–1800, §explicit-failure, Redis 8.8+ caveat,
walkthrough + acceptance updates) and `blog-dlq-post.plan.md` (walkthrough task covers XNACK;
3-FCALL sequence re-verified on 8.8 during task 4's FATAL test — expected identical).

**Task 9 — Final gates**
`grep -rn "8\.4"` (code+docs, excluding `augmentcode/` legacy + git) → no stale refs ·
`mvn test` (full) · `cd frontend && npm run build` · `luacheck lua/` · `./launch-docker.sh --build`
smoke + Playwright screenshot of the 6 buttons · `/ship` → propose focused commits (upgrade /
backend / frontend / docs+blog).

## Files summary

Create: `dto/ProcessOutcome.java`, `service/DLQXnackIntegrationTest.java` (test),
`controller/DLQProcessControllerTest.java` (test), `docs/adr/0011-xnack-explicit-failure.md`.
Modify: `docker-compose.yml`, `pom.xml`, `AbstractRedisIntegrationTest.java`,
`LlmAnalyticsWorkerTest.java` (comment), `DLQMessagingService.java`, `DLQController.java`,
`dto/DLQEvent.java`, `dlq-actions.component.ts`, `stream-viewer.component.ts`,
`websocket.service.ts` (maybe), `dlq.component.ts` (explanation), `redis-api.service.ts` (types
only if needed), + the doc files listed in task 8.

## Riskiest step & de-risk

**Task 2**: Jedis 7.5.3 parsing `XPENDING` extended replies for released entries (empty consumer,
idle −1) — deliberately the FIRST test written; fallback (raw `sendCommand` XPENDING parse)
implemented only if the test proves Jedis throws. Runner-up: WS/UI regressions from renaming the
Fail button — mitigated by keeping the legacy REST body working (task 5 test).

## How to run

```bash
mvn test -Dtest=DLQXnackIntegrationTest        # tasks 2–4 inner loop
mvn test -Dtest=DLQProcessControllerTest       # task 5
mvn test                                       # full net (tasks 0, 1, 9)
cd frontend && npm run build                   # tasks 6–7 gate
luacheck lua/ --globals redis cjson cmsgpack bit
```

Done = task 9 all green + every acceptance box in `docs/specs/xnack-redis88.md` checkable against
a command above + blog spec/plan amended.

## Sequencing note

`blog/dlq-post` branch currently holds uncommitted doc work (sweep-semantics fixes, briefs, specs,
plans). Suggest committing that batch (`docs:` commit) before task 0 so the upgrade/feature commits
stay focused — author's call.
