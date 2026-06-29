# Scheduled Messages Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Producers["🏭 Producers"]
        P1["Scheduler"]
    end

    subgraph Redis["🔴 Redis"]
        ZS[("📅 scheduled:messages<br/>Sorted Set")]
        TS[("📥 reminders.v1<br/>Target Stream")]
    end

    subgraph PollingService["☕ Polling Service (Java)"]
        POLL["⏰ ScheduledMessageService"]
    end

    subgraph Workers["⚙️ Workers"]
        W1["Message Processor"]
    end

    P1 -->|ZADD score=timestamp| ZS
    POLL -->|"1. ZRANGEBYSCORE 0 {now}"| ZS
    POLL -->|2. XADD| TS
    POLL -->|3. ZREM| ZS
    TS -->|XREADGROUP| W1

    style Redis fill:#dc382d,color:#fff
    style ZS fill:#f39c12,color:#000
    style PollingService fill:#9b59b6,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Producer
    participant ZS as Sorted Set
    participant POLL as Scheduler
    participant TS as Target Stream
    participant W as Worker

    Note over P: Schedule message for future
    P->>ZS: ZADD scheduled:messages<br/>score=1704067200 (Jan 1, 10:00)
    P->>ZS: ZADD scheduled:messages<br/>score=1704070800 (Jan 1, 11:00)
    P->>ZS: ZADD scheduled:messages<br/>score=1704074400 (Jan 1, 12:00)
    
    Note over ZS: Messages stored by timestamp
    
    loop Every second
        POLL->>ZS: ZRANGEBYSCORE 0 {now}
        Note over POLL: Check for due messages
    end
    
    Note over POLL: ⏰ Time: Jan 1, 10:00
    POLL->>ZS: ZRANGEBYSCORE 0 1704067200
    ZS-->>POLL: Message 1 (due!)
    POLL->>TS: XADD messages.v1 {payload}
    POLL->>ZS: ZREM scheduled:messages msg1
    
    TS-->>W: Message delivered
    W->>W: Process message
    
    Note over POLL: ⏰ Time: Jan 1, 11:00
    POLL->>ZS: ZRANGEBYSCORE 0 1704070800
    ZS-->>POLL: Message 2 (due!)
    POLL->>TS: XADD messages.v1 {payload}
    POLL->>ZS: ZREM scheduled:messages msg2
```

## How It Works

| Step | Redis Command | Description |
|------|---------------|-------------|
| 1. Schedule | `ZADD key score payload` | Store message with timestamp as score |
| 2. Poll | `ZRANGEBYSCORE key 0 {now}` | Find messages due now |
| 3. Deliver | `XADD stream payload` | Add to target stream |
| 4. Remove | `ZREM key payload` | Remove from scheduled set |

## Key Points

- **Sorted Set Score**: Unix timestamp determines delivery time
- **Polling**: Service checks every second for due messages
- **Atomic Move**: Message removed from schedule after delivery
- **Persistence**: Scheduled messages survive Redis restart
- **Use Case**: Reminders, delayed notifications, scheduled jobs

