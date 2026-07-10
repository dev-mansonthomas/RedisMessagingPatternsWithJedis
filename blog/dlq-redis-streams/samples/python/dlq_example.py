"""Dead Letter Queue on Redis Streams — call read_claim_or_dlq and print its reply.

This sample assumes NO prior Redis knowledge: it calls one server-side function
and walks its result, and the comments explain both.

Blog post: https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams
Prereq:    Redis 8.8+ with samples/setup.sh applied. Target override: REDIS_URL env var.
Run:       uv run dlq_example.py

────────────────────────────────────────────────────────────────────────────
A 60-second primer
────────────────────────────────────────────────────────────────────────────
• A Redis *stream* is an append-only log. Each entry has an ID like "1699-0" and
  a flat list of field/value pairs, e.g. ["type", "order.created", "order_id", "1001"].
• A *consumer group* lets several workers share one stream. For every entry it
  remembers which consumer currently holds it and how many times it has been
  delivered — that delivery count is the retry budget the DLQ pattern is built on.
• Instead of sending the raw stream commands ourselves, we call ONE function that
  lives on the server, `read_claim_or_dlq` (written in Lua — see lua/stream_utils.lua).
  `FCALL` runs it atomically on the server and hands us back its return value.

────────────────────────────────────────────────────────────────────────────
What read_claim_or_dlq returns  (this maps 1:1 onto the Lua `return { ... }`)
────────────────────────────────────────────────────────────────────────────
The Lua function ends with:   return { messages_to_process, dlq_ids }
so the reply is a 2-element list:

  result[0] = messages_to_process  → entries you should handle now.
              Each item is [ id, [f1, v1, f2, v2, ...] ].
              That inner flat list is exactly what the XREADGROUP command returns.

  result[1] = dlq_ids              → messages the function just swept to the
              dead-letter stream. Each item is [ original_id, new_dlq_id ]: the
              entry left `test-stream` and now lives in `test-stream:dlq`.

Either list may be empty (nothing to do / nothing dead-lettered). redis-py maps
the whole nested Lua table to nested Python lists, so we index it positionally.
"""

import os

import redis

# 1) Connect. decode_responses=True turns Redis' raw bytes into str, so the IDs
#    and field values below print as text instead of b"...".
r = redis.Redis.from_url(
    os.environ.get("REDIS_URL", "redis://localhost:6379"), decode_responses=True
)

# 2) Call the function. The FCALL wire form is:
#        FCALL <name> <numkeys> <key...> <arg...>
#    redis-py's fcall(name, numkeys, *keys_then_args) mirrors it exactly:
#        numkeys = 2                → the next two values are KEYS
#        KEYS = stream, dlq_stream
#        ARGV = group, consumer, minIdle(ms), count, maxDeliver
#    (the values below are the demo defaults from DLQConfigService).
result = r.fcall(
    "read_claim_or_dlq", 2,
    "test-stream", "test-stream:dlq",                # KEYS[1], KEYS[2]
    "test-group", "consumer-1", "100", "100", "2",   # ARGV[1..5]
)

# 3) Split the outer 2-element reply. The `if result else ([], [])` guards the
#    (shouldn't-happen) nil reply so unpacking never crashes.
to_process, dlq_ids = result if result else ([], [])

# 4) Messages to handle now. Each entry is [id, [f1, v1, f2, v2, ...]]; zip the
#    even indices (field names) with the odd ones (values) to rebuild a dict.
for msg_id, flat_fields in to_process:
    fields = dict(zip(flat_fields[::2], flat_fields[1::2]))
    print(f"TO_PROCESS {msg_id} {fields}")

# 5) Messages the function just moved to the DLQ. Each is [original_id, new_dlq_id].
for original_id, dlq_id in dlq_ids:
    print(f"DLQ {original_id} -> {dlq_id}")

# 6) Both lists empty — this poll found no work and dead-lettered nothing.
if not to_process and not dlq_ids:
    print("no messages to process")
