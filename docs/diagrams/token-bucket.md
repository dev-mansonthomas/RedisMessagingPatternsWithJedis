# Token Bucket Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Producers["🏭 Producers"]
        P1["Job Submitter"]
    end

    subgraph Redis["🔴 Redis"]
        JS[("📥 token-bucket.jobs.v1<br/>Stream")]
        LUA["📜 Lua<br/>acquire_token"]
        subgraph Counters["🔢 Concurrency Counters"]
            C1["running:payment<br/>max=3"] ~~~ C2["running:email<br/>max=2"] ~~~ C3["running:csv<br/>max=1"]
        end
        CFG[("⚙️ config<br/>Hash")]
        LUA -->|"GET + INCR"| Counters
    end

    subgraph Workers["⚙️ 18 Workers"]
        W1["Worker 1..6"] ~~~ W2["Worker 7..12"] ~~~ W3["Worker 13..18"]
    end

    P1 -->|XADD| JS
    JS -->|XREADGROUP| Workers
    Workers -->|HGET max| CFG
    Workers -->|"FCALL(max)"| LUA

    style Redis fill:#dc382d,color:#fff
    style LUA fill:#f39c12,color:#000
    style Counters fill:#1e3a5f,color:#fff
    style C1 fill:#3b82f6,color:#fff
    style C2 fill:#10b981,color:#fff
    style C3 fill:#f59e0b,color:#000
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Producer
    participant R as Redis Stream
    participant W as Worker
    participant TB as Token Bucket
    participant EXT as External API

    P->>R: XADD {type: payment, jobId}
    P->>R: XADD {type: payment, jobId}
    P->>R: XADD {type: payment, jobId}
    P->>R: XADD {type: payment, jobId}
    
    Note over TB: payment:max=3, running=0
    
    W->>R: XREADGROUP (get job 1)
    W->>TB: INCR running:payment → 1
    Note over W: 1 ≤ 3 ✓ Proceed
    W->>EXT: Process payment 1
    
    W->>R: XREADGROUP (get job 2)
    W->>TB: INCR running:payment → 2
    Note over W: 2 ≤ 3 ✓ Proceed
    W->>EXT: Process payment 2
    
    W->>R: XREADGROUP (get job 3)
    W->>TB: INCR running:payment → 3
    Note over W: 3 ≤ 3 ✓ Proceed
    W->>EXT: Process payment 3
    
    W->>R: XREADGROUP (get job 4)
    W->>TB: INCR running:payment → 4
    Note over W: 4 > 3 ✗ Wait!
    W->>TB: DECR running:payment → 3
    W-->>W: Sleep & retry later
    
    EXT-->>W: Payment 1 complete
    W->>TB: DECR running:payment → 2
    
    Note over W: Now job 4 can proceed
```

## Key Points

- **Concurrency Limit**: Each job type has a configurable max concurrency
- **Dynamic Configuration**: Limits can be changed at runtime via Redis Hash
- **Global Coordination**: All workers share the same counters
- **Use Case**: Protect external APIs from overload
- **Job Types**: payment (max 3), email (max 2), csv (max 1)

