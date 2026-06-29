# ADR-0002 — Jedis 7.1 directly, not Spring Data Redis / Lettuce

- Status: Accepted (reconstructed — verify)
- Date: (inferred) project inception — repo name is `RedisDLQJedis`

## Context

The demo's value is showing **exact Redis commands** (`XADD`, `XREADGROUP ... CLAIM`, `FCALL`,
`ZRANGEBYSCORE`). Higher-level abstractions hide those commands.

## Decision

Depend on **Jedis 7.1.0** directly with a hand-configured `JedisPool`; do not use Spring Data Redis
or Lettuce. Call Redis Functions via `jedis.fcall(...)` and run inline scripts via `EVAL`.

## Consequences

- Code reads close to `redis-cli`, which is the teaching goal.
- Manual connection management: every op borrows/returns from the pool; blocking listeners
  (Pub/Sub, keyspace) hold dedicated connections.
- No reactive client; concurrency is handled with **Java 21 Virtual Threads** instead (ADR-0003).
- Pool sizing is configurable via `application.yml` / `REDIS_POOL_*` env vars.
