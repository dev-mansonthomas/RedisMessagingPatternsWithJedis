# PRD — Redis Messaging Patterns

> Reconstructed from code + git history (no original PRD existed). Items tagged
> **(inferred — verify)** are deductions, not author statements.

## Problem

Engineers evaluating Redis as a messaging substrate need to *see* how classic enterprise
messaging patterns (DLQ, pub/sub, request/reply, work queues, routing, scheduling, rate
limiting) map onto Redis primitives — and how they behave under failure and concurrency.
Reading docs is abstract; this project makes each pattern **interactive and observable**.

## Users (inferred — verify)

- **Redis Solution Architects / SEs** demoing messaging capabilities to customers.
- **Backend engineers** learning Redis Streams, Consumer Groups, Functions, and Pub/Sub.
- **Workshop/training audiences** who interact with a UI rather than `redis-cli`.

## Goals

1. Implement a representative catalogue of messaging patterns on Redis (11 today).
2. Visualize each pattern's data flow **in real time** (streams filling/draining, routing decisions).
3. Keep each pattern **self-contained and inspectable** — one page, named Redis keys, a flow diagram.
4. Run with one command (`./launch-docker.sh`) against Redis 8.8+.

## Non-goals

- Production hardening (no auth, open CORS, in-memory config, no persistence guarantees beyond AOF).
- Being a reusable library/framework — it is a demonstrator.
- Exhaustive parity with a broker (RabbitMQ/Kafka); see `augmentcode/feature-comparison.md` for the
  RabbitMQ-vs-Redis gap analysis and `docs/migration-status.md` for roadmap status.

## Scope — implemented patterns

DLQ · Pub/Sub · Request/Reply · Work Queue · Fan-Out · Stream Topic Routing · Pub/Sub Topic
Routing · Content-Based Routing · Scheduled Messages · Per-Key Serialized · Token Bucket.
Per-pattern contracts live in `docs/specs/`.

## Acceptance criteria (product-level)

- Each pattern page loads, accepts input, and shows the resulting Redis activity live.
- DLQ routing occurs after `maxDeliveries` failed attempts; ACK'd messages disappear from the view.
- Request/Reply resolves on response **or** times out via keyspace expiry.
- Token Bucket never exceeds the configured concurrency per job type.
- Per-Key Serialized never processes two jobs for the same key concurrently.
- `./launch-docker.sh --build` brings up frontend, backend, Redis, and RedisInsight.

## Success signals (inferred — verify)

Used successfully in live demos/workshops; a newcomer can run it and understand a pattern
without reading backend code.
