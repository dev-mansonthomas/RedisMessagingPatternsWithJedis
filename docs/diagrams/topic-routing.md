# Topic Routing Pattern (Pub/Sub with Pattern Matching)

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Publishers["☕ Publishers (Java)"]
        P1["Event Publisher"]
    end

    subgraph Redis["🔴 Redis Pub/Sub"]
        CH1["📡 orders.created"]
        CH2["📡 orders.updated"]
        CH3["📡 payments.created"]
        CH4["📡 payments.failed"]
    end

    subgraph Subscribers["☕ Pattern Subscribers (Java)"]
        S1["Subscriber A<br/>orders.*"]
        S2["Subscriber B<br/>payments.*"]
        S3["Subscriber C<br/>*.created"]
    end

    P1 -->|PUBLISH| CH1
    P1 -->|PUBLISH| CH2
    P1 -->|PUBLISH| CH3
    P1 -->|PUBLISH| CH4

    CH1 -.->|match| S1
    CH2 -.->|match| S1
    CH1 -.->|match| S3
    CH3 -.->|match| S2
    CH4 -.->|match| S2
    CH3 -.->|match| S3

    style Redis fill:#dc382d,color:#fff
    style S1 fill:#3498db,color:#fff
    style S2 fill:#2ecc71,color:#fff
    style S3 fill:#9b59b6,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Publisher
    participant R as Redis
    participant S1 as Sub A (orders.*)
    participant S2 as Sub B (payments.*)
    participant S3 as Sub C (*.created)

    Note over S1,S3: Subscribe to patterns
    
    S1->>R: PSUBSCRIBE orders.*
    S2->>R: PSUBSCRIBE payments.*
    S3->>R: PSUBSCRIBE *.created
    
    P->>R: PUBLISH orders.created {order}
    par Pattern matching
        R-->>S1: Match orders.* ✓
        R-->>S3: Match *.created ✓
    end
    Note over S2: No match (payments.*)
    
    P->>R: PUBLISH payments.failed {payment}
    R-->>S2: Match payments.* ✓
    Note over S1,S3: No match
    
    P->>R: PUBLISH payments.created {payment}
    par Pattern matching
        R-->>S2: Match payments.* ✓
        R-->>S3: Match *.created ✓
    end
```

## Pattern Examples

| Pattern | Matches | Doesn't Match |
|---------|---------|---------------|
| `orders.*` | orders.created, orders.updated | payments.created |
| `*.created` | orders.created, payments.created | orders.updated |
| `payments.*` | payments.created, payments.failed | orders.created |
| `*.*` | All two-level topics | single-level |

## Key Points

- **Pattern Matching**: PSUBSCRIBE allows wildcard patterns
- **Flexible Routing**: Subscribers choose what to receive
- **Decoupled**: Publishers don't know subscribers
- **Real-time**: Instant push delivery
- **Use Case**: Event-driven architectures, microservices

