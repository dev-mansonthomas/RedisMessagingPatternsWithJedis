# Implementation Reference

> **Purpose**: Technical details for AI assistants making code changes.
> **Last Updated**: 2025-12-04

## Lua Functions (lua/stream_utils.lua)

### 1. `read_claim_or_dlq`
**Purpose**: DLQ pattern - claim pending messages and route to DLQ if max deliveries exceeded.

```
KEYS: [stream, dlq_stream]
ARGS: [group, consumer, minIdleMs, count, maxDeliveries]
RETURNS: [[messages_to_process], [dlq_ids]]
```

**Flow**:
1. `XPENDING` to find messages exceeding `maxDeliveries`
2. `XCLAIM` + `XADD` to DLQ + `XACK` for those messages
3. `XREADGROUP GROUP ... CLAIM minIdleMs STREAMS stream >` (Redis 8.4.0+)
4. Return messages to process and DLQ routing info

### 2. `request`
**Purpose**: Request/Reply - send request with timeout tracking.

```
KEYS: [timeout_key, shadow_key, stream_name]
ARGS: [correlation_id, business_id, stream_response_name, timeout_seconds, payload_json]
RETURNS: message_id
```

**Flow**:
1. `SET timeout_key business_id EX timeout` (triggers expiration event)
2. `HSET shadow_key businessId ... streamResponseName ...` (metadata for timeout handler)
3. `XADD stream_name * correlationId ... businessId ... payload...`

### 3. `response`
**Purpose**: Request/Reply - send response and cancel timeout.

```
KEYS: [timeout_key, stream_name]
ARGS: [correlation_id, business_id, payload_json]
RETURNS: message_id
```

**Flow**:
1. `DEL timeout_key` (prevents timeout event)
2. `XADD stream_name * correlationId ... businessId ... payload...`

---

## Java Services

### RedisStreamListenerService
**File**: `src/main/java/com/redis/patterns/service/RedisStreamListenerService.java`

**Key Implementation**:
```java
// Virtual Thread per stream
Thread.ofVirtual()
    .name("stream-listener-" + streamName)
    .start(new StreamMonitor(streamName));

// XREAD BLOCK in StreamMonitor.run()
jedis.xread(
    XReadParams.xReadParams().block(1000).count(100),
    Collections.singletonMap(streamName, lastId)
);
```

**Monitored streams on startup**: `test-stream`, `test-stream:dlq`

### DLQMessagingService
**File**: `src/main/java/com/redis/patterns/service/DLQMessagingService.java`

**Key Methods**:
- `produceMessage(streamName, payload)` → `XADD`
- `getNextMessages(params)` → Calls Lua `read_claim_or_dlq`
- `acknowledgeMessage(streamName, groupName, messageId)` → `XACK` + broadcasts `MESSAGE_DELETED`
- `getStreamMessages(streamName, count)` → `XREVRANGE` (visualization, no PENDING)

### RequestReplyService
**File**: `src/main/java/com/redis/patterns/service/RequestReplyService.java`

**Key Methods**:
- `sendRequest(...)` → Calls Lua `request`
- `sendResponse(...)` → Calls Lua `response`
- Uses `KeyspaceNotificationConfig` for timeout detection

### WorkQueueService
**File**: `src/main/java/com/redis/patterns/service/WorkQueueService.java`

**Key Implementation**:
```java
// 4 Virtual Thread workers started on application startup (CommandLineRunner)
for (int i = 1; i <= NUM_WORKERS; i++) {
    Thread.ofVirtual()
        .name("work-queue-worker-" + i)
        .start(() -> workerLoop(workerId, running));
}

// Worker loop polls every 100ms using read_claim_or_dlq
jedis.fcall("read_claim_or_dlq",
    Arrays.asList(JOB_STREAM, JOB_DLQ),
    Arrays.asList(JOB_GROUP, consumerName, "100", "1", "2"));
```

**Streams**:
- Input: `jobs.imageProcessing.v1`
- Done: `jobs.done.worker-{1-4}` (one per worker)
- DLQ: `jobs.imageProcessing.v1:dlq`

**Key Methods**:
- `produceJob(jobId, processingType, additionalFields)` → `XADD` to job stream
- `workerLoop(workerId, running)` → Polls and processes jobs
- `processMessage(...)` → OK: copy to done stream + ACK; Error: no ACK (retry/DLQ)

### KeyspaceNotificationConfig
**File**: `src/main/java/com/redis/patterns/config/KeyspaceNotificationConfig.java`

**Purpose**: Listens to `__keyevent@0__:expired` for Request/Reply timeout handling.

When timeout key expires:
1. Read shadow key for metadata (businessId, streamResponseName)
2. Send TIMEOUT response to response stream
3. Delete shadow key

---

## Angular Components

