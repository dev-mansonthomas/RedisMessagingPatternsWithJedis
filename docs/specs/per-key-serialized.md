# Spec — Per-Key Serialized Processing

Route `/per-key-serialized` · `PerKeySerializedController` (`/api/per-key-serialized`) ·
`PerKeySerializedService`.

## Goal
Process jobs in **parallel across keys** but **strictly serial per key** (e.g. never two jobs for
the same `orderId` at once), without a global lock.

## Redis
- Stream `jobs.perkey.v1` (fields `orderId`, `action`), single group `jobs-serialized-group`.
- Per-key lock `running:order:{orderId}` = messageId, `SET NX PX 30000`.
- Per-worker done streams `jobs.perkey.v1.worker{1..3}.done`.

## REST
- `POST /per-key-serialized/submit` — `XADD` a batch of jobs.
- `DELETE /per-key-serialized/clear`.

## Flow
3 Virtual Thread workers share one group. Per job: try `SET running:order:{orderId} NX PX 30000`.
- Lock acquired → process (~4s) → copy to worker's done stream → `XACK` → release lock.
- Lock held by another → **skip** (don't block); leave message pending. `XAUTOCLAIM` (idle 500ms)
  re-delivers it later, by which time the holder has released the lock.

## Acceptance
- Two jobs with the same `orderId` never run concurrently; they serialize.
- Jobs with different `orderId`s run in parallel across the 3 workers.

## Inferred — verify
Whether the lock is explicitly `DEL`'d on success or relies on the 30s TTL.
