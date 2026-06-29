# ADR-0005 — WebSocket (+ SockJS fallback) for real-time UI updates

- Status: Accepted (reconstructed — verify)
- Date: (inferred) early — DLQ visualization commits

## Context

Pattern pages need server-pushed updates as streams fill and drain. The backend already detects
changes via the stream listeners (ADR-0003) and needs a transport to the browser.

## Decision

Expose a raw Spring WebSocket handler at **`/api/ws/dlq-events`** with **SockJS** fallback and
`allowedOriginPatterns("*")`. Broadcast `DLQEvent`/`PubSubEvent` JSON to all sessions via
`WebSocketEventService`. The Angular client connects through `sockjs-client`.

## Consequences

- Works behind proxies that block raw WS (SockJS fallback).
- SockJS requires a `window.global = window` polyfill in `index.html`.
- Single broadcast channel for all patterns; the client filters by `streamName`/`eventType`.
- `*` origins is fine for a local demo, unacceptable for deployment (see TODO / ADR-0008).
