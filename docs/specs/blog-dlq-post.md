# Blog post #1 — Dead Letter Queue with Redis Streams & Redis Functions

> First slice of `docs/product/brief-blog-series.md`. Written for agents: implement exactly this.
> Branch: `blog/dlq-post`. Publication tag (created by the author, on the host, at publish time):
> **`blog-dlq-v1`** — see [`blog/PUBLISHING.md`](../../blog/PUBLISHING.md) for the exact host-side
> commands (the pinned permalinks 404 until that tag is pushed).

## Purpose

Produce the first post of the "Redis Messaging Patterns" series for the official Redis blog
(English): explain the **DLQ pattern on Redis Streams** (bounded retries, poison messages routed
to a dead-letter stream) as implemented by the `read_claim_or_dlq` Redis Function of this repo,
with a short **Redis Functions (Lua)** explainer. The reader must be able to reproduce the pattern
**CLI-first** (`redis-cli` / RedisInsight) and call the function from **6 languages** via runnable
samples versioned in this repo — the post links to them (pinned permalinks), it never inlines
long code. Ancillary demo tech (WebSocket, Angular, Spring internals) must not appear.

## Deliverables (file tree)

```
blog/dlq-redis-streams/
├── index.md                  # the post (English, 1500–1800 words of prose)
├── img/
│   ├── dlq-flow.png          # exported logical diagram (referenced by index.md, alt text required)
│   └── dlq-flow.excalidraw   # editable source of the diagram
└── samples/
    ├── setup.sh              # redis-cli: FUNCTION LOAD REPLACE + XGROUP CREATE ... MKSTREAM + seed
    ├── java/                 # pom.xml + src/main/java/DlqExample.java        (Jedis)
    ├── python/               # pyproject.toml (uv) + dlq_example.py           (redis-py)
    ├── node/                 # package.json + dlq-example.mjs                 (node-redis)
    ├── go/                   # go.mod + main.go                               (go-redis v9)
    ├── csharp/               # DlqExample.csproj + Program.cs                 (NRedisStack)
    └── rust/                 # Cargo.toml + src/main.rs                       (redis crate)
```

Plus, **outside** the tree: a coherence-audit report (see below) delivered in the PR/conversation,
and updates to `CLAUDE.md` / `docs/TODO.md` if the audit changes anything.

## User stories / acceptance criteria

- As a backend dev who knows Redis as a cache, I can read the post and understand how Streams +
  consumer groups + a Redis Function give bounded retries with a DLQ.
- As a messaging architect, I can find the pattern's guarantees (at-least-once, atomic DLQ routing,
  no message loss, retry bounded by `maxDeliveries`) stated explicitly in the opening section.
- As a reader, I can reproduce the pattern end-to-end with only `redis-cli` (or RedisInsight) and
  the repo's Lua file — no Java/Angular required.
- As a polyglot dev, I can clone the repo and run the FCALL sample of my language in one documented
  command.

Testable criteria:

- [ ] Given a fresh `redis:8.8-alpine` container, when `samples/setup.sh` runs, then
      `FUNCTION LIST LIBRARYNAME stream_utils` shows the library and
      `XINFO GROUPS test-stream` shows the demo group — script exits 0, idempotent on re-run.
- [ ] Given the fresh container + `setup.sh`, when every `redis-cli` block of `index.md` is executed
      **in document order, verbatim**, then each block's described outcome is observed; in
      particular the deliberately-failed message ends up in `test-stream:dlq` (and is `XACK`ed away
      from `test-stream` PENDING) after `maxDeliver` delivery attempts + `minIdle` idle time.
- [ ] The XNACK section's `redis-cli` blocks, replayed in order on the same container, show:
      `FAIL` → re-claimable immediately with deliveries kept; `FATAL` → swept to the DLQ by the
      very next `FCALL` with no idle wait; `SILENT` → deliveries reset to 0. (Semantics verified
      2026-07-09 and encoded in ADR-0011 + `DLQXnackIntegrationTest`.)
