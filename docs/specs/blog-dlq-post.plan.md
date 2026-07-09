# Plan — blog-dlq-post (test-first)

> Spec: `docs/specs/blog-dlq-post.md`. Branch: `blog/dlq-post`.
> **Session gate**: Go / .NET / Rust toolchains are provisioned in the VM but NOT visible in the
> session that wrote this plan — tasks 6–8 require a restarted Claude session (`go`, `dotnet`,
> `cargo` on PATH). Tasks 0–5 run now. Verified 2026-07-09: `go dotnet cargo rustc` → MISSING;
> `mvn node uv redis-cli docker shellcheck` → OK.

## Verified client APIs (Context7, 2026-07-09 — do not re-guess)

| Client | Version | FCALL call | Notes |
|---|---|---|---|
| Jedis (Java) | 7.1.0 (repo pin) | `jedis.fcall("read_claim_or_dlq", keys, args)` | same API the repo's `DLQMessagingService` uses |
| redis-py | 8.0.1 | `r.fcall("read_claim_or_dlq", 2, k1, k2, a1..a5)` | `decode_responses=True`; nested lists |
| node-redis | 6.1.0 | `client.fCall('read_claim_or_dlq', { keys: [k1,k2], arguments: [a1..a5] })` | all arguments must be strings; reply = nested string arrays |
| go-redis | v9.21.0 | `rdb.FCall(ctx, "read_claim_or_dlq", []string{k1,k2}, a1..a5).Result()` | reply `interface{}` → type-assert `[]interface{}` defensively |
| NRedisStack + SE.Redis | 1.6.0 / 3.0.11 | **no typed helper** → `db.Execute("FCALL", "read_claim_or_dlq", 2, k1, k2, a1..a5)` | cast `(RedisResult[])` down each level; least-documented path — run it early |
| redis crate (Rust) | 1.3.0 | **no typed helper** → `redis::cmd("FCALL").arg("read_claim_or_dlq").arg(2).arg(...)` | `query::<(Vec<(String, Vec<String>)>, Vec<(String,String)>)>` or match raw `Value` |

All clients: a Lua `nil`/`false` return becomes RESP Nil — guard before indexing; empty Lua tables
arrive as empty arrays (safe).

## Empirical ground truth (already proven, 2026-07-09, redis:8.4-alpine)

`maxDeliver=2`: FCALL #1 delivers (deliveries=1) → FCALL #2 re-delivers via `CLAIM` (deliveries=2)
→ FCALL #3 sweeps to DLQ, delivers nothing, returns `[orig_id, dlq_id]`, PEL clean. Calls must be
≥ `minIdle` apart. This wording is **normative** for the post (spec, Behavior section).

## The test harness (how everything is verified)

One command: `./blog/dlq-redis-streams/verify.sh`
— spins its own throwaway `redis:8.4-alpine` (container `blog-dlq-verify`, port **6399**), runs
every check below, prints PASS/FAIL per check, exits non-zero on any FAIL, always removes the
container. Checks map 1:1 to the spec's acceptance boxes:

| Check | Asserts |
|---|---|
| `chk_shellcheck` | `shellcheck samples/setup.sh` (and `verify.sh` itself) clean |
| `chk_setup` | `setup.sh` twice → exit 0 both; `FUNCTION LIST LIBRARYNAME stream_utils` non-empty; `XINFO GROUPS test-stream` shows the group |
| `chk_walkthrough` | extracts fenced ` ```bash ` blocks from `index.md` between `<!-- verify:begin -->`/`<!-- verify:end -->` markers, runs them in order, then asserts: `XLEN test-stream:dlq` == 1, `XPENDING test-stream <group>` empty |
| `chk_sample_<lang>` ×6 | documented one-liner exits 0; stdout contains a `[orig_id → dlq_id]` line when a sweep is pending; graceful `no messages` on empty state |
| `chk_wordcount` | prose of `index.md` (fenced blocks stripped) in **1200–1500** words |
| `chk_links` | every `github.com/...RedisMessagingPatternsWithJedis` URL contains `/blog-dlq-v1/`; every referenced repo path exists in `git ls-files` |
| `chk_forbidden` | no `websocket|sockjs|angular|spring` in prose (closing "see it live" section exempt via marker) |
| `chk_img` | `img/dlq-flow.png` exists, referenced with relative path + non-empty alt text |

Done for the whole feature = `verify.sh` all-PASS **plus** `luacheck lua/` 0 errors **plus**
`mvn test` green (unchanged demo proof).

## Ordered tasks (red → green → refactor)

**Task 0 — finish the coherence audit (spec gate, research not TDD)**
Resolve the name ambiguities the spec's table left open: read
`src/main/java/com/redis/patterns/controller/DLQController.java`,
`service/DLQMessagingService.java`, `config/DLQConfigService.java` and the frontend calls
(`dlq-actions`, `dlq-config`, `stream-viewer` usage: `dlq-group`/`dlq-consumer` appear for the DLQ
stream view) to pin the real group/consumer names (`test-group` vs `mygroup`, `consumer-1` vs
`worker`) and whether `mystream` is reachable. Update the names table in
`docs/specs/blog-dlq-post.md` + fix `docs/specs/dlq.md`'s "Inferred — verify" note.
**Gate**: if any demo-code change looks needed → stop, explain, wait for author validation.
Output: short audit report in the conversation.

**Task 1 — RED: write `blog/dlq-redis-streams/verify.sh`**
Create the harness with all checks above. Run it: every check FAILs (nothing exists yet) — that
failure list is the feature's to-do. `shellcheck verify.sh` must pass immediately.
Files: create `blog/dlq-redis-streams/verify.sh`.

**Task 2 — GREEN `chk_setup`/`chk_shellcheck`: write `samples/setup.sh`**
`REDIS_URL`-aware (default `redis://localhost:6399` override via env / `-p` port arg),
`FUNCTION LOAD REPLACE < lua/stream_utils.lua` (path resolved from repo root),
`XGROUP CREATE test-stream <group> $ MKSTREAM` tolerating `BUSYGROUP`. Re-run verify.sh:
`chk_setup` PASS, rest still red.
Files: create `blog/dlq-redis-streams/samples/setup.sh`.

