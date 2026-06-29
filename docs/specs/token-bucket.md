# Spec — Token Bucket (per-type concurrency cap)

Route `/token-bucket` · `TokenBucketController` (`/api/token-bucket`) · `TokenBucketService` ·
registered Lua functions `acquire_token` / `release_token` (in `stream_utils.lua`).

## Goal
Cap the number of **concurrently running** jobs per job type (a concurrency limiter / token bucket),
with live charts of running vs. completed.

## Redis
- Job stream `token-bucket.jobs.v1` (`type`, `jobId`, `submittedAt`), group `token-bucket-group`.
- Done `token-bucket.jobs.v1.done`; progress `token-bucket.jobs.v1.progress` (`status=STARTED|COMPLETED`).
- Counters `token-bucket:running:{type}`, `token-bucket:completed:{type}`.
- Config Hash `token-bucket:config` (`max:payment=3`, `max:email=2`, `max:csv=1`).
- Logs Lists `token-bucket:log:submitted`, `token-bucket:log:completed` (last 100 each).

## Job types
PAYMENT (max 3, ~4s) · EMAIL (max 2, ~4s) · CSV (max 1, ~10s).

## REST
- `POST /submit` (`type`, `count`) · `GET /config` · `PUT /config` (`type`, `maxConcurrency`)
  · `DELETE /clear` · `GET /logs` · `GET /progress`.

## Flow
18 Virtual Thread workers (6/type) poll every 10ms. Per job: `FCALL acquire_token` (KEYS=`running:{type}`,
ARGS=`max`) — if `running < max` then `INCR running` return 1 else return 0. On token: progress STARTED
→ sleep type duration → progress COMPLETED → `INCR completed` → log → `XACK` → release token via
`FCALL release_token` (`DECR`, floored at 0). No token → skip and retry. Idle messages are reclaimed
via `XAUTOCLAIM` with an idle threshold (15s) **above** the longest job, so busy workers' in-flight
messages aren't stolen and double-processed.

## Acceptance
- Running count for a type never exceeds its configured `maxConcurrency`.
- Lowering `maxConcurrency` at runtime throttles new starts.
- The running counter never goes negative (release is floored at 0).