### StreamViewerComponent
**File**: `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

**Inputs**: `stream`, `group`, `consumer`, `pageSize`

**Key Behavior**:
- Loads initial data via `XREVRANGE` (REST API)
- Subscribes to WebSocket for real-time updates
- Filters events: only `MESSAGE_PRODUCED` adds messages, `MESSAGE_DELETED` removes

### WebSocketService
**File**: `frontend/src/app/services/websocket.service.ts`

**Endpoint**: `http://localhost:8080/api/ws/dlq-events` (SockJS)

**Note**: Requires `window.global = window;` polyfill in `index.html` for SockJS.

### StreamRefreshService
**File**: `frontend/src/app/services/stream-refresh.service.ts`

**Purpose**: Coordinates refresh across all StreamViewerComponents (e.g., after "Clear All Streams").

---

## API Endpoints

### DLQ Controller (`/api/dlq`)
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/produce` | Add message to stream |
| POST | `/process` | Get next messages (calls Lua) |
| POST | `/ack` | Acknowledge message |
| GET | `/stream/{name}` | Get messages (XREVRANGE) |
| DELETE | `/stream/{name}` | Delete stream |
| GET | `/stats` | Get stream statistics |

### Pub/Sub Controller (`/api/pubsub`)
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/publish` | Publish to channel |
| POST | `/subscribe` | Subscribe to channel |

### Request/Reply Controller (`/api/request-reply`)
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/request` | Send request |
| POST | `/response` | Send response |

### Work Queue Controller (`/api/work-queue`)
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/produce` | Produce a job (params: processingType=OK\|Error) |
| GET | `/streams` | Get stream names for this pattern |

---

## Common Issues & Solutions

### WebSocket not connecting
- Check context path: endpoint is `/api/ws/dlq-events`
- Ensure `setAllowedOriginPatterns("*")` (not `setAllowedOrigins`) for SockJS

### Messages not appearing in UI
- Check WebSocket event type: must be `MESSAGE_PRODUCED`
- Check stream name matches in event filtering

### DLQ routing not working
- Wait `minIdleMs` (default 5000ms) between retry attempts
- Check `maxDeliveries` config in Redis: `HGET dlq:config:test-stream maxDeliveries`

### Lua function not found
- Functions auto-load on startup via `RedisLuaFunctionLoader`
- Verify: `redis-cli FUNCTION LIST` should show `stream_utils` library

---

## Redis Commands Reference

```bash
# Stream operations
XADD test-stream * field1 value1
XREVRANGE test-stream + - COUNT 10
XLEN test-stream
XINFO GROUPS test-stream
XPENDING test-stream test-group

# Consumer group
XGROUP CREATE test-stream test-group $ MKSTREAM
XREADGROUP GROUP test-group consumer-1 COUNT 1 STREAMS test-stream >
XACK test-stream test-group <message-id>

# Lua functions
FUNCTION LIST
FCALL read_claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 5000 10 2

# Pub/Sub
PUBLISH my-channel "hello"
SUBSCRIBE my-channel

# Config
HGETALL dlq:config:test-stream
```

---

## Project Structure

```
├── lua/
│   └── stream_utils.lua          # All Lua functions
├── src/main/java/com/redis/patterns/
│   ├── config/
│   │   ├── RedisConfig.java              # JedisPool configuration
│   │   ├── WebSocketConfig.java          # WebSocket + SockJS setup
│   │   ├── KeyspaceNotificationConfig.java  # Timeout detection
│   │   └── RedisPubSubListener.java      # Pub/Sub subscriber
│   ├── controller/
│   │   ├── DLQController.java
│   │   ├── PubSubController.java
│   │   ├── RequestReplyController.java
│   │   └── WorkQueueController.java
│   ├── service/
│   │   ├── RedisLuaFunctionLoader.java   # Loads Lua on startup
│   │   ├── RedisStreamListenerService.java  # XREAD BLOCK + Virtual Threads
│   │   ├── DLQMessagingService.java
│   │   ├── PubSubService.java
│   │   ├── RequestReplyService.java
│   │   ├── WorkQueueService.java         # 4 Virtual Thread workers
│   │   └── WebSocketEventService.java
│   └── dto/
│       └── DLQEvent.java                 # WebSocket event model
├── frontend/src/app/
│   ├── components/
│   │   ├── dlq/                          # DLQ demo page
│   │   ├── pubsub/                       # Pub/Sub demo page
│   │   ├── request-reply/                # Request/Reply demo page
│   │   ├── work-queue/                   # Work Queue demo page
│   │   ├── stream-viewer/                # Reusable stream viewer
│   │   └── dlq-actions/                  # Process buttons
│   └── services/
│       ├── websocket.service.ts          # SockJS client
│       ├── redis-api.service.ts          # REST API client
│       └── stream-refresh.service.ts     # Refresh coordination
└── README.md                             # User-facing documentation
```

