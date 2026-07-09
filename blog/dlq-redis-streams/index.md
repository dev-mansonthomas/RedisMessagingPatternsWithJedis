# Dead Letter Queues on Redis Streams — bounded retries, poison messages, and the new XNACK

## A series on messaging patterns, built on Redis

Redis is usually introduced as a cache, but its data structures make it a capable messaging
substrate in its own right. This post opens a series that implements the classic enterprise
messaging patterns — dead letter queues, request/reply, work queues, fan-out, topic routing, and
more — directly on Redis, with runnable code you can clone and step through. Every pattern lives in
a single companion project, the
[Redis Messaging Patterns repository](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis),
where each one gets its own page that visualizes the data flow in real time. We start with the
pattern every durable consumer eventually reaches for: the **Dead Letter Queue (DLQ)**.

## The problem: one poison message shouldn't wedge the queue

A consumer reads a message, tries to process it, and fails. Maybe the payload is malformed, a
downstream service is down, or the record trips a bug. With naive at-least-once delivery the message
is simply redelivered — and fails again, and again. A single **poison message** can pin a partition,
burn CPU on an endless retry loop, and hide healthy traffic queued up behind it.

The Dead Letter Queue pattern bounds this. Give every message a finite retry budget; once that
budget is exhausted, move the message out of the main flow into a separate *dead-letter* stream,
where it can be inspected, alerted on, or replayed later — without ever blocking the live consumers.
Done right, a DLQ gives you four properties worth stating explicitly for anyone designing on top of
it:

- **At-least-once delivery** — a message is retried until it succeeds or is explicitly dead-lettered;
  it is never silently dropped.
- **Bounded retries** — the number of attempts is capped by a `maxDeliveries` budget, so a poison
  message cannot loop forever.
- **No message loss** — routing to the DLQ is atomic with acknowledging the original, so a message
  is never in both streams at once, nor in neither.
- **Isolation** — poison messages leave the hot path, so one bad record can't starve the rest.

## The pattern on Redis Streams

Redis Streams supply the primitives for all four properties. A **stream** is an append-only log; a
**consumer group** tracks, per message, which consumer currently holds it and how many times it has
been delivered. When a consumer reads with `XREADGROUP`, the message enters the group's *Pending
Entries List* (PEL) and its delivery counter starts at 1. Acknowledge it with `XACK` and it leaves
the PEL; crash before acknowledging and it stays pending, claimable by another consumer once it has
been idle long enough.

![Logical flow of the DLQ pattern on Redis Streams: producer, consumer group with delivery counter, next-poll sweep to the DLQ stream](img/dlq-flow.png)

That delivery counter *is* the retry budget. On each poll we ask Redis for the pending messages that
have sat idle past a `minIdle` threshold (`XPENDING ... IDLE minIdle`), read their delivery counts,
and sweep any that have reached `maxDeliveries` into the DLQ stream. Everything else — brand-new
messages plus still-eligible pending ones — is claimed and handed back for processing in a single
atomic step. Because the entire decision runs server-side, two consumers can never disagree about
whether a message is poison or which of them owns it. (`read_claim_or_dlq` needs **Redis 8.4+** for
the atomic claim; the explicit-failure `XNACK` path shown later needs **Redis 8.8+**.)

## The core logic, in four steps

The repository packages this decision as a Redis Function called `read_claim_or_dlq`. Given the main
stream and the DLQ stream as keys, plus the group, consumer, idle threshold, batch size, and retry
budget as arguments, it does exactly four things:

```text
read_claim_or_dlq(stream, dlq; group, consumer, minIdle, count, maxDeliver):
  1. pending ← XPENDING stream group IDLE minIdle - + count
     keep the entries whose delivery count ≥ maxDeliver
  2. for each such entry:                                 # atomic hand-off
        XCLAIM it → XADD a copy into dlq → XACK it on stream
  3. claimed ← XREADGROUP group consumer COUNT count
                 CLAIM minIdle STREAMS stream >            # pending + new, one shot
  4. return [ messages_to_process, dlq_ids ]
```

