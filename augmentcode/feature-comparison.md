# DLQ Feature Comparison: RabbitMQ vs Redis Implementation

## Current Implementation Features

| Feature | Implemented | Details |
|---------|-------------|---------|
| Max Delivery Count | ✅ | `maxDeliveries` parameter |
| Atomic Claim + DLQ Routing | ✅ | Lua script `read_claim_or_dlq` |
| Consumer Groups | ✅ | Redis Streams native |
| Min Idle Time | ✅ | Before re-claim |
| Message Copy to DLQ | ✅ | Preserves original fields |
| Real-time Events | ✅ | WebSocket broadcasting |

---

## 1. RabbitMQ Features Missing in Our Implementation

### 1.1 TTL (Time-To-Live) - `x-message-ttl`

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High - Redis supports `EXPIRE` and messages have timestamps |
| **Difficulty** | 🟡 Medium - Requires polling or auxiliary Sorted Set |
| **Performance** | 🟡 Moderate impact - Cleanup thread or periodic Lua |

**Proposed implementation:**
```lua
-- In Lua, check if message has expired
local msg_time = tonumber(string.match(message_id, "^(%d+)"))
local ttl_ms = tonumber(redis.call('HGET', 'stream:config', 'ttl_ms')) or 0
if ttl_ms > 0 and (now - msg_time) > ttl_ms then
  -- Route to DLQ with reason "expired"
end
```

---

### 1.2 Max Length / Max Bytes - `x-max-length` / `x-max-length-bytes`

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High - Redis Streams supports `MAXLEN` natively |
| **Difficulty** | 🟢 Low - Add `MAXLEN` to `XADD` |
| **Performance** | 🟢 No impact - Native Redis |

**Implementation:** Already supported by Redis Streams with `XADD ... MAXLEN ~ 1000`

---

### 1.3 Dead Letter Reason - Metadata

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High |
| **Difficulty** | 🟢 Low - Add `_dlq_reason` field |
| **Performance** | 🟢 Negligible |

**Possible reasons:**
- `max_deliveries_exceeded`
- `message_expired` (TTL)
- `message_rejected` (explicit NACK)
- `queue_overflow` (max length)

---

### 1.4 Explicit NACK - Manual Rejection

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High |
| **Difficulty** | 🟢 Low - New Lua function `nack_to_dlq` |
| **Performance** | 🟢 Negligible |

**Proposed implementation:**
```lua
redis.register_function('nack_to_dlq', function(keys, args)
  local stream, dlq = keys[1], keys[2]
  local message_id, reason = args[1], args[2]
  -- XCLAIM + XADD to DLQ + XACK
end)
```

---

### 1.5 Dynamic Dead Letter Exchange (DLX) - `x-dead-letter-exchange`

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High |
| **Difficulty** | 🟡 Medium - Config per stream in Redis Hash |
| **Performance** | 🟢 Low - One additional HGET |

**Proposed configuration:**
```
dlq:config:{stream} -> {
  "dlqStream": "orders:dlq",
  "dlqRoutingKey": "order.failed",
  "dlqExchange": "events.errors.v1"
}
```

---

### 1.6 Priority Queues - `x-max-priority`

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | 🟡 Medium - Redis Streams has no native priority |
| **Difficulty** | 🔴 High - Requires multiple streams or Sorted Set |
| **Performance** | 🟡 Moderate impact - More complex selection logic |

**Approaches:**
1. **Multiple streams**: `orders:high`, `orders:medium`, `orders:low`
2. **Sorted Set**: Score = priority, but loses Consumer Groups

---

### 1.7 Lazy Queues - Disk Storage

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ⚠️ Partial - Redis is in-memory by design |
| **Difficulty** | 🔴 N/A |
| **Performance** | N/A |

**Redis alternative:** Use Redis with AOF + `maxmemory-policy allkeys-lru` or externalize to storage (S3, DB) for archival.

---

### 1.8 Queue Expiration - `x-expires`

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High |
| **Difficulty** | 🟢 Low - `EXPIRE` on stream key |
| **Performance** | 🟢 Negligible |

---

## 2. Advanced Features NOT Supported by RabbitMQ

### 2.1 Retry with Integrated Exponential Backoff

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High |
| **Difficulty** | 🟡 Medium - Combine with Scheduled Messages |
| **Performance** | 🟡 Moderate - Uses auxiliary Sorted Set |

