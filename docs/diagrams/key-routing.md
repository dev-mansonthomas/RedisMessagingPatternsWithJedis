# Key Routing Pattern

## Architecture Diagram

```mermaid
flowchart LR
    subgraph Management["🔧 Management"]
        UI["🅰️ Angular<br/>Rules Manager"]
        REST["☕ Spring Boot<br/>REST API"]
        UI --> REST
    end

    subgraph Producers["☕ Producers (Java)"]
        P1["Event Publisher"]
    end

    subgraph Redis["🔴 Redis"]
        direction TB
        RULES[("📋 routing:rules:*<br/>HashMap")]
        LUA["📜 Lua<br/>route_message"]
        LUA -.->|"HGETALL"| RULES
        EX[("&nbsp;&nbsp;&nbsp;&nbsp;📥 Exchange&nbsp;&nbsp;&nbsp;&nbsp;")]
        LUA -->|"XADD"| EX
        S1[("&nbsp;&nbsp;events.order.v1&nbsp;&nbsp;")]
        S2[("&nbsp;&nbsp;events.order.v2&nbsp;&nbsp;")]
        S3[("&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;events.vip&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;")]
        S4[("&nbsp;&nbsp;&nbsp;&nbsp;events.gdpr&nbsp;&nbsp;&nbsp;&nbsp;")]
        LUA -->|"XADD"| S1
        LUA -->|"XADD"| S2
        LUA -->|"XADD"| S3
        LUA -->|"XADD"| S4
    end

    subgraph Workers["⚙️ Workers"]
        W1["&nbsp;&nbsp;&nbsp;Order V1&nbsp;&nbsp;&nbsp;"]
        W2["&nbsp;&nbsp;&nbsp;Order V2&nbsp;&nbsp;&nbsp;"]
        W3["&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;VIP&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"]
        W4["&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;GDPR&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"]
    end

    REST -->|"HSET/HDEL"| RULES
    P1 -->|"FCALL"| LUA
    S1 --> W1
    S2 --> W2
    S3 --> W3
    S4 --> W4

    style Management fill:#2c3e50,color:#fff
    style Redis fill:#dc382d,color:#fff
    style Workers fill:#34495e,color:#fff
    style LUA fill:#f39c12,color:#000
    style RULES fill:#9b59b6,color:#fff
    style UI fill:#dd0031,color:#fff
    style REST fill:#6db33f,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant UI as Angular UI
    participant API as Spring Boot API
    participant Redis as Redis
    participant LUA as Lua route_message
    participant W as Workers

    Note over UI,API: Rule Management (CRUD)
    UI->>API: Create/Update Rule
    API->>Redis: HSET routing:rules:* {rule JSON}

    Note over Redis,LUA: Message Routing
    API->>LUA: FCALL route_message(routingKey, payload)
    LUA->>Redis: HGETALL routing:rules:*
    Redis-->>LUA: Rules list
    Note over LUA: Evaluate Lua patterns<br/>against routing key
    LUA->>Redis: XADD events.topic.v1 (exchange)
    LUA->>Redis: XADD events.order.v1 (if match)
    LUA->>Redis: XADD events.vip (if match)

    Note over Redis,W: Message Consumption
    W->>Redis: XREADGROUP events.order.v1
    Redis-->>W: Messages
```

## Routing Rules Example

| Pattern | Destination | Priority | Stop on Match |
|---------|-------------|----------|---------------|
| `^order%.place%..*%.v1$` | events.order.v1 | 10 | false |
| `^order%.place%..*%.v2$` | events.order.v2 | 10 | false |
| `^order%..*%.vip%.` | events.vip | 20 | false |
| `^order%..*%.eu%.` | events.gdpr | 30 | false |
| `^order%.cancelled%.` | events.audit | 5 | true |

## Key Points

- **Dynamic Routing Rules**: Rules stored in Redis HashMap, no redeploy needed
- **Lua Pattern Matching**: More expressive than RabbitMQ's * and #
- **CRUD via REST API**: Angular UI manages rules through Spring Boot API
- **Multi-destination**: One message can route to multiple streams
- **Stop on Match**: Rules can stop evaluation after matching
- **Priority-based**: Lower priority number = evaluated first
- **Audit Trail**: All messages logged to exchange stream

