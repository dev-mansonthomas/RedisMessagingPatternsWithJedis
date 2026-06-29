# Dead Letter Queue (DLQ) Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Producers["🏭 Producers"]
        P1["Message Producer"]
    end

    subgraph Redis["🔴 Redis"]
        MS[("📥 test-stream<br/>Main Stream")]

        subgraph DLQSection["⚠️ DLQ"]
            LUA["📜 Lua<br/>read_claim_or_dlq"]
            DLQ[("test-stream:dlq")]
        end

        LUA -->|"XREADGROUP<br/>test-group"| MS
        LUA -->|"XADD<br/>(if redelivery > 2)"| DLQ
    end

    subgraph Consumer["⚙️ Consumer"]
        C1["Worker"]
    end

    P1 -->|XADD| MS
    C1 -->|"poll<br/>FCALL"| LUA
    C1 -->|XACK| MS

    style Redis fill:#dc382d,color:#fff
    style DLQ fill:#6b7280,color:#fff
    style LUA fill:#f39c12,color:#000
    style MS fill:#3498db,color:#fff
    style DLQSection fill:#e74c3c,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Producer
    participant R as Redis Stream
    participant LUA as Lua Function
    participant W as Worker
    participant DLQ as Dead Letter Queue

    P->>R: XADD test-stream {message}
    
    W->>LUA: FCALL read_claim_or_dlq
    LUA->>R: XREADGROUP CLAIM (atomic)
    R-->>LUA: Message (delivery_count: 1)
    LUA-->>W: Message
    
    Note over W: ❌ Processing fails
    Note over W: Message NOT acknowledged
    
    W->>LUA: FCALL read_claim_or_dlq
    LUA->>R: XREADGROUP CLAIM
    R-->>LUA: Message (delivery_count: 2)
    LUA-->>W: Message
    
    Note over W: ❌ Processing fails again
    
    W->>LUA: FCALL read_claim_or_dlq
    LUA->>R: XREADGROUP CLAIM
    R-->>LUA: Message (delivery_count: 3)
    Note over LUA: delivery_count ≥ maxDeliveries!
    LUA->>DLQ: XADD to DLQ
    LUA->>R: XACK (remove from pending)
    LUA-->>W: DLQ notification
    
    Note over DLQ: Message preserved for inspection
```

## Key Points

- **Automatic Retry**: Failed messages automatically re-delivered
- **Delivery Counter**: Redis tracks how many times a message was delivered
- **Max Deliveries**: After N failures, message goes to DLQ
- **Atomic Operation**: Lua function ensures claim+read is atomic
- **Inspection**: DLQ messages can be inspected and manually reprocessed

