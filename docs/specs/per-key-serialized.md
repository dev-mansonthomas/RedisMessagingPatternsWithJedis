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
3 Virtual Thread workers share one group. Per job: try `SET running:order:{orderId} NX PX 30000`
(value = the messageId, used as an ownership token).
- Lock acquired → process (~4s) → copy to worker's done stream → `XACK` → release the lock via
  `FCALL release_lock` (compare-and-delete: deletes only if the lock still holds **our** token, so a
  worker can never delete a lock another worker re-acquired after a TTL expiry).
- Lock held by another → **skip** (don't block); leave message pending. `XAUTOCLAIM` (idle 10s,
  above the ~4s processing time) re-delivers it later, by which time the holder has released the lock.

## Acceptance
- Two jobs with the same `orderId` never run concurrently; they serialize.
- Jobs with different `orderId`s run in parallel across the 3 workers.
- A lock is only ever released by its owner (compare-and-delete), never by a different worker.