Two subtleties decide whether your mental model is right. First, the condition is
`deliveries >= maxDeliver`, and the DLQ check in step 1 runs *before* the re-read in step 3 — so it
only ever sees delivery counts from previous calls. Second, `XREADGROUP ... CLAIM` increments the
delivery counter as it re-delivers. Put together: a poison message is delivered `maxDeliver` times,
and the *next* poll sweeps it to the DLQ. With `maxDeliver = 2` that is three calls — deliver,
re-deliver, sweep — each at least `minIdle` apart. It is never "after N failures it instantly
vanishes"; there is always that one extra sweeping poll, and the walkthrough below makes it visible.

## Why a Redis Function, not `EVAL`

We could ship this as a Lua script run with `EVAL`, but **Redis Functions** (Redis 7.0+) are a
better fit for logic that is genuinely part of your data model. A function is registered once inside
a named **library**, persisted in the RDB/AOF, and replicated to replicas — so it survives restarts
and failovers and is callable by name with `FCALL`, from any client, with no per-call script upload.
Contrast that with `EVAL` / `EVALSHA` / `SCRIPT LOAD`, where the client owns the script text, tracks
SHA digests, and has to handle `NOSCRIPT` by re-uploading after a failover or a flushed script cache.
Functions move all of that bookkeeping into the server. Loading the whole library is one command:

```bash
redis-cli -x FUNCTION LOAD REPLACE < lua/stream_utils.lua
```

`REPLACE` makes it idempotent — pushing a new version of the library never fails with "already
exists" — which is exactly what you want in a deploy step.

## Reproduce it in 5 minutes

Everything below runs against a vanilla **Redis 8.8+** with nothing but `redis-cli` — no
application code involved. Start a throwaway server and grab the demo repository (we only need one
Lua file from it):

```bash
docker run -d --name dlq-demo -p 6379:6379 redis:8.8-alpine
git clone https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis.git
cd RedisMessagingPatternsWithJedis
```

Load the functions library and create the consumer group (or run
[`samples/setup.sh`](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/setup.sh),
which does exactly this):

<!-- verify:begin -->
```bash
./blog/dlq-redis-streams/samples/setup.sh
```

Produce a well-behaved message and a poison message, then poll with the function. Both come back
for processing — first delivery, `deliveries = 1`:

```bash
GOOD=$(redis-cli XADD test-stream '*' type order.created order_id 1001 amount 49.90)
POISON=$(redis-cli XADD test-stream '*' type order.poison order_id 666 amount 0.00)

redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
```

Ack the good one; for the poison one, do what a crashed worker does — nothing:

```bash
redis-cli XACK test-stream test-group "$GOOD"
```

Now wait past `minIdle` (100 ms here) and poll again. The poison message is re-delivered —
`deliveries` climbs to 2, which is our `maxDeliveries` budget:

```bash
sleep 0.3
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XPENDING test-stream test-group
# → 1 pending entry, delivery count: 2
```

One more idle period, one more poll — and this is the sweep: the function returns **no message to
process**, just the pair `[original_id, dlq_id]`. The poison message now lives in the DLQ stream,
and the pending list is clean:

```bash
sleep 0.3
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XRANGE test-stream:dlq - +
redis-cli XPENDING test-stream test-group
# → total pending: 0
```

Note the timing: the message was **delivered `maxDeliveries` (2) times, and the *next* poll swept
it**. The DLQ check runs before the re-read, so it only sees delivery counts from previous calls —
`maxDeliveries + 1` polls in total, each at least `minIdle` apart.
<!-- verify:end -->

## Explicit failure with XNACK (Redis 8.8)

So far a consumer signals failure *implicitly* — by not acknowledging and letting the message time
out. That is robust (a crashed consumer looks exactly like a slow one) but slow: every retry has to
wait out `minIdle`. Redis 8.8 adds `XNACK`, an explicit "I'm handing this back" that a live consumer
can send the instant it knows the outcome, in three flavors that map cleanly onto real failure
modes:

- **`FAIL`** — "I tried and it failed": release the message immediately, keep the delivery count.
  It is re-claimable right away, with no idle wait.
- **`FATAL`** — "this is poison": force the delivery count to its maximum, so the very next poll
  sweeps the message straight to the DLQ.
- **`SILENT`** — "I have to give this back but never really tried" (a graceful shutdown, say):
  refund the budget by resetting the counter to 0.

