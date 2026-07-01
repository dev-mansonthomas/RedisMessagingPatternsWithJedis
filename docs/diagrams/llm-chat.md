# LLM Chat Pattern (#12)

> Slice 1 (happy path + internals) is solid below; the dashed nodes/edges mark later slices
> (fan-out, resilience). See `docs/specs/llm-chat.md`.

## Architecture Diagram

```mermaid
flowchart TB
    UI["🖥️ Angular /llm-chat"]

    subgraph Redis["🔴 Redis"]
        S[("💬 chat:{cid}<br/>Conversation Stream")]
        T[("⌨️ chat:{cid}:tok<br/>Token Stream (capped)")]
    end

    subgraph Workers["⚙️ Virtual Threads (per cid)"]
        R["🤖 LlmResponderWorker<br/>cg:responder"]
        TL["📡 LlmTokenListenerService"]
        M["🛡️ Moderation<br/>cg:moderation"]
        A["📊 Analytics<br/>cg:analytics"]
    end

    LLM["🧠 LlmClient (mock)"]

    UI -->|"POST /api/llm-chat/{cid}/message<br/>XADD role=user"| S
    S -->|"XREADGROUP cg:responder >"| R
    R -->|"XREVRANGE COUNT N (context)"| S
    R -->|"generate stream"| LLM
    R -->|"XADD token msgId"| T
    R -->|"XADD role=assistant + XACK"| S
    T -->|"XREAD BLOCK"| TL
    TL -->|"WebSocket LlmChatEvent (filter cid, demux msgId)"| UI
    S -.->|"Slice 2: XREADGROUP cg:moderation"| M
    S -.->|"Slice 2: XREADGROUP cg:analytics"| A

    style Redis fill:#dc382d,color:#fff
    style S fill:#3498db,color:#fff
    style T fill:#8e44ad,color:#fff
    style LLM fill:#f39c12,color:#000
    style M stroke-dasharray: 5 5
    style A stroke-dasharray: 5 5
```

## Sequence Diagram — happy path

```mermaid
sequenceDiagram
    autonumber
    participant U as Angular UI
    participant C as LlmChatController
    participant S as chat:{cid}
    participant R as Responder (VT)
    participant L as LlmClient (mock)
    participant T as chat:{cid}:tok
    participant TL as TokenListener (VT)

    U->>C: POST message(text)
    C->>S: ensureConversation (XGROUP CREATE $, MKSTREAM)
    C->>S: XADD role=user
    R->>S: XREADGROUP cg:responder > (BLOCK)
    R->>S: XREVRANGE COUNT N (rebuild context)
    R->>L: generate(context) [streaming]
    loop each token
        L-->>R: token
        R->>T: XADD token msgId
        TL->>T: XREAD BLOCK
        TL-->>U: WS TOKEN (progressive render)
    end
    R->>S: XADD role=assistant (full text)
    R->>S: XACK (user message processed)
```

## Sequence Diagram — crash recovery (Slice 3)

```mermaid
sequenceDiagram
    autonumber
    participant R1 as Responder-1 (VT)
    participant S as chat:{cid}
    participant R2 as Responder-2 (VT)

    R1->>S: XREADGROUP cg:responder (takes message)
    Note over R1: crash before XACK
    Note over S: message stays PENDING (XPENDING shows it)
    R2->>S: XAUTOCLAIM minIdle=5000
    S-->>R2: message reclaimed
    R2->>S: regenerate then XADD assistant + XACK
```

## Key Points

- **The conversation is the stream**: `chat:{cid}` is the ordered, replayable source of truth.
- **Context via `XREVRANGE`**: the last N turns are read back to seed the model.
- **Token streaming is a stream**: `chat:{cid}:tok` (one per conversation, `msgId`-tagged) feeds the
  live UI; a single listener per `cid`, front demuxes by `msgId`.
- **Group before `XADD`, at `$`**: no missed messages; `role != user → XACK & skip` prevents an
  infinite generate loop.
- **Display reads are `XRANGE`/`XINFO`** — never `XREADGROUP` — to avoid phantom pending entries.
