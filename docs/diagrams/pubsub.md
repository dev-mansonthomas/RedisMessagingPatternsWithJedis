# Pub/Sub Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Publishers["🏭 Publishers"]
        P1["Publisher"]
    end

    subgraph Redis["🔴 Redis"]
        CH["📡 Channel<br/>news"]
    end

    subgraph Subscribers["👥 Subscribers"]
        S1["Subscriber A"]
        S2["Subscriber B"]
        S3["Subscriber C"]
    end

    P1 -->|PUBLISH| CH
    CH -->|PUSH| S1
    CH -->|PUSH| S2
    CH -->|PUSH| S3

    style Redis fill:#dc382d,color:#fff
    style CH fill:#3498db,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Publisher
    participant R as Redis
    participant S1 as Subscriber A
    participant S2 as Subscriber B
    participant S3 as Subscriber C

    Note over S1,S3: Subscribers connect first
    
    S1->>R: SUBSCRIBE news
    R-->>S1: Subscribed to 'news'
    S2->>R: SUBSCRIBE news
    R-->>S2: Subscribed to 'news'
    S3->>R: SUBSCRIBE news
    R-->>S3: Subscribed to 'news'
    
    Note over R: All subscribers ready
    
    P->>R: PUBLISH news "Breaking news!"
    R-->>P: 3 (subscriber count)
    
    par Real-time Push
        R-->>S1: "Breaking news!"
    and
        R-->>S2: "Breaking news!"
    and
        R-->>S3: "Breaking news!"
    end
    
    Note over P: Publisher continues immediately
    Note over S1,S3: All receive message instantly
    
    P->>R: PUBLISH news "More news!"
    par Real-time Push
        R-->>S1: "More news!"
        R-->>S2: "More news!"
        R-->>S3: "More news!"
    end
```

## Key Points

- **Fire-and-Forget**: Publisher doesn't wait for delivery confirmation
- **Real-time Push**: Messages pushed instantly (no polling)
- **No Persistence**: Messages not stored - must be connected to receive
- **Fan-Out**: One message reaches all subscribers
- **Use Case**: Chat, notifications, live updates
- **Difference from Streams**: No history, no guaranteed delivery

