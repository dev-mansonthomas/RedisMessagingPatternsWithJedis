# Content-Based Routing Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Top[" "]
        direction LR
        subgraph Producers["🏭 Producers"]
            P1["Payment Submitter"]
        end

        subgraph Workers["⚙️ Workers"]
            W1["Standard"]
            W2["Premium"]
            W3["VIP"]
        end
    end

    subgraph RouterBox["🔀 Content Router"]
        CR["Routes by amount"]
    end

    subgraph Redis["🔴 Redis"]
        IN[("📥 incoming")]
        STD[("📦 payments.standard.v1<br/>≤ $1,000")]
        PRM[("📦 payments.premium.v1<br/>$1K - $10K")]
        VIP[("📦 payments.vip.v1<br/>> $10,000")]

        subgraph DLQSection["⚠️ DLQ"]
            LUA["📜 Lua<br/>read_claim_or_dlq"]
            DLQ[("payments.dlq")]
        end
    end

    P1 -->|XADD| IN
    IN -->|XREADGROUP| CR
    CR -->|"XADD ≤$1K"| STD
    CR -->|"XADD $1K-$10K"| PRM
    CR -->|"XADD >$10K"| VIP

    W1 -->|poll| LUA
    W2 -->|poll| LUA
    W3 -->|poll| LUA

    LUA -->|XREADGROUP| STD
    LUA -->|XREADGROUP| PRM
    LUA -->|XREADGROUP| VIP
    LUA -->|"XADD<br/>(if deliveries ≥ maxDeliveries)"| DLQ

    style Top fill:transparent,stroke:transparent
    style Redis fill:#dc382d,color:#fff
    style RouterBox fill:#9b59b6,color:#fff
    style LUA fill:#f39c12,color:#000
    style STD fill:#2ecc71,color:#fff
    style PRM fill:#f39c12,color:#000
    style VIP fill:#e74c3c,color:#fff
    style DLQ fill:#6b7280,color:#fff
    style DLQSection fill:#e74c3c,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant P as Producer
    participant IN as payments.incoming.v1
    participant CR as Content Router
    participant STD as payments.standard.v1
    participant DLQ as payments.standard.v1:dlq
    participant LUA as Lua Script
    participant W as Standard Worker

    P->>IN: XADD {amount: -100}

    CR->>IN: XREADGROUP
    IN-->>CR: {amount: -100}
    Note over CR: -100 ≤ 1000 → Standard
    CR->>STD: XADD
    CR->>IN: XACK

    W->>LUA: poll for messages
    LUA->>STD: XREADGROUP
    STD-->>LUA: {amount: -100}
    LUA-->>W: message
    Note over W: Process fails!<br/>Exception thrown
    Note over W: No XACK sent

    W->>LUA: poll (retry 1)
    LUA->>STD: XREADGROUP + XCLAIM
    Note over LUA: deliveries = 2 (claimed again)
    STD-->>LUA: {amount: -100}
    LUA-->>W: message
    Note over W: Process fails again!

    W->>LUA: poll (sweep)
    LUA->>STD: XPENDING → XCLAIM
    Note over LUA: deliveries = 2 ≥ maxDeliveries<br/>sweep — not redelivered
    LUA->>DLQ: XADD (move to DLQ)
    LUA->>STD: XACK (remove from stream)
    Note over DLQ: Message in DLQ<br/>for manual review
```

## Routing Rules

| Amount Range | Target Stream | Priority |
|-------------|---------------|----------|
| ≤ $1,000 | payments.standard.v1 | Normal |
| $1,001 - $10,000 | payments.premium.v1 | High |
| > $10,000 | payments.vip.v1 | Critical |
| Negative (after 2 retries) | payments.standard.v1:dlq | DLQ |

## Key Points

- **Content Router**: Microservice that reads from incoming stream and routes to target streams based on amount
- **Lua Script**: Executes inside Redis, handles XREADGROUP + XCLAIM + DLQ logic atomically
- **Workers poll Lua**: Workers call the Lua script periodically to fetch pending messages
- **DLQ Flow**: When a message fails processing 2+ times, Lua moves it to DLQ automatically
- **No direct DLQ routing**: Router never sends to DLQ - only Lua does after repeated failures
