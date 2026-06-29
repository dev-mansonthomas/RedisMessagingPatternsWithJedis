# Code Review Findings — Redis Messaging Patterns

> First full code review of the project (2026-06-29). Scope: critical paths —
> Redis interaction + pattern implementations (backend + Lua). 47 raw candidates
> from a 6-finder parallel pass, deduped and verified against the code.
> Severity is weighted for an **educational, single-node, no-auth demo**: Redis
> semantics and pattern correctness bugs count double (they are what the project
> teaches); deployment-hardening issues are noted but ranked lower.
>
> Status legend: `TODO` · `FIXED` · `WONTFIX (documented)`

## Priority 1 — Redis semantics / pattern correctness

### F1 — `XREADGROUP .block(0)` blocks the request thread forever — `FIXED`
`service/DLQMessagingService.java:294`. `block(0)` blocks indefinitely; on a
stream with no undelivered (`>`) entries the REST thread never returns **and**
the borrowed pooled connection is never released → pool leak/exhaustion.
**Fix:** finite block timeout (2000 ms) + handle the `null`/empty return.
**Accept:** calling the process endpoint on an empty stream returns promptly;
no connection leak.

### F2 — Consumer groups / stream readers start at `0-0` while comments claim `$`/"latest" — `FIXED`
`DLQMessagingService.java:73`, `WorkQueueService.java:82`, `FanOutService.java:85`
(consumer groups) and `RedisStreamListenerService.java:123` (`XREAD`).
`new StreamEntryID()` encodes to `0-0` (start of stream), not `$`. The comments
say the opposite — a lie on the most safety-critical line.
**Decision:** the demo flow *produces before creating the group / starts the
monitor on already-populated streams*, so reading from the start is intentional
for the worker groups → **fix the comments to state the real behaviour**. For
the live monitor (`RedisStreamListenerService`) replaying history on restart is
a real defect → start from `$` (`StreamEntryID.LAST_ENTRY`) so a restart never
re-broadcasts old entries.
**Accept:** comments match behaviour; restarting the backend does not re-emit
historical `MESSAGE_PRODUCED` events.

### F3 — Token Bucket: `XAUTOCLAIM idle=100ms` ≪ processing 4–10 s → double processing, cap violated — `FIXED`
`service/TokenBucketService.java:153`. Messages are `XACK`ed only after the
multi-second `sleep`, so a peer worker reclaims an in-flight message after 100 ms
and processes it again — breaking the very concurrency cap the pattern enforces.
**Fix:** raise the idle reclaim threshold above the max processing time (≥ 15 s,
i.e. > the 10 s CSV job).
**Accept:** a single job is processed by exactly one worker; running counter
never exceeds the configured cap.

### F4 — `route_message` commits the exchange XADD before evaluating rules; a malformed user pattern aborts the function — `FIXED`
`lua/stream_utils.lua` (route_message) + `controller/RoutingRulesController.java`
(no pattern validation on write). User-supplied `rule.pattern` is run via
`string.match` with no guard; a malformed pattern raises *"malformed pattern"*,
the exchange entry is already persisted (no rollback) → message routed nowhere,
and one bad rule disables all routing for that exchange.
**Fix:** (a) wrap pattern matching in `pcall` in Lua so one bad rule can't abort
the whole function; (b) validate the pattern at write time in the controller
(reject patterns that fail to compile). Optionally evaluate rules before the
exchange XADD.
**Accept:** writing a malformed pattern is rejected with 400; a pre-existing bad
rule degrades to "skip that rule", routing of others still works.

## Priority 2 — robustness / availability

### F5 — Blocking SUBSCRIBE/PSUBSCRIBE on the shared pool + no reconnect — `FIXED`
`config/RedisPubSubConfig.java:45`, `config/KeyspaceNotificationConfig.java:62`,
`service/PubSubTopicRoutingService.java:77`. Each borrows from the shared
`JedisPool` and blocks for life (anti-pattern: blocking pub/sub needs a dedicated
connection), and there is no reconnect loop — one Redis drop permanently kills
pub/sub + keyspace-expiry timeouts until restart.
**Fix:** use a dedicated `Jedis` (not the pool) for the blocking subscribe, wrap
in a reconnect loop with backoff, honour shutdown.
**Accept:** killing+restarting Redis re-establishes the subscription
automatically; pool capacity is not consumed by subscribers.