**Proposed implementation:**
```lua
-- Calculate backoff delay
local delay = math.min(base_delay * (2 ^ (delivery_count - 1)), max_delay)
local retry_at = now + delay

-- Add to Sorted Set for delayed retry
redis.call('ZADD', 'retry:scheduled', retry_at, cjson.encode({
  stream = stream,
  message_id = message_id,
  fields = fields
}))
```

---

### 2.2 Content-Based DLQ Routing

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High |
| **Difficulty** | 🟡 Medium - Reuse `route_message` pattern |
| **Performance** | 🟡 Moderate - Lua pattern matching |

**Example:** Route payment errors to `dlq:payments`, order errors to `dlq:orders`.

---

### 2.3 Automatic DLQ Message Replay

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High |
| **Difficulty** | 🟢 Low - Already implementable with XRANGE + XADD |
| **Performance** | 🟢 Low - Batch operation |

**Proposed API:**
```java
dlqService.replayMessages("orders:dlq", "orders", filter);
```

---

### 2.4 Message Transformation before DLQ

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | ✅ High |
| **Difficulty** | 🟢 Low - Modify Lua `xadd_copy_fields` |
| **Performance** | 🟢 Negligible |

**Added metadata:**
- `_dlq_original_stream`
- `_dlq_original_id`
- `_dlq_reason`
- `_dlq_timestamp`
- `_dlq_delivery_count`

---

### 2.5 DLQ Query/Filtering

| Criteria | Evaluation |
|----------|------------|
| **Feasibility** | 🟡 Medium - Redis Streams has limited query capabilities |
| **Difficulty** | 🟡 Medium - Requires secondary index or Redis Search |
| **Performance** | 🟡 Depends on DLQ size |

**Options:**
1. **Redis Search** (RediSearch module) for full-text search
2. **Manual Hash index**: `dlq:index:reason:{reason}` → Set of IDs

---

## 3. Summary Table

| Feature | RabbitMQ | Our Impl. | Feasibility | Difficulty | Perf. Impact |
|---------|----------|-----------|-------------|------------|--------------|
| Max Deliveries | ✅ | ✅ | - | - | - |
| TTL Message | ✅ | ❌ | ✅ High | 🟡 Medium | 🟡 Moderate |
| Max Length | ✅ | ❌ | ✅ High | 🟢 Low | 🟢 None |
| DLQ Reason | ✅ | ❌ | ✅ High | 🟢 Low | 🟢 None |
| Explicit NACK | ✅ | ❌ | ✅ High | 🟢 Low | 🟢 None |
| Dynamic DLX | ✅ | ❌ | ✅ High | 🟡 Medium | 🟢 Low |
| Priority | ✅ | ❌ | 🟡 Medium | 🔴 High | 🟡 Moderate |
| Lazy Queue | ✅ | ❌ | ⚠️ Partial | 🔴 N/A | N/A |
| **Exponential Backoff** | ❌ | ❌ | ✅ High | 🟡 Medium | 🟡 Moderate |
| **Content-Based DLQ** | ❌ | ❌ | ✅ High | 🟡 Medium | 🟡 Moderate |
| **Auto Replay** | ❌ | ❌ | ✅ High | 🟢 Low | 🟢 Low |
| **Message Transform** | ❌ | ❌ | ✅ High | 🟢 Low | 🟢 None |
| **DLQ Query** | ❌ | ❌ | 🟡 Medium | 🟡 Medium | 🟡 Variable |

---

## 4. Priority Recommendations

### Quick Wins (Low difficulty, high value)

1. ✅ **DLQ Reason** - Add `_dlq_reason` to messages
2. ✅ **Message Transform** - Complete metadata
3. ✅ **Max Length** - Use native `MAXLEN`
4. ✅ **Explicit NACK** - New Lua function

### Medium Term

5. 🟡 **TTL** - With Sorted Set or Lua check
6. 🟡 **Exponential Backoff** - Integrate with Scheduled Messages
7. 🟡 **Dynamic DLX** - Configuration per stream

### Long Term / Complex

8. 🔴 **Priority Queues** - Multiple streams architecture
9. 🔴 **DLQ Query** - Redis Search integration

