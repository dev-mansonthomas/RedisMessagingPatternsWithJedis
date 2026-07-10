/*
Dead Letter Queue on Redis Streams — call read_claim_or_dlq and print its reply.
This sample assumes no prior Redis knowledge.

QUICKSTART — paste the indented lines into a terminal (needs Docker + the .NET SDK):

    git clone https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis.git
    cd RedisMessagingPatternsWithJedis
    export REDIS_URL="redis://localhost:$(./blog/dlq-redis-streams/samples/setup.sh)"
    cd blog/dlq-redis-streams/samples/csharp && dotnet run

setup.sh loads the Lua function and, if nothing is already listening on
localhost:6379, starts a throwaway Redis 8.8 in Docker on a free port and prints
that port — captured above into REDIS_URL. Blog post & the other five languages:
https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams

A 60-second primer
  - A Redis *stream* is an append-only log; each entry = an ID ("1699-0") plus a
    flat list of field/value pairs (["type","order.created","order_id","1001"]).
  - A *consumer group* tracks, per entry, which consumer holds it and how many
    times it was delivered — that delivery count is the DLQ pattern's retry budget.
  - We call one server-side Lua function, read_claim_or_dlq (lua/stream_utils.lua),
    via FCALL — Redis runs it atomically — instead of issuing the raw stream commands.

What read_claim_or_dlq returns (maps 1:1 to the Lua "return { a, b }")
  The reply is a 2-element array:
    outer[0] = messages to process -> each is [ id, [f1, v1, f2, v2, ...] ]
                                      (that flat list is what XREADGROUP returns)
    outer[1] = dlq_ids             -> each is [ original_id, new_dlq_id ]: the entry
                                      left test-stream and now lives in test-stream:dlq
  Either list may be empty. StackExchange.Redis surfaces every level as a
  RedisResult: a nested array casts to RedisResult[], a leaf casts to string.
*/
using StackExchange.Redis;

var url = Environment.GetEnvironmentVariable("REDIS_URL") ?? "redis://localhost:6379";

// 1) Connect. StackExchange.Redis wants host:port, not a redis:// URL — strip the scheme.
var endpoint = url.Replace("redis://", "").Replace("rediss://", "");
using var redis = await ConnectionMultiplexer.ConnectAsync(endpoint);
var db = redis.GetDatabase();

// 2) Call the function. db.Execute("FCALL", ...) sends the raw command
//    FCALL <name> <numkeys> <keys...> <args...>; we pass numkeys = 2 explicitly.
//      KEYS = stream, dlq_stream
//      ARGS = group, consumer, minIdle(ms), count, maxDeliver  (demo defaults)
var result = await db.ExecuteAsync("FCALL", "read_claim_or_dlq", 2,
    "test-stream", "test-stream:dlq",
    "test-group", "consumer-1", "100", "100", "2");

// 3) The reply is the 2-element array [ messages_to_process, dlq_ids ].
//    A null/short reply means there was nothing to do.
var outer = (RedisResult[]?)result;
if (outer is null || outer.Length < 2)
{
    Console.WriteLine("no messages to process");
    return;
}

var toProcess = (RedisResult[])outer[0]!; // [[id, [f1, v1, ...]], ...]
var dlqIds = (RedisResult[])outer[1]!;    // [[original_id, dlq_id], ...]

// A leaf value casts straight to string.
static string Str(RedisResult r) => (string?)r ?? "";

// 4) Entries to handle now: entry[0] = ID, entry[1] = flat [f1, v1, f2, v2, ...],
//    walked two-by-two into "field=value" pairs.
foreach (var m in toProcess)
{
    var entry = (RedisResult[])m!;
    var flat = (RedisResult[])entry[1]!;
    var fields = new List<string>();
    for (var i = 0; i + 1 < flat.Length; i += 2)
        fields.Add($"{Str(flat[i])}={Str(flat[i + 1])}");
    Console.WriteLine($"TO_PROCESS {Str(entry[0])} {string.Join(' ', fields)}");
}

// 5) Entries the function just moved to the dead-letter stream: [original_id, new_dlq_id].
foreach (var p in dlqIds)
{
    var pair = (RedisResult[])p!;
    Console.WriteLine($"DLQ {Str(pair[0])} -> {Str(pair[1])}");
}

// 6) Nothing to process and nothing dead-lettered this poll.
if (toProcess.Length == 0 && dlqIds.Length == 0)
    Console.WriteLine("no messages to process");