### F6 — Per-key lock released without ownership check + reclaim idle ≪ processing — `FIXED`
`service/PerKeySerializedService.java:148,209`. `finally` does an unconditional
`DEL lockKey` (no check that the stored `messageId` is still ours) → on a TTL
expiry/GC pause it can delete another worker's lock; `XAUTOCLAIM idle=500ms` ≪
4 s processing causes repeated reclaim churn (delivery-count inflation).
**Fix:** release the lock with a compare-and-delete Lua (`if GET==token then DEL`);
raise reclaim idle above processing time.
**Accept:** a lock is only ever released by its owner; no spurious reclaim churn.

### F7 — Streams worker patterns: XADD-to-done then XACK (at-least-once duplicates) — `FIXED (documented)`
`service/WorkQueueService.java:232`, `FanOutService.java`, `TokenBucketService.java`.
A crash between writing the "done" entry and `XACK` re-delivers/reprocesses the
job (duplicate done entry; false DLQ after MAX_DELIVERIES). Inherent to
at-least-once delivery.
**Fix:** document the idempotency expectation clearly on these paths (demo); no
behavioural change required.
**Accept:** code comment explains the at-least-once / idempotency contract.

### F8 — Scheduled messages: non-atomic ZRANGEBYSCORE-then-ZREM + zscore NPE — `FIXED`
`service/ScheduledMessagesService.java:116,129,143`. Read-then-delete is correct
only because a single poller exists; a concurrent `deleteMessage` makes
`zscore(...).longValue()` NPE (zscore → null) → swallowed → orphaned
`scheduled:message:*` hash leaks.
**Fix:** null-check the `zscore` result; (optionally) claim due messages
atomically. Minimum: guard the NPE and clean up the hash.
**Accept:** deleting a message during a poll never NPEs or leaks a hash.

## Priority 3 — security / configuration / readability

### F9 — CORS `*` origins + `allowCredentials(true)` + all methods/headers — `FIXED`
`config/CorsConfig.java:44-47`. Dangerous combination if auth is ever added;
broad surface now.
**Fix:** drive allowed origins from config (default the local frontend origins),
keep credentials only with an explicit allowlist.
**Accept:** only configured origins are accepted; `*`+credentials is gone.

### F10 — Lua loader: filesystem-relative path + incomplete verification — `FIXED`
`service/RedisLuaFunctionLoader.java:115,142`. Loads via CWD-relative path (breaks
in a packaged jar) and verifies only library presence, not each function.
**Fix:** load from the classpath; verify each expected function via
`FUNCTION LIST`.
**Accept:** loads regardless of CWD; a missing function fails fast at startup.

### Secondary (lower severity)
- **S1** `DLQMessagingService.java:649` — `getDeliveryCount` returns hardcoded `2`
  on XPENDING miss → new message mislabelled "retry". `FIXED` (return 1 / treat
  as new on miss).
- **S2** `DLQMessagingService.java:246` — `claimOrDLQ` never sets `messagesToDLQ`
  and counts new messages as "reclaimed". `FIXED` (report the DLQ count, relabel).
- **S3** `RequestReplyService.java:482` — `switch(responseType)` lacks a
  null/default branch → NPE after XACK drops the response. `FIXED` (default branch).
- **S4** `lua/stream_utils.lua` route_message + `ContentBasedRoutingService.java:179`
  — no default route / `amount` defaults to 0 → silent loss/misroute. `FIXED`
  (explicit unrouted handling / reject missing amount).
- **S5** `lua/stream_utils.read_claim_or_dlq.lua` — dead file re-declaring library
  `stream_utils` with one function; `FUNCTION LOAD REPLACE` on it would clobber
  the real library. `FIXED` (remove the file).
- **S6** `service/PubSubTopicRoutingService.java:107` — payload (de)serialised by
  `split(',')`/`split('=')`, no escaping → values with `,`/`=` corrupted. `FIXED`
  (JSON).
- **S7** `service/KeyspaceNotificationListener.java` — missed expiry event = lost
  timeout, no reconciliation. `WONTFIX (documented)` — acceptable for the demo;
  noted as a known limitation.

### Cross-cutting readability
`fcall` replies are decoded as nested untyped `List<Object>` with casts /
`instanceof` / even-index field parsing across `WorkQueueService`, `FanOutService`,
`TokenBucketService.processEntry`. The ACK/DLQ contract is hard to audit.
**Suggestion:** extract a small typed decoder documenting the Lua reply shape.
`TODO (follow-up)` — not done in this pass to keep the diff reviewable.
