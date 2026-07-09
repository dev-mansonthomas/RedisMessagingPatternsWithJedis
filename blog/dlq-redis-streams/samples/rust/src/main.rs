// Call the read_claim_or_dlq Redis Function (DLQ pattern) once and print the result.
//
// Blog post: https://github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis/tree/blog-dlq-v1/blog/dlq-redis-streams
// Prereq: Redis 8.8+ with samples/setup.sh applied. Target override: REDIS_URL env var.
// Run: cargo run
use redis::Value;
use std::env;

fn s(v: &Value) -> String {
    match v {
        Value::BulkString(b) => String::from_utf8_lossy(b).into_owned(),
        Value::SimpleString(s) => s.clone(),
        Value::Int(i) => i.to_string(),
        other => format!("{other:?}"),
    }
}

fn arr(v: &Value) -> &[Value] {
    match v {
        Value::Array(items) => items,
        _ => &[],
    }
}

fn main() -> redis::RedisResult<()> {
    let url = env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6379".to_string());
    let client = redis::Client::open(url)?;
    let mut con = client.get_connection()?;

    // KEYS: stream, dlq — ARGS: group, consumer, minIdleMs, count, maxDeliveries
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

    let outer = arr(&result);
    if outer.len() < 2 {
        println!("no messages to process");
        return Ok(());
    }
    let to_process = arr(&outer[0]); // [[id, [f1, v1, ...]], ...]
    let dlq_ids = arr(&outer[1]); // [[original_id, dlq_id], ...]

    for m in to_process {
        let entry = arr(m);
        let flat = arr(&entry[1]);
        let fields: Vec<String> = flat
            .chunks_exact(2)
            .map(|kv| format!("{}={}", s(&kv[0]), s(&kv[1])))
            .collect();
        println!("TO_PROCESS {} {}", s(&entry[0]), fields.join(" "));
    }
    for p in dlq_ids {
        let pair = arr(p);
        println!("DLQ {} -> {}", s(&pair[0]), s(&pair[1]));
    }
    if to_process.is_empty() && dlq_ids.is_empty() {
        println!("no messages to process");
    }
    Ok(())
}
