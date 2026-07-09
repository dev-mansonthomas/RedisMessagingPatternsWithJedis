"""Call the read_claim_or_dlq Redis Function (DLQ pattern) once and print the result.

Blog post: https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams
Prereq: Redis 8.8+ with samples/setup.sh applied. Target override: REDIS_URL env var.
Run: uv run dlq_example.py
"""

import os

import redis

r = redis.Redis.from_url(
    os.environ.get("REDIS_URL", "redis://localhost:6379"), decode_responses=True
)

# KEYS: stream, dlq — ARGS: group, consumer, minIdleMs, count, maxDeliveries
result = r.fcall(
    "read_claim_or_dlq", 2,
    "test-stream", "test-stream:dlq",
    "test-group", "consumer-1", "100", "100", "2",
)

to_process, dlq_ids = result if result else ([], [])

for msg_id, flat_fields in to_process:  # [id, [f1, v1, ...]]
    fields = dict(zip(flat_fields[::2], flat_fields[1::2]))
    print(f"TO_PROCESS {msg_id} {fields}")

for original_id, dlq_id in dlq_ids:  # [original_id, dlq_id]
    print(f"DLQ {original_id} -> {dlq_id}")

if not to_process and not dlq_ids:
    print("no messages to process")
