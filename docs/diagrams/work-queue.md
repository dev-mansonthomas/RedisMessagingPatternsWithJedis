# Work Queue Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Producers["🏭 Producers"]
        P1["Producer"]
    end

    subgraph Redis["🔴 Redis"]
        JS[("📥 jobs.workqueue.v1<br/>Stream")]
        DLQ[("⚠️ jobs.workqueue.v1:dlq<br/>Dead Letter Queue")]
        LUA["📜 Lua<br/>read_claim_or_dlq"]
        LUA -->|XREADGROUP<br/>job-queue-group| JS
        LUA -->|"XADD<br/>(if redelivery > 2)"| DLQ
    end

    subgraph Workers["⚙️ Workers"]
        W1["Worker 1"]
        W2["Worker 2"]
        W3["Worker 3"]
    end

    subgraph Done["✅ Done Streams"]
        D1[("worker1.done")]
        D2[("worker2.done")]
        D3[("worker3.done")]
    end

    P1 -->|XADD| JS
    W1 -->|"poll<br/>FCALL"| LUA
    W2 -->|"poll<br/>FCALL"| LUA
    W3 -->|"poll<br/>FCALL"| LUA
    W1 -->|XADD| D1
    W2 -->|XADD| D2
    W3 -->|XADD| D3

    style Redis fill:#dc382d,color:#fff
    style JS fill:#3498db,color:#fff
    style DLQ fill:#6b7280,color:#fff
    style LUA fill:#f39c12,color:#000
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Producer
    participant R as Redis Stream
    participant CG as Consumer Group
    participant W1 as Worker 1
    participant W2 as Worker 2
    participant D as Done Stream

    P->>R: XADD jobs.workqueue.v1 {jobId, type}
    P->>R: XADD jobs.workqueue.v1 {jobId, type}
    
    Note over CG: Load balancing via Consumer Group
    
    W1->>CG: XREADGROUP GROUP job-queue-group worker1
    CG-->>W1: Message 1
    
    W2->>CG: XREADGROUP GROUP job-queue-group worker2
    CG-->>W2: Message 2
    
    Note over W1: Processing job...
    W1->>R: XACK (acknowledge)
    W1->>D: XADD worker1.done {result}
    
    Note over W2: Processing job...
    W2->>R: XACK (acknowledge)
    W2->>D: XADD worker2.done {result}
```

## Key Points

- **Load Balancing**: Consumer Group distributes messages across workers
- **Exactly-Once Delivery**: Each message delivered to only one worker
- **Acknowledgment**: Workers ACK after successful processing
- **DLQ Support**: Failed messages (after max retries) go to Dead Letter Queue
- **Visibility**: Each worker has its own "done" stream for tracking