- [ ] Each of the 6 samples runs against that container with the exact one-liner documented in
      `index.md`/sample README (`mvn -q exec:java`, `uv run`, `node`, `go run .`, `dotnet run`,
      `cargo run`), exits 0, and prints (a) messages to process and (b) `[original_id, dlq_id]`
      pairs when a DLQ routing occurs.
- [ ] Prose word count of `index.md` (fenced code blocks and URLs excluded) is **1500–1800**.
- [ ] Every GitHub link in `index.md` matches
      `https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/(blob|tree)/blog-dlq-v1/...`
      and every linked path exists in the working tree (tag is pushed later by the author).
- [ ] `index.md` contains: the 1-paragraph series intro, the logical diagram (relative `img/` path,
      alt text), a pseudo-code block, the Redis Functions explainer, the CLI reproduction, the
      6-language section (links, not inline dumps), the Redis **8.8+** requirement stated before
      the first command, and exactly **one** short inline real-code excerpt (5–15 lines, the Jedis
      `fcall` call from `DLQMessagingService`, followed by its pinned permalink).
- [ ] `grep -iE 'websocket|sockjs|angular|spring' blog/dlq-redis-streams/index.md` → no hits in
      prose (repo-description mentions of "Spring Boot demo" in the closing links section are the
      only tolerated exception, if any).
- [ ] The coherence-audit report exists and lists either "no discrepancy" or each discrepancy with
      a proposed fix; **no demo code/doc change is committed without the author's explicit
      validation**.
- [ ] `luacheck lua/ --globals redis cjson cmsgpack bit` still passes with 0 errors (the post must
      not require Lua changes; if the audit does, see validation gate).

## Inputs & outputs

### The function being explained (already in repo — read-only unless audit says otherwise)

`lua/stream_utils.lua` → `read_claim_or_dlq` (`#!lua name=stream_utils`):

```
FCALL read_claim_or_dlq 2 <stream> <dlq> <group> <consumer> <minIdleMs> <count> <maxDeliver>
KEYS[1]=stream  KEYS[2]=dlq
ARGV: group, consumer, minIdle(ms), count, maxDeliver
Returns: [ messages_to_process = [[id, [f1,v1,...]], ...],
           dlq_ids            = [[original_id, dlq_id], ...] ]
```

Internal steps (this is what the pseudo-code in the post must mirror, 1:1):
1. `XPENDING stream group IDLE minIdle - + count` → entries with `deliveries >= maxDeliver`
2. for those: `XCLAIM` → `XADD` copy to dlq → `XACK` on stream
3. `XREADGROUP GROUP group consumer COUNT count CLAIM minIdle STREAMS stream >` (Redis 8.4+)
4. return `[toProcess, dlqIds]`

### Keys & names used everywhere (post, setup.sh, samples — must be identical)

| Thing | Value |
|---|---|
| Main stream | `test-stream` |
| DLQ stream | `test-stream:dlq` |
| Consumer group | `test-group` — **resolved 2026-07-09**: `DLQConfigService.DEFAULT_CONFIG` is the effective source; `mygroup`/`mystream`/`worker` are dead builder defaults (`DLQProperties`/`DLQParameters`) |
| Consumer | `consumer-1` (resolved, same source) |
| Defaults | `minIdle=100` ms, `count=100`, `maxDeliver=2` (from `DLQConfigService`) |

### Samples contract (all 6 identical in behavior)

stdin/args: none (constants matching the table above; connection `redis://localhost:6379`,
overridable via `REDIS_URL` env var). Behavior: connect → `FCALL` once → pretty-print the two
result arrays → exit 0. No retry loops, no framework, ≤ ~60 lines each, top-of-file comment
linking the blog post and stating the Redis 8.8+ / `setup.sh` prerequisite.

### index.md section plan (order is normative)

