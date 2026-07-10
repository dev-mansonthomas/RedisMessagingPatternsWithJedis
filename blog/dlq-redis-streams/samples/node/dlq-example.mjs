/*
Dead Letter Queue on Redis Streams — call read_claim_or_dlq and print its reply.
This sample assumes no prior Redis knowledge.

QUICKSTART — paste the indented lines into a terminal (needs Docker + Node.js):

    git clone https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis.git
    cd RedisMessagingPatternsWithJedis
    export REDIS_URL="redis://localhost:$(./blog/dlq-redis-streams/samples/setup.sh)"
    cd blog/dlq-redis-streams/samples/node && npm install && node dlq-example.mjs

setup.sh loads the Lua function and, if nothing is already listening on
localhost:6379, starts a throwaway Redis 8.8 in Docker on a free port and prints
that port — captured above into REDIS_URL. Blog post & the other five languages:
https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams

A 60-second primer
  - A Redis *stream* is an append-only log; each entry = an ID ("1699-0") plus a
    flat list of field/value pairs (["type","order.created","order_id","1001"]).
  - A *consumer group* tracks, per entry, which consumer holds it and how many
    times it was delivered — that delivery count is the DLQ pattern's retry budget.
  - We don't send the raw stream commands; we FCALL one server-side Lua function,
    read_claim_or_dlq (lua/stream_utils.lua), which Redis runs atomically for us.

What read_claim_or_dlq returns (maps 1:1 to the Lua "return { a, b }")
  The reply is a 2-element array:
    result[0] = messages to process -> each is [ id, [f1, v1, f2, v2, ...] ]
                                       (that flat list is what XREADGROUP returns)
    result[1] = dlq_ids             -> each is [ original_id, new_dlq_id ]: the entry
                                       left test-stream and now lives in test-stream:dlq
  Either list may be empty. node-redis maps the nested Lua tables to plain nested
  JS arrays, so we destructure them positionally.
*/
import { createClient } from 'redis';

// 1) Connect to the server (URL from REDIS_URL, or localhost by default).
const client = createClient({ url: process.env.REDIS_URL ?? 'redis://localhost:6379' });
await client.connect();

// 2) Call the function. fCall(name, { keys, arguments }) maps to the wire form
//    FCALL <name> <numkeys> <keys...> <args...>; node-redis derives numkeys from
//    keys.length.
//      keys      = stream, dlq_stream
//      arguments = group, consumer, minIdle(ms), count, maxDeliver  (demo defaults)
const result = await client.fCall('read_claim_or_dlq', {
  keys: ['test-stream', 'test-stream:dlq'],
  arguments: ['test-group', 'consumer-1', '100', '100', '2'],
});

// 3) Split the outer pair; fall back to two empty arrays if the reply is null.
const [toProcess, dlqIds] = result ?? [[], []];

// 4) Entries to handle now. Each is [id, flat] where flat = [f1, v1, f2, v2, ...];
//    step two at a time to rebuild a { field: value } object.
for (const [id, flat] of toProcess) {
  const fields = {};
  for (let i = 0; i + 1 < flat.length; i += 2) fields[flat[i]] = flat[i + 1];
  console.log(`TO_PROCESS ${id} ${JSON.stringify(fields)}`);
}

// 5) Entries the function just moved to the dead-letter stream: [original_id, new_dlq_id].
for (const [originalId, dlqId] of dlqIds) {
  console.log(`DLQ ${originalId} -> ${dlqId}`);
}

// 6) Nothing to process and nothing dead-lettered on this poll.
if (toProcess.length === 0 && dlqIds.length === 0) {
  console.log('no messages to process');
}

await client.quit();
