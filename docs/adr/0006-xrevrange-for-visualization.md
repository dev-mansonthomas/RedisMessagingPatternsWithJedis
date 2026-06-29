# ADR-0006 — `XREVRANGE` for visualization, consumer-group reads only for processing

- Status: Accepted (reconstructed — verify)
- Date: (inferred) commit `23d5d63` (refactor away from XREADGROUP for display)

## Context

An early approach read streams with `XREADGROUP` to populate the UI. That registers the UI as a
consumer and creates PENDING entries, producing "phantom" messages and interfering with real
processing/retry accounting.

## Decision

**Display** stream contents with read-only **`XREVRANGE`** (newest-first, no group side effects).
Reserve `XREADGROUP`/Lua consumer-group reads strictly for actual message **processing**.

## Consequences

- UI is a pure observer; no phantom PENDING, no accidental delivery-count bumps.
- The live "produced/deleted" deltas come from the listener + WebSocket events, not from group reads.
- This invariant must be preserved by any new pattern page.
