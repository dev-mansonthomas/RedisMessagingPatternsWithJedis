# ADR-0008 — Deliberately demo-grade security posture

- Status: Accepted (reconstructed — verify), revisit before any deployment
- Date: (inferred)

## Context

This is a local educational demo. Friction (auth screens, locked-down CORS, TLS) would hurt the
teaching/demo experience and is out of scope.

## Decision

Ship with **no authentication/authorization**, Redis with **no password** by default
(env-overridable), and runtime config held **in memory**. These choices are intentional for the demo
and recorded here so they are not mistaken for oversights.

> **Update (2026-06-29, security review):** CORS and WebSocket origins are **no longer `*`** — both
> are now locked to an explicit allow-list (`app.cors.allowed-origins`, default the local
> frontend/backend; see `CorsConfig`, `WebSocketConfig`, ADR-0005). The Mermaid diagram renderer was
> also switched to `securityLevel: 'antiscript'` (sanitizes the SVG before the `innerHTML` sink).
> The remaining deliberate demo posture is: **no auth, no TLS, in-memory config, default no Redis
> password.**

## Consequences

- Must **not** be exposed to untrusted networks as-is.
- A deployment story (auth, Redis ACL/TLS, secret management) is a prerequisite for going beyond
  localhost — tracked in `docs/TODO.md`. (Origin allow-list is already done — see the update above.)
- `REDIS_PASSWORD` and pool settings are already env-driven, easing a future lockdown.
