# ADR-0007 — Keyspace expiry notifications drive Request/Reply timeouts

- Status: Accepted (reconstructed — verify)
- Date: (inferred) commit `c2366e5` "Request/Reply implemented"

## Context

Request/Reply needs a timeout: if no response arrives within N seconds, the requester must receive
a synthetic TIMEOUT response. Client-side timers are fragile across restarts and multiple workers.

## Decision

On `request`, set an **expiring timeout key** (`SET ... EX timeout`) plus a **shadow metadata key**.
Enable `notify-keyspace-events Ex` and subscribe to `__keyevent@0__:expired`
(`KeyspaceNotificationListener`, Virtual Thread). When the timeout key expires, read the shadow key
and emit a TIMEOUT response via the `response` function. A real `response` `DEL`s the timeout key,
cancelling the event.

## Consequences

- Timeout enforcement is server-side and survives requester restarts.
- Depends on Redis keyspace notifications being enabled (the app sets this at startup).
- Expiry firing is best-effort/near-deadline, not exact — acceptable for the demo.
- Shadow key carries `businessId` + `streamResponseName` so the handler can route the timeout.
