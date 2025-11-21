<div align="center">

# 🚀 Redis Messaging Patterns

**Enterprise-grade messaging patterns using Redis Streams and Redis Functions**

[![Redis](https://img.shields.io/badge/Redis-8.4-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io)
[![License](https://img.shields.io/badge/License-LGPL%202.1-blue?style=for-the-badge)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-21-DD0031?style=for-the-badge&logo=angular&logoColor=white)](https://angular.io)
[![Jedis](https://img.shields.io/badge/Jedis-7.1.0-DC382D?style=for-the-badge)](https://github.com/redis/jedis)

[Features](#-features) • [Installation](#-installation) • [Usage](#-testing-the-dlq-pattern) • [Documentation](#-function-reference) • [Contributing](#-contributing)

</div>

---

## 📋 Table of Contents

- [Overview](#-overview)
- [Features](#-features)
- [What is a Dead Letter Queue?](#-what-is-a-dead-letter-queue-dlq)
- [Technology Stack](#-technology-stack)
- [Prerequisites](#-prerequisites)
- [Installation](#-installation)
- [Testing](#-testing-the-dlq-pattern)
- [Function Reference](#-function-reference)
- [Project Structure](#-project-structure)
- [Roadmap](#-roadmap)
- [Contributing](#-contributing)
- [License](#-license)

---

## 🎯 Overview

This project demonstrates various messaging patterns using Redis Streams and Redis Functions. The first pattern implemented is the **Dead Letter Queue (DLQ)** pattern, providing a robust solution for handling message processing failures in distributed systems.

## ✨ Features

- ✅ **Dead Letter Queue (DLQ)** pattern implementation
- ✅ Automatic message routing based on delivery thresholds
- ✅ Built with Redis Functions for atomic operations
- ✅ **Real-time monitoring** with WebSocket streaming
- ✅ **Interactive web UI** with Angular 21
- ✅ **Background stream monitoring** for live updates
- ✅ Comprehensive test suite with interactive demos
- ✅ Production-ready error handling
- 🔜 Additional messaging patterns coming soon

---

## 💡 What is a Dead Letter Queue (DLQ)?

A Dead Letter Queue is a messaging pattern used to handle messages that cannot be processed successfully after multiple attempts. Instead of losing these problematic messages or blocking the processing pipeline, they are automatically moved to a separate queue (the "dead letter queue") for later inspection, debugging, or manual intervention.

### Why Use a DLQ?

- **Fault Tolerance**: Prevents message loss when processing fails repeatedly
- **System Resilience**: Keeps the main processing pipeline flowing by isolating problematic messages
- **Debugging**: Provides a dedicated place to inspect and analyze failed messages
- **Monitoring**: Enables tracking of failure patterns and system health

### How It Works in This Project

1. **Message Processing**: Messages are consumed from a Redis Stream using consumer groups
2. **Delivery Tracking**: Redis automatically tracks how many times each message has been delivered
3. **Threshold Check**: The `claim_or_dlq` function monitors pending messages and their delivery counts
4. **Automatic Routing**: 
   - Messages below the delivery threshold are reclaimed for reprocessing
   - Messages at or above the threshold are copied to the DLQ stream and acknowledged in the main stream

## 🛠 Technology Stack

| Technology | Purpose |
|------------|---------|
| **Redis 8.4** | In-memory data store with Streams and Functions |
| **Redis Streams** | Message queue and event streaming |
| **Redis Functions** | Server-side Lua scripting for atomic operations |
| **Java 21** | Backend application with Spring Boot |
| **Jedis 7.1.0** | Redis Java client library |
| **Spring Boot 3.5.7** | Enterprise Java framework |
| **Angular 21** | Modern frontend framework |
| **WebSocket** | Real-time bidirectional communication |
| **Bash** | Automation and testing scripts |

---

## 📦 Prerequisites

- **Docker** and **Docker Compose** (recommended)
- **Bash** shell for running scripts
- **redis-cli** (included in Docker container)

> **Note**: This project has been tested with **Redis 8.4**. Redis Functions require Redis 7.0+ and Redis Streams require Redis 5.0+.

---

## 🚀 Installation

### Step 1: Start Redis with Docker

The easiest way to get started is using Docker:

```bash
# Start Redis 8.4 in a Docker container
docker run -d \
  --name redis-messaging \
  -p 6379:6379 \
  redis:8.4-alpine

# Verify Redis is running
docker exec redis-messaging redis-cli PING
# Expected output: PONG
```

**Alternative: Using Docker Compose**

Create a `docker-compose.yml` file (if not already present):

```yaml
version: '3.8'
services:
  redis:
    image: redis:8.4-alpine
    container_name: redis-messaging
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes

volumes:
  redis-data:
```

Then start Redis:

```bash
docker-compose up -d
```

### Step 2: Configure Redis Connection

Create or edit the `.env` file in the project root:

```bash
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
```

For remote or secured Redis instances, you can also set:
- `REDIS_USER`: Redis username (optional)
- `REDIS_PASS`: Redis password (optional)
- `REDIS_TLS`: Set to `1` to enable TLS (optional)

### Step 3: Load the Redis Function

Navigate to the `lua` directory and run the load script:

```bash
cd lua
./load.sh
```

This script will:
1. Load the `claim_or_dlq` function into Redis
2. Display the list of loaded functions to confirm installation

Expected output:
```
OK
1) 1) "library_name"
   2) "stream_utils"
   3) "engine"
   4) "LUA"
   ...
```

---

## 🧪 Testing the DLQ Pattern

Run the interactive test script to see the DLQ pattern in action:

```bash
cd lua
./test_dlq.sh
```

### What the Test Script Demonstrates

The script runs two scenarios:

#### Scenario 1: Nominal Path (No DLQ)
1. A message is added to the stream
2. Consumer `c1` reads it without acknowledging (deliveries = 1)
3. Consumer `c2` reclaims the message using `claim_or_dlq` (deliveries < threshold)
4. The message is processed and acknowledged
5. **Result**: Message stays in the main stream, DLQ remains empty

#### Scenario 2: DLQ Path (Threshold Exceeded)
1. A new message is added to the stream
2. Consumer `c1` reads it without acknowledging (deliveries = 1)
3. Consumer `c2` reclaims it (deliveries = 2, at threshold)
4. Consumer `c3` calls `claim_or_dlq` again (deliveries >= maxDeliveries)
5. **Result**: Message is copied to the DLQ stream and acknowledged in the main stream

The script is interactive and will pause before each command, allowing you to see exactly what's happening at each step.

---

## 🏗️ Real-Time Monitoring Architecture

The application provides real-time monitoring of Redis Streams through a WebSocket-based architecture:

```
Redis Stream (test-stream, test-stream:dlq)
    ↓
StreamMonitorService (@Scheduled every 500ms)
    ↓ XREADGROUP (consumer group: monitor-group)
Detect new messages
    ↓
WebSocketEventService.broadcastEvent()
    ↓ WebSocket (SockJS)
All connected clients
    ↓
StreamViewerComponent (Angular)
    ↓
Real-time display in UI
```

### Key Components

#### Backend (Spring Boot)

| Component | Purpose | Location |
|-----------|---------|----------|
| **StreamMonitorService** | Background service that polls Redis Streams every 500ms and broadcasts new messages | `src/main/java/com/redis/patterns/service/StreamMonitorService.java` |
| **WebSocketEventService** | Manages WebSocket sessions and broadcasts events to all connected clients | `src/main/java/com/redis/patterns/service/WebSocketEventService.java` |
| **DLQEventWebSocketHandler** | Handles WebSocket connection lifecycle (connect, disconnect, errors) | `src/main/java/com/redis/patterns/websocket/DLQEventWebSocketHandler.java` |
| **DLQController** | REST API endpoints for message retrieval and DLQ operations | `src/main/java/com/redis/patterns/controller/DLQController.java` |
| **DLQMessagingService** | Core service for Redis Stream operations (XADD, XREADGROUP, XACK) | `src/main/java/com/redis/patterns/service/DLQMessagingService.java` |

#### Frontend (Angular 21)

| Component | Purpose | Location |
|-----------|---------|----------|
| **StreamViewerComponent** | Reusable component that displays stream messages with real-time updates | `frontend/src/app/components/stream-viewer/` |
| **WebSocketService** | Manages WebSocket connection using SockJS with automatic reconnection | `frontend/src/app/services/websocket.service.ts` |
| **RedisApiService** | HTTP client for REST API calls (initial data loading) | `frontend/src/app/services/redis-api.service.ts` |
| **DLQComponent** | Main page displaying two stream viewers (main + DLQ) | `frontend/src/app/components/dlq/` |

### How It Works

1. **Initial Load**: When the page loads, `StreamViewerComponent` fetches existing messages via REST API (`GET /api/dlq/messages`)
2. **WebSocket Connection**: Component connects to WebSocket endpoint (`/api/ws/dlq-events`) using SockJS
3. **Background Monitoring**: `StreamMonitorService` continuously polls Redis Streams using `XREADGROUP`
4. **Event Broadcasting**: New messages are broadcast to all WebSocket clients as `MESSAGE_PRODUCED` events
5. **Real-Time Display**: Angular components receive events and update the UI instantly

### Testing Real-Time Updates

Add a message to Redis and watch it appear instantly in the UI:

```bash
redis-cli XADD test-stream * type order.shipped order_id 9999 tracking "TEST123"
```

---

## 📚 Function Reference

### `claim_or_dlq`

Claims pending messages from a Redis Stream and routes them based on delivery count.

**Syntax:**
```bash
FCALL claim_or_dlq 2 <stream> <dlq_stream> <group> <consumer> <minIdle> <count> <maxDeliveries>
```

**Parameters:**
- `stream`: Source stream name
- `dlq_stream`: Dead letter queue stream name
- `group`: Consumer group name
- `consumer`: Consumer name claiming the messages
- `minIdle`: Minimum idle time in milliseconds before a message can be claimed
- `count`: Maximum number of pending messages to process
- `maxDeliveries`: Delivery threshold (messages at or above this count go to DLQ)

**Returns:**
Array of messages reclaimed for processing (excludes messages sent to DLQ)

**Example:**
```bash
redis-cli FCALL claim_or_dlq 2 mystream mystream:dlq mygroup worker1 5000 100 3
```

This claims up to 100 messages idle for at least 5 seconds, sending those with 3+ deliveries to the DLQ.

---

## 📁 Project Structure

```
.
├── lua/
│   ├── stream_utils.claim_or_dlq.lua       # Redis Function implementation
│   ├── load.sh                              # Script to load the function into Redis
│   └── test_dlq.sh                          # Interactive test script
├── src/main/java/com/redis/patterns/
│   ├── controller/
│   │   └── DLQController.java               # REST API endpoints
│   ├── service/
│   │   ├── DLQMessagingService.java         # Core Redis Stream operations
│   │   ├── StreamMonitorService.java        # Background stream monitoring
│   │   ├── WebSocketEventService.java       # WebSocket event broadcasting
│   │   └── DLQTestScenarioService.java      # Test scenario execution
│   ├── websocket/
│   │   └── DLQEventWebSocketHandler.java    # WebSocket connection handler
│   ├── dto/
│   │   └── DLQEvent.java                    # Event data transfer object
│   └── config/
│       ├── WebSocketConfig.java             # WebSocket configuration
│       └── RedisConfig.java                 # Redis connection pool
├── frontend/src/app/
│   ├── components/
│   │   ├── stream-viewer/                   # Reusable stream viewer component
│   │   └── dlq/                             # DLQ page with dual stream viewers
│   └── services/
│       ├── websocket.service.ts             # WebSocket client (SockJS)
│       └── redis-api.service.ts             # HTTP client for REST API
├── .env                                     # Redis connection configuration
└── LICENSE                                  # LGPL 2.1 License
```

---

## 🗺 Roadmap

This project will be expanded to include additional messaging patterns:

- [ ] **Retry with Exponential Backoff** - Intelligent retry mechanisms
- [ ] **Message Prioritization** - Priority-based message processing
- [ ] **Batch Processing** - Efficient bulk message handling
- [ ] **Message Deduplication** - Prevent duplicate message processing
- [ ] **Circuit Breaker** - Fault tolerance pattern
- [ ] **Saga Pattern** - Distributed transaction management

---

## 🤝 Contributing

Contributions are welcome! Here's how you can help:

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/amazing-pattern`)
3. **Commit** your changes (`git commit -m 'Add amazing messaging pattern'`)
4. **Push** to the branch (`git push origin feature/amazing-pattern`)
5. **Open** a Pull Request

### Guidelines

- Follow existing code style and conventions
- Add tests for new messaging patterns
- Update documentation as needed
- Ensure all tests pass before submitting

---

## 👥 Contributors

<a href="https://github.com/yourusername/RedisDLQJedis/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=yourusername/RedisDLQJedis" />
</a>

---

## 📄 License

This project is licensed under the **GNU Lesser General Public License v2.1** - see the [LICENSE](./LICENSE) file for details.

### What this means:

- ✅ You can use this library in commercial applications
- ✅ You can modify and distribute the code
- ✅ You must disclose source code changes
- ✅ You must include the original license

---

## 🙏 Acknowledgments

- Built with [Redis](https://redis.io) - The world's fastest in-memory database
- Inspired by enterprise messaging patterns and best practices
- Thanks to the Redis community for excellent documentation

---

<div align="center">

**[⬆ Back to Top](#-redis-messaging-patterns)**

Made with ❤️ for the Redis community

</div>

