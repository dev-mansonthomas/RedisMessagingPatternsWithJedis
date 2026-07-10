// Dead Letter Queue on Redis Streams — call read_claim_or_dlq and print its reply.
//
// This sample assumes no prior Redis knowledge; the comments explain both the call
// and the structure that comes back.
//
// Blog post: https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams
// Prereq: Redis 8.8+ with samples/setup.sh applied. Target override: REDIS_URL env var.
// Run: cargo run
//
// ── A 60-second primer ───────────────────────────────────────────────────────
//   • A Redis *stream* is an append-only log; each entry = an ID ("1699-0") plus a
//     flat list of field/value pairs (["type","order.created","order_id","1001"]).
//   • A *consumer group* tracks, per entry, which consumer holds it and how many
//     times it was delivered — that delivery count is the DLQ pattern's retry budget.
//   • We FCALL one server-side Lua function, read_claim_or_dlq (lua/stream_utils.lua),
//     which Redis runs atomically, instead of issuing the raw stream commands ourselves.
//
// ── What read_claim_or_dlq returns (maps 1:1 to the Lua `return { a, b }`) ─────
// The reply is a 2-element array:
//   outer[0] = messages to process → each is [ id, [f1, v1, f2, v2, ...] ]
//                                     (that flat list is what XREADGROUP returns)
//   outer[1] = dlq_ids             → each is [ original_id, new_dlq_id ]: the entry
//                                     left test-stream and now lives in test-stream:dlq
// Either list may be empty. The `redis` crate returns an untyped `redis::Value`
// tree: arrays are Value::Array, leaf strings are Value::BulkString(bytes). The two
// tiny helpers below (`s`, `arr`) unwrap those cases; the rest is walking the tree.
use redis::Value;
use std::env;

// Render a leaf Value as text (bulk string, simple string, or integer).
fn s(v: &Value) -> String {
    match v {
        Value::BulkString(b) => String::from_utf8_lossy(b).into_owned(),
        Value::SimpleString(s) => s.clone(),
        Value::Int(i) => i.to_string(),
        other => format!("{other:?}"),
    }
}

// View a Value as its child slice if it is an array, else an empty slice.
fn arr(v: &Value) -> &[Value] {
    match v {
        Value::Array(items) => items,
        _ => &[],
    }
}

fn main() -> redis::RedisResult<()> {
    let url = env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".to_string());

    // 1) Open a client and a blocking connection.
    let client = redis::Client::open(url)?;
    let mut con = client.get_connection()?;

    // 2) Call the function. We build the raw command
    //    FCALL <name> <numkeys> <keys...> <args...> argument by argument.
    //      numkeys = 2
    //      KEYS = stream, dlq_stream
    //      ARGV = group, consumer, minIdle(ms), count, maxDeliver  (demo defaults)
    let result: Value = redis::cmd("FCALL")
        .arg("read_claim_or_dlq")
        .arg(2)
        .arg("test-stream")
        .arg("test-stream:dlq")
        .arg("test-group")
        .arg("consumer-1")
        .arg("100")
        .arg("100")
        .arg("2")
        .query(&mut con)?;

    // 3) The reply is the 2-element array [ messages_to_process, dlq_ids ].
    let outer = arr(&result);
    if outer.len() < 2 {
        println!("no messages to process");
        return Ok(());
    }
    let to_process = arr(&outer[0]); // [[id, [f1, v1, ...]], ...]
    let dlq_ids = arr(&outer[1]); // [[original_id, dlq_id], ...]

    // 4) Entries to handle now: entry[0] = ID, entry[1] = flat [f1, v1, f2, v2, ...].
    //    chunks_exact(2) walks the flat list one (field, value) pair at a time.
    for m in to_process {
        let entry = arr(m);
        let flat = arr(&entry[1]);
        let fields: Vec<String> = flat
            .chunks_exact(2)
            .map(|kv| format!("{}={}", s(&kv[0]), s(&kv[1])))
            .collect();
        println!("TO_PROCESS {} {}", s(&entry[0]), fields.join(" "));
    }

    // 5) Entries the function just moved to the dead-letter stream: [original_id, new_dlq_id].
    for p in dlq_ids {
        let pair = arr(p);
        println!("DLQ {} -> {}", s(&pair[0]), s(&pair[1]));
    }

    // 6) Nothing to process and nothing dead-lettered this poll.
    if to_process.is_empty() && dlq_ids.is_empty() {
        println!("no messages to process");
    }
    Ok(())
}