1. Series intro (1 §, links repo)
2. The problem — poison message, unbounded redelivery, what a DLQ guarantees
3. The pattern on Redis Streams — diagram + narrative (producer → stream → group → deliveries
   counter → DLQ), guarantees called out for architects
4. The core logic in pseudo-code (mirrors the 4 Lua steps)
5. Sidebar — Redis Functions: why vs `EVAL`/`EVALSHA`/`SCRIPT LOAD` (named API, server-persisted
   & replicated library, loaded once, no SHA bookkeeping), how to load:
   `redis-cli -x FUNCTION LOAD REPLACE < lua/stream_utils.lua`
6. Reproduce it in 5 minutes — `redis-cli`/RedisInsight walkthrough: docker run redis:8.8 →
   setup.sh (or its commands spelled out) → `XADD` a good + a poison message → `FCALL` →
   don't-ACK to simulate failure → wait `minIdle` → `FCALL` again (×`maxDeliver`) → show
   `XRANGE test-stream:dlq` + `XPENDING` now clean
7. Explicit failure with XNACK (Redis 8.8) — the timeout walkthrough's counterpart: `FAIL`
   (released immediately, budget kept), `FATAL` (counter → max, swept to the DLQ by the very next
   `FCALL`, no idle wait), `SILENT` (budget refunded — graceful shutdown story); one short CLI
   block, released state shown via `XPENDING` (unowned, `idle = -1`). Facts per ADR-0011.
8. Call it from your language — 6 links (pinned permalinks) + the single Jedis inline excerpt
9. See it live & what's next — repo, `./launch-docker.sh --build`, the `/dlq` page, series teaser

## Behavior & edge cases

The post/reproduction must get these right (they are where readers get confused):

- **A message never delivered cannot go to the DLQ**: DLQ routing reads `XPENDING`, so only
  messages already read at least once, idle ≥ `minIdle`, with `deliveries >= maxDeliver` are moved.
  The walkthrough must therefore show at least one failed `FCALL`-then-no-`XACK` cycle per
  delivery increment.
- **Confirmed empirically (2026-07-09, `redis:8.8-alpine`)**: `XREADGROUP ... CLAIM` increments the
  delivery counter, and the DLQ check reads `XPENDING` *before* the re-read. With `maxDeliver=2`:
  FCALL #1 delivers (deliveries=1), FCALL #2 re-delivers (deliveries=2), FCALL #3 sweeps to the
  DLQ and delivers nothing. **Normative for the post**: present it as "the message is delivered
  `maxDeliver` times; the *next* poll sweeps it to the DLQ" (`maxDeliver`+1 calls, each ≥ `minIdle`
  apart) — never as "after N failures it goes to the DLQ" without the sweep nuance. Test plan #2
  re-proves this on the published walkthrough.
- **Both result arrays may be empty** — samples must print that gracefully, not crash.
- **Reply shape varies by client/RESP version**: nested arrays (RESP2) vs maps/typed values
  (RESP3). Each sample parses defensively and each client's `FCALL` API is verified against
  current docs via **Context7** (Jedis, redis-py, node-redis, go-redis v9, NRedisStack, rust
  `redis` crate) — training memory is not a source.
- **Idempotent setup**: `XGROUP CREATE` on an existing group → `BUSYGROUP`; `setup.sh` must
  tolerate it (`|| true` with a comment, or pre-check). `FUNCTION LOAD REPLACE` (not bare `LOAD`)
  so reruns work.
- **Redis < 8.8**: `XNACK` fails (and `XREADGROUP ... CLAIM` needs 8.4+) → the post states the
  8.8+ requirement up front and the walkthrough pins `redis:8.8-alpine`.
- **XNACK exception to the sweep timing**: released messages bypass the `minIdle` wait — the post's
  timeout narrative ("each call ≥ minIdle apart") applies to the implicit-failure path only; the
  XNACK section must call this contrast out explicitly (it is the point of the section).
