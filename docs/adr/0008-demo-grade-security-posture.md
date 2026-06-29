# ADR-0008 — Deliberately demo-grade security posture

- Status: Accepted (reconstructed — verify), revisit before any deployment
- Date: (inferred)

## Context

This is a local educational demo. Friction (auth screens, locked-down CORS, TLS) would hurt the
teaching/demo experience and is out of scope.

## Decision

Ship with **no authentication/authorization**, **CORS `*`**, **WebSocket origins `*`**, Redis with
**no password** by default (env-overridable), and runtime config held **in memory**. These choices
are intentional for the demo and recorded here so they are not mistaken for oversights.

## Consequences

- Must **not** be exposed to untrusted networks as-is.
- A deployment story (auth, origin allow-list, Redis ACL/TLS, secret management) is a prerequisite
  for going beyond localhost — tracked in `docs/TODO.md`.
- `REDIS_PASSWORD` and pool settings are already env-driven, easing a future lockdown.
