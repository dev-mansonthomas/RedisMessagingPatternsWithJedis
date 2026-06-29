# Per-Key Serialized Processing Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Producers["🏭 Producers"]
        P1["Order Service"]
    end

    subgraph Redis["🔴 Redis"]
        JS[("📥 jobs.perkey.v1<br/>Stream")]
        L1["🔒 running:order:ORD-001"]
        L2["🔒 running:order:ORD-002"]
        L3["🔒 running:order:ORD-003"]
        CG["Consumer Group<br/>jobs-serialized-group"]
        JS --> CG
    end

    subgraph Workers["⚙️ Workers"]
        W1["Worker 1"]
        W2["Worker 2"]
        W3["Worker 3"]
    end

    P1 -->|XADD| JS
    CG -->|XREADGROUP| W1
    CG -->|XREADGROUP| W2
    CG -->|XREADGROUP| W3
    W1 <-.->|SET NX/DEL| L1
    W2 <-.->|SET NX/DEL| L2
    W3 <-.->|SET NX/DEL| L3

    style Redis fill:#dc382d,color:#fff
    style L1 fill:#9b59b6,color:#fff
    style L2 fill:#9b59b6,color:#fff
    style L3 fill:#9b59b6,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Producer
    participant R as Redis Stream
    participant W1 as Worker 1
    participant W2 as Worker 2
    participant L as Lock Keys

    P->>R: XADD {orderId: ORD-001, step: validate}
    P->>R: XADD {orderId: ORD-001, step: charge}
    P->>R: XADD {orderId: ORD-002, step: validate}
    P->>R: XADD {orderId: ORD-001, step: ship}
    
    Note over W1,W2: Workers process in parallel
    
    W1->>R: XREADGROUP → ORD-001/validate
    W1->>L: SET running:order:ORD-001 NX
    L-->>W1: OK (lock acquired)
    Note over W1: Processing validate...
    
    W2->>R: XREADGROUP → ORD-001/charge
    W2->>L: SET running:order:ORD-001 NX
    L-->>W2: NIL (lock held!)
    Note over W2: ⏳ Skip, will retry
    
    W2->>R: XREADGROUP → ORD-002/validate
    W2->>L: SET running:order:ORD-002 NX
    L-->>W2: OK (lock acquired)
    Note over W2: Processing ORD-002...
    
    W1->>L: DEL running:order:ORD-001
    W1->>R: XACK ORD-001/validate
    
    Note over W1: Now ORD-001/charge can proceed
    W1->>R: XREADGROUP → ORD-001/charge
    W1->>L: SET running:order:ORD-001 NX
    L-->>W1: OK
    Note over W1: Processing charge...
```

## Key Points

- **Per-Key Ordering**: Messages for the same key processed sequentially
- **Parallel Different Keys**: Different order IDs process in parallel
- **Distributed Lock**: `SET NX` ensures only one worker per key
- **Lock TTL**: Locks have expiration to prevent deadlocks
- **Use Case**: Order processing where steps must execute in order