- **Permalinks 404 until the tag is pushed**: acceptance checks path-existence locally; the author
  pushes `blog-dlq-v1` from the host at publication (never from the VM).

### Coherence-audit step (blocking, before writing the post)

Compare, and report discrepancies with proposed fixes: `lua/stream_utils.lua` (function 1) ↔
`docs/specs/dlq.md` (incl. its "Inferred — verify" items: group/consumer naming, `mystream` vs
`test-stream`) ↔ `/dlq` page texts (`frontend/src/app/components/dlq/dlq.component.ts`,
`diagram-definitions` service) ↔ `DLQController`/`DLQMessagingService`/`DLQConfigService` ↔
`README.md` DLQ mentions. **Gate: any change to demo code or existing docs requires the author's
explicit validation first** (explain why, wait for approval). The blog post adapts to the code,
not the other way around, unless the author decides otherwise.

## Out of scope

- The French version; the other 11 patterns; a series-overview post.
- CMS submission mechanics (front-matter, upload) — the deliverable is portable markdown + PNG.
- Any change to demo behavior, UI, or WebSocket layer (audit-validated fixes excepted).
- Production hardening (HA, cluster, ACL, TLS), performance tuning, benchmark claims.
- Automated tests for the demo's Java/Angular code (unchanged scope, see `docs/TODO.md`).
- CI link-checking of the pinned permalinks post-tag (nice-to-have, note in TODO if skipped).

## Test plan

Environment: `docker run -d --name blog-dlq-redis -p 6379:6379 redis:8.8-alpine` (fresh; flush
between scenarios). Toolchains: JDK21+Maven, Node 24, uv (present) + Go, .NET SDK, Rust
(provisioned in the VM — **requires a session restart before implementation**; if any is missing,
stop and tell the author, per global standards).

1. `shellcheck samples/setup.sh` → 0 findings; run twice → both exit 0 (idempotence).
2. Scripted walkthrough replay: extract every ```-fenced `redis-cli` block from `index.md`, run in
   order, assert the stated observations (poison message in `test-stream:dlq`, main-stream
   `XPENDING` empty at the end). This is the proof for the "reproduce" acceptance box.
3. Run all 6 samples with their documented one-liners against a state where one DLQ routing is
   pending → each exits 0 and prints ≥1 message-to-process and/or the `[orig_id, dlq_id]` pair;
   also run against an empty stream → graceful empty output.
4. Word count: `sed`-strip fenced blocks from `index.md` → `wc -w` within 1500–1800.
5. Link check: every `github.com/...` URL contains `/blog-dlq-v1/`; every repo path referenced
   exists locally (`git ls-files` match).
6. Forbidden-tech grep (see acceptance) + `luacheck` unchanged + `mvn test` still green (proves the
   audit didn't break the demo).
7. Visual: render `index.md` (any markdown preview) → diagram displays, alt text present.

## Dependencies & risks

- **Client libraries** (samples) — resolve latest stable + exact `FCALL` API via **Context7 at
  implementation time**: Jedis (repo pins 7.1.0 — samples align with the repo), redis-py,
  node-redis, go-redis v9, NRedisStack, Rust `redis` crate.
- **Toolchains**: Go / .NET / Rust in the VM only after the author restarts the session — sequence
  implementation accordingly (samples last, or restart first).
- **Riskiest**: (1) FCALL reply parsing across 6 clients (RESP2/RESP3 shapes) — mitigated by
  actually running each sample; (2) the delivery-count-vs-call-count off-by-one — **resolved
  empirically** (see Behavior & edge cases; project docs/diagrams aligned on 2026-07-09), test
  plan #2 guards regressions; (3) audit discovering demo/doc drift — mitigated by the validation
  gate, which may pause the slice.

## Next step

Run `/plan-feature blog-dlq-post` to break this into TDD-ordered steps (audit → setup.sh →
walkthrough → samples → diagram → prose → verification).
