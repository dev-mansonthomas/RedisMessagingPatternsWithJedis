# Project Context for AI Assistants

> **Purpose**: This document helps AI assistants understand the project quickly.
> **Last Updated**: 2025-12-04

## Project Overview

**RedisMessagingPatternsWithJedis** is a demo application showcasing Redis messaging patterns:
- **Dead Letter Queue (DLQ)** - Failed message handling with retry logic
- **Publish/Subscribe (Pub/Sub)** - Fire-and-forget messaging
- **Request/Reply** - Synchronous-like pattern with correlation IDs and timeout handling

## Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 21 | Virtual Threads for lightweight concurrency |
| Spring Boot | 3.5.7 | Backend framework |
| Jedis | 7.1.0 | Redis client |
| Redis | 8.4+ | XREADGROUP CLAIM option (8.4.0+) |
| Angular | 21 | Frontend SPA |
| WebSocket/SockJS | - | Real-time UI updates |

## Architecture Summary

```
┌───────────────────────────────────────────────────────────────────-──┐
│                         Angular Frontend                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                   │
│  │ DLQ Page    │  │ Pub/Sub Page│  │ Req/Reply   │                   │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘                   │
│         │                │                │                          │
│         └────────────────┼────────────────┘                          │
│                          │ WebSocket (SockJS)                        │
└──────────────────────────┼───────────────────────────────────────────┘
                           │
┌──────────────────────────┼───────────────────────────────────────────┐
│                   Spring Boot Backend                                │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ RedisStreamListenerService (Virtual Threads + XREAD BLOCK)      │ │
│  │ - Monitors streams for new messages                             │ │
│  │ - Broadcasts MESSAGE_PRODUCED via WebSocket                     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐   │
│  │ DLQMessaging    │  │ PubSubService   │  │ RequestReplyService │   │
│  │ Service         │  │                 │  │                     │   │
│  └────────┬────────┘  └────────┬────────┘  └──────────┬──────────┘   │
└───────────┼────────────────────┼─────────────────────┼───────────────┘
            │                    │                     │
┌───────────┼────────────────────┼─────────────────────┼───────────────┐
│           │                    │                     │    Redis      │
│  ┌────────▼────────┐  ┌────────▼────────┐  ┌────────▼────────┐       │
│  │ Redis Streams   │  │ Redis Pub/Sub   │  │ Redis Streams   │       │
│  │ + Consumer      │  │ Channels        │  │ + Keyspace      │       │
│  │   Groups        │  │                 │  │   Notifications │       │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘       │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ Lua Functions (stream_utils library)                            │ │
│  │ - read_claim_or_dlq: DLQ pattern with XREADGROUP CLAIM          │ │
│  │ - request: Request/Reply request sender                         │ │
│  │ - response: Request/Reply response sender                       │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

## Key Concepts

### 1. DLQ Pattern
- Uses **Redis Streams** with **Consumer Groups**
- Messages that fail processing are retried up to `maxDeliveries` times
- After max retries, messages are routed to a DLQ stream
- Lua function `read_claim_or_dlq` handles atomic claim + DLQ routing

### 2. Pub/Sub Pattern
- Uses **Redis Pub/Sub** channels (not Streams)
- Fire-and-forget: messages not persisted
- Push model: subscribers receive messages immediately via always-active connection
- Backend uses `RedisPubSubListener` to subscribe and forward to WebSocket

### 3. Request/Reply Pattern
- Uses **Redis Streams** for requests and responses
- **Correlation ID** links request to response
- **Timeout handling** via Redis keyspace notifications (`__keyevent@0__:expired`)
- Lua functions `request` and `response` ensure atomicity
- Multiple workers can process requests without duplicate processing (Consumer Groups)

### 4. Virtual Threads + XREAD BLOCK
- `RedisStreamListenerService` uses Java 21 Virtual Threads
- One lightweight thread per monitored stream
- `XREAD BLOCK 1000` provides push-like behavior with 1-second timeout
- Detects ALL messages (API + external sources like Redis Insight)

### 5. Single Consumer Architecture
- **Visualization**: Uses `XREVRANGE` (read-only, no PENDING entries)
- **Processing**: Uses `XREADGROUP` with `test-group` consumer group
- This prevents phantom messages in the UI

## Key Files

| File | Purpose |
|------|---------|
| `lua/stream_utils.lua` | All Lua functions (read_claim_or_dlq, request, response) |
| `src/.../service/RedisLuaFunctionLoader.java` | Loads Lua on Spring Boot startup |
| `src/.../service/RedisStreamListenerService.java` | XREAD BLOCK with Virtual Threads |
| `src/.../service/DLQMessagingService.java` | DLQ operations (produce, claim, ack) |
| `src/.../service/PubSubService.java` | Pub/Sub publish |
| `src/.../service/RequestReplyService.java` | Request/Reply with timeout handling |
| `src/.../config/KeyspaceNotificationConfig.java` | Timeout detection via key expiration |
| `src/.../config/RedisPubSubListener.java` | Pub/Sub subscriber |
| `frontend/src/app/services/websocket.service.ts` | WebSocket client (SockJS) |
| `frontend/src/app/components/stream-viewer/` | Reusable stream viewer component |

## WebSocket Events

| Event Type | Trigger | Frontend Action |
|------------|---------|-----------------|
| `MESSAGE_PRODUCED` | New message in stream | Add to display |
| `MESSAGE_DELETED` | Message ACK'd or routed to DLQ | Remove from display |
| `MESSAGE_RECLAIMED` | Message reclaimed for retry | Ignore (processing event) |
| `INFO` | Informational | Ignore |
| `ERROR` | Error occurred | Ignore |

## Configuration

- **Redis**: `application.yml` → `redis.host`, `redis.port`
- **DLQ Config**: Stored in Redis hash `dlq:config:{streamName}`
  - `maxDeliveries`: Max retry attempts (default: 2)
  - `minIdleMs`: Min idle time before reclaim (default: 5000ms)

## Running the Project

```bash
# 1. Start Redis 8.4+
docker run -d --name redis-messaging -p 6379:6379 redis:8.4-alpine

# 2. Start Backend (Lua functions load automatically)
mvn spring-boot:run

# 3. Start Frontend
cd frontend && npm start

# 4. Open http://localhost:4200
```

## Important Notes

- **NOT production-ready**: Demo/educational material only
- **Lua functions auto-load**: Via `RedisLuaFunctionLoader.java` on startup
- **Context path**: Backend uses `/api` prefix
- **WebSocket endpoint**: `/api/ws/dlq-events` (with SockJS fallback)

