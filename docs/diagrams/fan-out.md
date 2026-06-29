# Fan-Out Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Producer["🏭 Producer"]
        P1["Event Publisher"]
    end

    subgraph Workers["⚙️ Workers"]
        W1["&nbsp;&nbsp;Analytics Service&nbsp;&nbsp;"]
        W2["Notification Service"]
        W3["&nbsp;&nbsp;&nbsp;&nbsp;Audit Service&nbsp;&nbsp;&nbsp;&nbsp;"]
    end

    subgraph Redis["🔴 Redis"]
        subgraph DoneStreams["✅ Done Streams"]
            D1[("&nbsp;&nbsp;analytics.done&nbsp;&nbsp;")]
            D2[("notifications.done")]
            D3[("&nbsp;&nbsp;&nbsp;&nbsp;audit.done&nbsp;&nbsp;&nbsp;&nbsp;")]
        end

        ES[("📥 events.fanout.v1<br/>Stream")]

        subgraph DLQSection["⚠️ DLQ"]
            LUA["📜 Lua<br/>read_claim_or_dlq"]
            DLQ[("events.fanout.v1:dlq")]
        end

        LUA -->|XREADGROUP| ES
        LUA -->|"XADD<br/>(if redelivery > 2)"| DLQ
    end

    P1 -->|XADD| ES
    W1 -->|"poll<br/>FCALL"| LUA
    W2 -->|"poll<br/>FCALL"| LUA
    W3 -->|"poll<br/>FCALL"| LUA
    W1 -->|XADD| D1
    W2 -->|XADD| D2
    W3 -->|XADD| D3

    style Redis fill:#dc382d,color:#fff
    style ES fill:#3498db,color:#fff
    style DLQ fill:#6b7280,color:#fff
    style LUA fill:#f39c12,color:#000
    style DoneStreams fill:#2ecc71,color:#fff
    style DLQSection fill:#e74c3c,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Publisher
    participant R as Redis Stream
    participant CG1 as Group: Analytics
    participant CG2 as Group: Notifications
    participant CG3 as Group: Audit
    participant A as Analytics
    participant N as Notifications
    participant Au as Audit

    P->>R: XADD events.fanout.v1 {event}
    
    Note over R: Same message delivered to ALL groups
    
    par Analytics Processing
        A->>CG1: XREADGROUP fanout-analytics
        CG1-->>A: Event copy
        A->>A: Process analytics
        A->>R: XACK
    and Notification Processing
        N->>CG2: XREADGROUP fanout-notifications
        CG2-->>N: Event copy
        N->>N: Send notification
        N->>R: XACK
    and Audit Processing
        Au->>CG3: XREADGROUP fanout-audit
        Au-->>Au: Event copy
        Au->>Au: Log audit
        Au->>R: XACK
    end
```

## Key Points

- **Multiple Consumer Groups**: Each group gets ALL messages independently
- **Independent Processing**: Each service processes at its own pace
- **Guaranteed Delivery**: Each group tracks its own position in the stream
- **Use Case**: Event broadcasting to multiple downstream services
- **Difference from Pub/Sub**: Messages are persisted and guaranteed

