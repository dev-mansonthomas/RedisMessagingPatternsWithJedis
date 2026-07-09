// Call the read_claim_or_dlq Redis Function (DLQ pattern) once and print the result.
//
// Blog post: https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams
// Prereq: Redis 8.8+ with samples/setup.sh applied. Target override: REDIS_URL env var.
// Run: npm install && node dlq-example.mjs
import { createClient } from 'redis';

const client = createClient({ url: process.env.REDIS_URL ?? 'redis://localhost:6379' });
await client.connect();

// KEYS: stream, dlq — ARGS: group, consumer, minIdleMs, count, maxDeliveries
const result = await client.fCall('read_claim_or_dlq', {
  keys: ['test-stream', 'test-stream:dlq'],
  arguments: ['test-group', 'consumer-1', '100', '100', '2'],
});

const [toProcess, dlqIds] = result ?? [[], []];

for (const [id, flat] of toProcess) { // [id, [f1, v1, ...]]
  const fields = {};
  for (let i = 0; i + 1 < flat.length; i += 2) fields[flat[i]] = flat[i + 1];
  console.log(`TO_PROCESS ${id} ${JSON.stringify(fields)}`);
}

for (const [originalId, dlqId] of dlqIds) { // [original_id, dlq_id]
  console.log(`DLQ ${originalId} -> ${dlqId}`);
}

if (toProcess.length === 0 && dlqIds.length === 0) {
  console.log('no messages to process');
}

await client.quit();
