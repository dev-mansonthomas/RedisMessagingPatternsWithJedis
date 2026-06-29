# Request/Reply Pattern

## Architecture Diagram

```mermaid
flowchart TB
    subgraph Client["🖥️ Client"]
        REQ["Requester"]
    end

    subgraph Redis["🔴 Redis"]
        subgraph LuaSection[" "]
            direction LR
            LUA_REQ["📜 Lua<br/>request"]
            LUA_RES["📜 Lua<br/>response"]
        end
        RS[("📥 request.stream.v1")]
        RES[("📤 response.stream.v1")]
        TK["⏱️ timeout:*<br/>SET + TTL"]
        SK["👻 shadow:*<br/>HSET + TTL+10"]
        KS["🔔 Keyspace<br/>Notifications"]
        TK -.->|"expire"| KS
    end

    subgraph Workers["⚙️ Workers"]
        W1["&nbsp;&nbsp;Request Worker&nbsp;&nbsp;"]
        W2["Keyspace Listener"]
    end

    REQ -->|"FCALL request"| LUA_REQ
    LUA_REQ -->|"XADD"| RS
    LUA_REQ -->|"SET EX"| TK
    LUA_REQ -->|"HSET + EXPIRE"| SK
    W1 -->|"FCALL read_claim_or_dlq"| RS
    W1 -->|"FCALL response"| LUA_RES
    LUA_RES -->|"DEL"| TK
    LUA_RES -->|"XADD"| RES
    KS -.->|"onExpire"| W2
    W2 -->|"HGETALL"| SK
    W2 -->|"XADD TIMEOUT"| RES
    REQ -->|"XREADGROUP<br/>polling"| RES

    style Redis fill:#dc382d,color:#fff
    style LuaSection fill:transparent,stroke:transparent
    style TK fill:#e74c3c,color:#fff
    style SK fill:#9b59b6,color:#fff
    style LUA_REQ fill:#f39c12,color:#000
    style LUA_RES fill:#f39c12,color:#000
    style KS fill:#3498db,color:#fff
    style W2 fill:#27ae60,color:#fff
```

## Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant LUA_REQ as Lua request
    participant LUA_RES as Lua response
    participant RQ as Request Stream
    participant W as Request Worker
    participant KL as Keyspace Listener
    participant RS as Response Stream
    participant TK as Timeout Key
    participant SK as Shadow Key

    C->>LUA_REQ: FCALL request {orderId, timeout: 10s}
    LUA_REQ->>RQ: XADD {correlationId, orderId, payload}
    LUA_REQ->>TK: SET timeout:corr-123 EX 10
    LUA_REQ->>SK: HSET shadow:corr-123 {businessId, streamResponseName}
    LUA_REQ->>SK: EXPIRE shadow:corr-123 20 (timeout+10)
    LUA_REQ-->>C: correlationId: corr-123

    par Polling for response
        C->>RS: XREADGROUP (polling)
    and Worker processes
        W->>RQ: FCALL read_claim_or_dlq
        RQ-->>W: Request {correlationId, orderId}
        Note over W: Processing request...
        W->>LUA_RES: FCALL response {correlationId, result}
        LUA_RES->>TK: DEL timeout:corr-123 (cancel timeout)
        LUA_RES->>RS: XADD {correlationId, result}
    end

    RS-->>C: Response {correlationId, result}
    Note over C: ✅ Got response!

    Note over C,SK: --- Alternative: Timeout ---

    Note over TK: ⏰ 10 seconds pass...
    TK-->>KL: Keyspace notification (key expired)
    KL->>SK: HGETALL shadow:corr-456 (get metadata)
    SK-->>KL: {businessId, streamResponseName}
    KL->>LUA_RES: FCALL response {correlationId, TIMEOUT}
    LUA_RES->>RS: XADD {correlationId, status: TIMEOUT}
    KL->>SK: DEL shadow:corr-456 (cleanup)
    RS-->>C: Response {status: TIMEOUT}
    Note over C: ⚠️ Request timed out
```

## Key Points

- **Correlation ID**: Links request to its response
- **Timeout Handling**: TTL keys trigger automatic timeout response via Keyspace Listener
- **Shadow Key**: Stores metadata (businessId, streamResponseName) for timeout handling
- **Atomic Operations**: Lua ensures request setup is atomic
- **Two Lua Functions**: `request` (sends request + sets up timeout) and `response` (sends response + cancels timeout)
- **Keyspace Listener**: Java worker that consumes Redis key expiration events and publishes TIMEOUT responses