The contrast with the timeout path is the whole point of the section: a released message **bypasses
the `minIdle` wait entirely**. Watch the pending entry right after a `FAIL` — it sits in the PEL
*unowned* (no consumer, `idle = -1`), immediately available to the next claim.

<!-- verify:begin -->
```bash
MSG=$(redis-cli XADD test-stream '*' type order.created order_id 2002 amount 12.50)
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2

# "I tried and failed" — release it NOW, keep the failure on the books:
redis-cli XNACK test-stream test-group FAIL IDS 1 "$MSG"
redis-cli XPENDING test-stream test-group - + 10
# → entry has NO consumer, idle = -1, deliveries kept at 1
```

No `sleep` this time — a released message is immediately re-claimable:

```bash
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
# → re-delivered instantly (deliveries: 2)
```

Or skip the retries entirely. `FATAL` burns the whole failure budget (the counter jumps to the
maximum), so the very next poll sweeps the message to the DLQ — again with no waiting:

```bash
redis-cli XNACK test-stream test-group FATAL IDS 1 "$MSG"
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XRANGE test-stream:dlq - +
# → two entries now: the timeout-swept poison + this one
```

Finally `SILENT`, for the consumer that must give work back *without* having tried — a graceful
shutdown, for instance. The delivery counter is reset to 0: the failure budget is refunded:

```bash
MSG2=$(redis-cli XADD test-stream '*' type order.created order_id 3003 amount 5.00)
redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XNACK test-stream test-group SILENT IDS 1 "$MSG2"
redis-cli XPENDING test-stream test-group - + 10
# → deliveries: 0 — as if the delivery never happened

redis-cli FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 100 100 2
redis-cli XACK test-stream test-group "$MSG2"
```
<!-- verify:end -->

## Call it from your language

`FCALL` is just a Redis command, so every mainstream client can invoke `read_claim_or_dlq`. The
reply is always the same `[messages_to_process, dlq_ids]` pair of nested arrays — which each sample
parses defensively, since both arrays may be empty. The repository ships one minimal, runnable
sample per language, each around forty lines, each reading the target from `REDIS_URL` and printing
the messages to process alongside any `[original_id, dlq_id]` routing pairs:

- [Java — Jedis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/java/src/main/java/DlqExample.java)
- [Python — redis-py](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/python/dlq_example.py)
- [Node.js — node-redis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/node/dlq-example.mjs)
- [Go — go-redis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/go/main.go)
- [.NET — NRedisStack](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/csharp/Program.cs)
- [Rust — redis](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/blog/dlq-redis-streams/samples/rust/src/main.rs)

Here is the call itself, lifted from the demo's `DLQMessagingService` — two keys, five arguments,
one round trip:

```java
Object result = jedis.fcall(
    FUNCTION_NAME,
    Arrays.asList(params.getStreamName(), params.getDlqStreamName()),
    Arrays.asList(
        params.getConsumerGroup(),
        params.getConsumerName(),
        String.valueOf(params.getMinIdleMs()),
        String.valueOf(params.getCount()),
        String.valueOf(params.getMaxDeliveries())
    )
);
```

The result parsing that follows it is the only real work, and it is the same shape in every sample.
See the [full method](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/blob/blog-dlq-v1/src/main/java/com/redis/patterns/service/DLQMessagingService.java#L153-L170)
for how the two arrays are unpacked.

<!-- forbidden-exempt:begin -->
## See it live, and what's next

The walkthrough above is one page of a larger demo. Clone the
[repository](https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis) and run
`./launch-docker.sh --build`; the `/dlq` page lets you add good and poison messages, poll the
function, and watch entries march through the delivery counter into the DLQ in real time — the very
same `read_claim_or_dlq` you just called by hand. The demo happens to be a Spring Boot backend with
an Angular frontend that streams updates over WebSocket, but none of that is load-bearing for the
pattern: the entire DLQ lives in the Lua function and the two streams, which is exactly why it ports
to six languages in forty lines each. Next in the series: **request/reply** with correlation IDs and
keyspace-expiry timeouts. Same streams, a very different guarantee.
<!-- forbidden-exempt:end -->