**Task 3 — GREEN `chk_walkthrough`: write the reproduction section of `index.md` first**
Author ONLY section 6 (Reproduce it in 5 minutes) of `index.md`, commands wrapped in the
`<!-- verify:begin/end -->` markers, mirroring the proven 3-FCALL sequence (with `sleep 0.3`
between calls, `XPENDING` shown after each, expected outputs as comments). verify.sh replays it
green. This locks the post's core claim to executable truth before any prose styling.
Files: create `blog/dlq-redis-streams/index.md` (skeleton headers + section 6 only).

**Task 4 — GREEN `chk_sample_java|python|node` (3 × red→green)**
For each language, in this order (toolchains present now):
red = one-liner in verify.sh matrix fails → green = write the mini-project using the verified API
above → refactor = hoist shared constants into a comment header template shared by all samples.
Files: create `samples/java/pom.xml` + `src/main/java/DlqExample.java`,
`samples/python/pyproject.toml` + `dlq_example.py`, `samples/node/package.json` +
`dlq-example.mjs`.
Each sample: connect (env `REDIS_URL`, default localhost:6379), one FCALL, print both arrays,
nil-guard, exit 0. ≤ ~60 lines.

**Task 5 — GREEN `chk_img`: the logical diagram**
Produce `img/dlq-flow.excalidraw` + exported `img/dlq-flow.png` (use the
`redis-excalidraw-diagrams` skill): producer → `test-stream` → consumer group (deliveries
counter) → worker fails ×maxDeliveries → **next-poll sweep** → `test-stream:dlq`. The sweep arrow
must be visually distinct (it is the post's thesis).
Files: create `blog/dlq-redis-streams/img/dlq-flow.{excalidraw,png}`.

——— SESSION RESTART REQUIRED HERE (Go / .NET / Rust on PATH) ———

**Task 6 — GREEN `chk_sample_go`** — `samples/go/go.mod` + `main.go` (go-redis v9.21.0, `FCall`).
**Task 7 — GREEN `chk_sample_csharp`** — `samples/csharp/DlqExample.csproj` + `Program.cs`
(SE.Redis `db.Execute` — the least-documented client: run it before writing prose that mentions it).
**Task 8 — GREEN `chk_sample_rust`** — `samples/rust/Cargo.toml` + `src/main.rs`
(`redis::cmd("FCALL")`, typed tuple query).

**Task 9 — GREEN `chk_wordcount`/`chk_links`/`chk_forbidden`: full prose of `index.md`**
Write sections 1–5, 7, 8 around the already-verified section 6, per the spec's normative section
plan: series intro ¶ → problem → pattern+diagram (guarantees for architects) → pseudo-code
(mirrors the 4 Lua steps 1:1) → Redis Functions sidebar (why vs EVAL/EVALSHA/SCRIPT LOAD; the
`FUNCTION LOAD REPLACE` one-liner) → §7 language links (6 pinned permalinks + ONE inline Jedis
excerpt 5–15 lines from `DLQMessagingService` + its permalink) → §8 see-it-live. Sweep semantics
phrased exactly as the spec's normative wording. Redis 8.4+ caveat before the first command.
Refactor pass: cut to 1200–1500 words, tighten.
Files: modify `blog/dlq-redis-streams/index.md`.

**Task 10 — full gate + ship**
Fresh `verify.sh` end-to-end (all PASS) → `luacheck lua/` → `mvn test` → `/ship` skill (lint,
build, secret scan, docs check) → update `CLAUDE.md` layout section + `docs/TODO.md` (note: CI
link-check post-tag not automated) → propose the commit(s); author publishes tag `blog-dlq-v1`
from the host at publication time.

## Files summary

Create: everything under `blog/dlq-redis-streams/` (verify.sh, index.md, img/×2, samples/setup.sh,
samples/{java,python,node,go,csharp,rust}/*).
Modify: `docs/specs/blog-dlq-post.md` (names table, task 0), `docs/specs/dlq.md` (inferred note),
`CLAUDE.md` + `docs/TODO.md` (task 10). Nothing else — demo code changes are gated on author
validation.

## Riskiest step & de-risk

**Task 7 (C#)**: only client with no typed FCALL helper *and* no Context7-citable doc for
`db.Execute` — de-risked by running it against real Redis before the prose references it, and by
the RESP-Nil guard. Second risk: the walkthrough drifting from real behavior — de-risked by
task 3 making the walkthrough machine-replayed from day one, before any prose exists.

## How to run

```bash
./blog/dlq-redis-streams/verify.sh          # the whole acceptance suite (own container, port 6399)
luacheck lua/ --globals redis cjson cmsgpack bit
mvn test                                    # demo unchanged
```

Done = all three green, spec acceptance boxes checkable one by one against verify.sh output.
