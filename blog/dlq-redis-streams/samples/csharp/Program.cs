// Call the read_claim_or_dlq Redis Function (DLQ pattern) once and print the result.
//
// Blog post: https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams
// Prereq: Redis 8.8+ with samples/setup.sh applied. Target override: REDIS_URL env var.
// Run: dotnet run
using StackExchange.Redis;

var url = Environment.GetEnvironmentVariable("REDIS_URL") ?? "redis://localhost:6379";
// StackExchange.Redis expects host:port, not a redis:// URL — strip the scheme.
var endpoint = url.Replace("redis://", "").Replace("rediss://", "");
using var redis = await ConnectionMultiplexer.ConnectAsync(endpoint);
var db = redis.GetDatabase();

// KEYS: stream, dlq — ARGS: group, consumer, minIdleMs, count, maxDeliveries
var result = await db.ExecuteAsync("FCALL", "read_claim_or_dlq", 2,
    "test-stream", "test-stream:dlq",
    "test-group", "consumer-1", "100", "100", "2");

var outer = (RedisResult[]?)result;
if (outer is null || outer.Length < 2)
{
    Console.WriteLine("no messages to process");
    return;
}

var toProcess = (RedisResult[])outer[0]!; // [[id, [f1, v1, ...]], ...]
var dlqIds = (RedisResult[])outer[1]!; // [[original_id, dlq_id], ...]

static string Str(RedisResult r) => (string?)r ?? "";

foreach (var m in toProcess)
{
    var entry = (RedisResult[])m!;
    var flat = (RedisResult[])entry[1]!;
    var fields = new List<string>();
    for (var i = 0; i + 1 < flat.Length; i += 2)
        fields.Add($"{Str(flat[i])}={Str(flat[i + 1])}");
    Console.WriteLine($"TO_PROCESS {Str(entry[0])} {string.Join(' ', fields)}");
}

foreach (var p in dlqIds)
{
    var pair = (RedisResult[])p!;
    Console.WriteLine($"DLQ {Str(pair[0])} -> {Str(pair[1])}");
}

if (toProcess.Length == 0 && dlqIds.Length == 0)
    Console.WriteLine("no messages to process");
