<div align="center">

# 🚀 Redis Messaging Patterns

**Learn enterprise messaging patterns using Redis Streams, Redis Functions, and Java 21 Virtual Threads**

[![License](https://img.shields.io/badge/License-LGPL%202.1-blue?style=for-the-badge)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Redis](https://img.shields.io/badge/Redis-8.4-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io)
[![Jedis](https://img.shields.io/badge/Jedis-7.1.0-DC382D?style=for-the-badge)](https://github.com/redis/jedis)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-21-DD0031?style=for-the-badge&logo=angular&logoColor=white)](https://angular.io)


[Patterns](#-implemented-patterns) • [Getting Started](#-getting-started) • [Key Files](#-key-files-to-explore) • [Architecture](#-architecture)

</div>

---

## 📋 Table of Contents

- [What is This Project?](#-what-is-this-project)
- [Key Concepts](#-key-concepts-for-beginners)
- [Implemented Patterns](#-implemented-patterns)
- [Technology Stack](#-technology-stack)
- [Prerequisites](#-prerequisites)
- [Getting Started](#-getting-started)
- [Key Files to Explore](#-key-files-to-explore)
- [Architecture](#-architecture)
- [Contributing](#-contributing)
- [License](#-license)

---

## 🎯 What is This Project?

This project is a **learning resource** that demonstrates enterprise messaging patterns using Redis. It provides:

- **Working implementations** of messaging patterns 
    - DLQ
    - Pub/Sub
    - Request/Reply
- **Interactive web UI** to visualize and test each pattern in real-time
- **Demonstration code** with Redis Functions (Lua), Jedis, and Java 21 Virtual Threads

Whether you're new to messaging systems or Redis, this project helps you understand how to build reliable, scalable message-driven applications.

---

## 📚 Key Concepts for Beginners

### What is Messaging?

**Messaging** is a way for different parts of an application (or different applications) to communicate by sending and receiving messages through a message broker (like Redis).

```
┌──────────────┐     ┌────────────────┐     ┌──────────────┐
│   Producer   │ ──▶ │ Message Broker │ ──▶ │   Consumer   │
│ (sends msgs) │     │   (Redis)      │     │ (reads msgs) │
└──────────────┘     └────────────────┘     └──────────────┘
```

### What is Redis Streams?

**Redis Streams** is a data structure in Redis designed for **guaranteed messaging**. Think of it as an append-only log where:
- **Producers** add messages to the end of the stream
- **Consumers** poll the stream to read messages (pull model)
- **Consumer Groups** allow multiple consumers to share the workload
- **Messages are persisted** until explicitly deleted
- **Acknowledgment** ensures no message is lost

### What is Redis Pub/Sub?

**Redis Pub/Sub** is a **real-time push messaging** system where:
- **Publishers** send messages to channels
- **Subscribers** receive messages instantly via an **always-active connection**
- Messages are **not persisted** - if no subscriber is connected, the message is lost
- **No polling needed** - Redis pushes messages to subscribers automatically

**Streams vs Pub/Sub**:
| Feature | Redis Streams | Redis Pub/Sub |
|---------|--------------|---------------|
| Delivery model | Pull (polling) | Push (real-time) |
| Persistence | Yes | No |
| Guaranteed delivery | Yes | No |
| Connection | On-demand | Always active |
| Use case | Reliable queuing | Real-time notifications |

### What are Redis Functions?

**Redis Functions** allow you to run Lua scripts directly inside Redis. This ensures:
- **Atomicity**: Multiple operations execute as one
- **Performance**: No network round-trips for complex logic
- **Consistency**: Operations can't be interrupted

---

## ✨ Implemented Patterns

### 1. 📬 Dead Letter Queue (DLQ)

**What it solves**: When message processing fails repeatedly, messages are automatically moved to a separate queue instead of being lost.

**Use case**: E-commerce order processing where some orders fail validation.

**Key concepts**:
- Consumer Groups track message delivery count
- After N failed attempts, messages go to DLQ
- Failed messages can be inspected and reprocessed later

### 2. 📢 Publish/Subscribe (Pub/Sub)

**What it solves**: Send a message to multiple recipients simultaneously without knowing who they are.

**Use case**: Real-time notifications, chat systems, live updates.

**Key concepts**:
- Fire-and-forget: No delivery guarantee
- Fan-out: One message reaches all subscribers
- Ephemeral: Messages are not persisted

### 3. ↔️ Request/Reply

**What it solves**: Send a request and wait for a response, with automatic timeout handling. Multiple workers can process requests in parallel without duplicate processing.

**Use case**: Inventory check before order confirmation, distributed task processing.

**Key concepts**:
- Correlation ID links request to response
- Consumer Groups ensure each request is processed by exactly one worker
- Multiple workers can share the load (horizontal scaling)
- Timeout keys trigger automatic timeout responses
- Keyspace notifications detect key expiration

---

## 🛠 Technology Stack

| Technology | Purpose | Why We Use It |
|------------|---------|---------------|
| **Redis 8.4** | Message broker | In-memory speed, Streams support, Functions |
| **Redis Streams** | Message queuing | Persistence, consumer groups, delivery tracking |
| **Redis Functions** | Atomic operations | Run Lua scripts server-side for consistency |
| **Redis Pub/Sub** | Broadcast messaging | Real-time fan-out to multiple subscribers |
| **Jedis 7.1.0** | Redis client | Java library to interact with Redis |
| **Java 21** | Backend runtime | Virtual Threads for efficient I/O |
| **Spring Boot 3.5.7** | Web framework | REST API, WebSocket, dependency injection |
| **Angular 21** | Frontend | Real-time UI with WebSocket |
| **WebSocket** | Real-time comms | Push updates from server to browser |

---

## 📦 Prerequisites

Before starting, you need:

| Tool | Version | Purpose |
|------|---------|---------|
| **Docker** | Latest | Run Redis container |
| **Java** | 21+ | Run Spring Boot backend |
| **Maven** | 3.8+ | Build Java project |
| **Node.js** | 18+ | Run Angular frontend |
| **npm** | 9+ | Install frontend dependencies |

---

## 🚀 Getting Started

### Step 1: Start Redis

```bash
# Start Redis 8.4 with Docker
docker run -d --name redis-messaging -p 6379:6379 redis:8.4-alpine

# Verify it's running
docker exec redis-messaging redis-cli PING
# Expected: PONG
```

### Step 2: Start the Backend

```bash
# Build and run
mvn clean package -DskipTests
java -jar target/redis-messaging-patterns-1.0.0.jar

# Or with Maven directly
mvn spring-boot:run
```

Backend runs on **http://localhost:8080**

> **Note**: Lua functions are automatically loaded into Redis on Spring Boot startup via [`RedisLuaFunctionLoader.java`](src/main/java/com/redis/patterns/service/RedisLuaFunctionLoader.java). No manual loading required.

### Step 3: Start the Frontend

```bash
cd frontend
npm install
npm start
```

Frontend runs on **http://localhost:4200**

---

## 📂 Key Files to Explore

This section highlights the most important files for understanding each pattern.

### 🔧 Lua Functions (Server-Side Logic)

| File | Description |
|------|-------------|
| **[`lua/stream_utils.lua`](lua/stream_utils.lua)** | **All Redis Functions in one file**: `read_claim_or_dlq`, `request`, `response` |

**What to look for**:
- `read_claim_or_dlq` (line 59): Uses Redis 8.4's `XREADGROUP CLAIM` for atomic claim+read
- `request` (line 188): Creates timeout tracking keys with `SET EX` and posts to stream
- `response` (line 279): Deletes timeout key and posts response

### ☕ Java Services (Backend Logic)

#### DLQ Pattern

| File | Key Concepts |
|------|--------------|
| **[`DLQMessagingService.java`](src/main/java/com/redis/patterns/service/DLQMessagingService.java)** | Jedis `fcall()` to invoke Lua functions, `XADD`, `XREADGROUP`, `XACK` |
| **[`RedisStreamListenerService.java`](src/main/java/com/redis/patterns/service/RedisStreamListenerService.java)** | **Virtual Threads** + `XREAD BLOCK` for real-time stream monitoring |

#### Pub/Sub Pattern

| File | Key Concepts |
|------|--------------|
| **[`PubSubService.java`](src/main/java/com/redis/patterns/service/PubSubService.java)** | Jedis `publish()` for fire-and-forget messaging |
| **[`RedisPubSubListener.java`](src/main/java/com/redis/patterns/config/RedisPubSubListener.java)** | Jedis `JedisPubSub` for subscribing to channels |

#### Request/Reply Pattern

| File | Key Concepts |
|------|--------------|
| **[`RequestReplyService.java`](src/main/java/com/redis/patterns/service/RequestReplyService.java)** | Full pattern: request, response, timeout handling with Virtual Threads |
| **[`KeyspaceNotificationConfig.java`](src/main/java/com/redis/patterns/config/KeyspaceNotificationConfig.java)** | Redis keyspace notifications for timeout detection |

### 🌐 WebSocket (Real-Time Communication)

| File | Description |
|------|-------------|
| **[`WebSocketEventService.java`](src/main/java/com/redis/patterns/service/WebSocketEventService.java)** | Broadcasts events to all connected Angular clients |
| **[`websocket.service.ts`](frontend/src/app/services/websocket.service.ts)** | SockJS client with automatic reconnection |

---

## 🏗️ Architecture

### How Virtual Threads Monitor Streams

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Spring Boot Application                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────────────┐    ┌──────────────────────┐               │
│  │  Virtual Thread #1   │    │  Virtual Thread #2   │               │
│  │  (stream-listener-   │    │  (request-listener)  │               │
│  │   test-stream)       │    │                      │               │
│  └──────────┬───────────┘    └──────────┬───────────┘               │
│             │                            │                          │
│             │ XREAD BLOCK 1000           │ XREADGROUP BLOCK 5000    │
│             │                            │                          │
│             ▼                            ▼                          │
│  ┌──────────────────────────────────────────────────────┐           │
│  │                 JedisPool (Connection Pool)          │           │
│  └────────────────────────-──┬──────────────────────────┘           │
│                              │                                      │
└──────────────────────────────┼──────────────────────────────────────┘
                               │
                               ▼
                    ┌────────────────────-─┐
                    │        Redis         │
                    │   (Streams, Pub/Sub) │
                    └────────────────────-─┘
```

**Why Virtual Threads?**
- Lightweight: Millions of threads possible
- Blocking I/O is efficient (no thread pool exhaustion)
- Perfect for `XREAD BLOCK` and `XREADGROUP BLOCK`

### WebSocket Event Flow

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Redis Stream   │     │  Spring Boot    │     │  Angular App    │
│                 │     │                 │     │                 │
│  test-stream    │────▶│ XREAD BLOCK     │────▶│ WebSocket       │
│  test-stream:dlq│     │                 │     │ Service         │
└─────────────────┘     │ ──────────────  │     │ ─────────────   │
                        │ WebSocket       │     │ Updates UI      │
                        │ EventService    │     │                 │
                        └─────────────────┘     └─────────────────┘
```

---

## 📚 Redis Functions Reference

### `read_claim_or_dlq` (DLQ Pattern)

Claims pending messages and routes failed ones to DLQ.

```bash
FCALL read_claim_or_dlq 2 <stream> <dlq> <group> <consumer> <minIdle> <count> <maxDeliver>
```

**Example**:
```bash
redis-cli FCALL read_claim_or_dlq 2 orders orders:dlq order-group worker1 5000 100 3
```

### `request` (Request/Reply Pattern)

Sends a request with automatic timeout tracking.

```bash
FCALL request 3 <timeout_key> <shadow_key> <stream> <correlationId> <businessId> <responseStream> <timeout> <payloadJson>
```

### `response` (Request/Reply Pattern)

Sends a response and cancels the timeout.

```bash
FCALL response 2 <timeout_key> <stream> <correlationId> <businessId> <payloadJson>
```

---

## 📁 Project Structure

```
RedisMessagingPatternsWithJedis/
├── lua/
│   └── stream_utils.lua              # All Lua functions (DLQ + Request/Reply)
│
├── src/main/java/com/redis/patterns/
│   ├── service/
│   │   ├── DLQMessagingService.java       # DLQ operations with Jedis
│   │   ├── PubSubService.java             # Pub/Sub publish
│   │   ├── RequestReplyService.java       # Request/Reply with Virtual Threads
│   │   ├── RedisStreamListenerService.java # XREAD BLOCK with Virtual Threads
│   │   └── WebSocketEventService.java     # Broadcast to Angular
│   ├── config/
│   │   ├── RedisConfig.java               # JedisPool configuration
│   │   ├── RedisPubSubConfig.java         # Pub/Sub subscriber setup
│   │   └── KeyspaceNotificationConfig.java # Timeout detection
│   └── controller/
│       ├── DLQController.java             # REST API for DLQ
│       ├── PubSubController.java          # REST API for Pub/Sub
│       └── RequestReplyController.java    # REST API for Request/Reply
│
├── frontend/src/app/
│   ├── components/
│   │   ├── dlq/                           # DLQ demo page
│   │   ├── pubsub/                        # Pub/Sub demo page
│   │   ├── request-reply/                 # Request/Reply demo page
│   │   └── stream-viewer/                 # Reusable stream viewer
│   └── services/
│       ├── websocket.service.ts           # WebSocket client
│       └── redis-api.service.ts           # HTTP client
│
└── README.md                              # You are here!
```

---

## 🤝 Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Submit a Pull Request

---

## 📄 License

**GNU Lesser General Public License v2.1** - See [LICENSE](./LICENSE)

---

<div align="center">

**[⬆ Back to Top](#-redis-messaging-patterns)**

Made with ❤️ for the Redis community

</div>

