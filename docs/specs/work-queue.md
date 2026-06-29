# Spec — Work Queue (Competing Consumers)

Route `/work-queue` · `WorkQueueController` (`/api/work-queue`) · `WorkQueueService` ·
Lua `read_claim_or_dlq`.

## Goal
Distribute jobs across N parallel workers sharing **one** consumer group; each job processed by
exactly one worker. Failed jobs retry then go to DLQ.

## Redis
- Input `jobs.imageProcessing.v1`, group `mygroup`(/job group), DLQ `jobs.imageProcessing.v1:dlq`.
- Per-worker done streams `jobs.done.worker-{1..4}`.

## REST
- `POST /produce?processingType=OK|Error` — `XADD` a job (`OK` succeeds, `Error` fails to trigger retry/DLQ).
- `GET /streams` — stream names for the page.

## Flow
4 Virtual Thread workers poll every 100ms via `FCALL read_claim_or_dlq` (count 1, maxDeliver 2).
`OK` jobs → copied to the worker's done stream + `XACK`. `Error` jobs → not ACK'd → reclaimed →
DLQ after 2 deliveries.

## Edge cases / acceptance
- `OK` job processed by exactly one worker, appears in that worker's done stream.
- `Error` job lands in DLQ after 2 attempts.

## Inferred — verify
Worker count constant (4) and whether it is configurable.
