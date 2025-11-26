# Redis DLQ Implementation Guide

## Overview

This document provides a comprehensive guide to the Redis Dead Letter Queue (DLQ) implementation with Java 21, Spring Boot 3.5.7, Jedis 7.1.0, and Angular 21.

## Backend Implementation Status

### ✅ Completed Components

1. **Build Configuration** (`pom.xml`)
   - Spring Boot 3.5.7 (latest stable)
   - Jedis 7.1.0 (supports Redis 8.4)
   - WebSocket support
   - Validation, Lombok, Jackson

2. **Configuration**
   - `application.yml` - Redis, DLQ, WebSocket, and logging configuration
   - `RedisConfig.java` - JedisPool bean with connection pooling
   - `RedisProperties.java` - Type-safe Redis configuration
   - `DLQProperties.java` - DLQ-specific configuration
   - `WebSocketConfig.java` - WebSocket endpoint configuration

3. **Core Services**
   - `RedisLuaFunctionLoader.java` - Loads claim_or_dlq function from existing lua/stream_utils.claim_or_dlq.lua (NO DUPLICATION)
   - `DLQMessagingService.java` - Core DLQ operations
   - `DLQTestScenarioService.java` - Step-by-step and high-volume test scenarios
   - `WebSocketEventService.java` - Real-time event broadcasting

4. **DTOs**
   - `DLQParameters.java` - Configurable DLQ parameters
   - `DLQEvent.java` - WebSocket event model
   - `DLQResponse.java` - API response model
   - `TestScenarioRequest.java` - Test scenario configuration

5. **Controllers**
   - `DLQController.java` - REST API endpoints for DLQ operations

6. **WebSocket**
   - `DLQEventWebSocketHandler.java` - WebSocket connection handler

### Key Features Implemented

- **Connection Pooling**: Shared JedisPool across all patterns
- **Lua Function Loading**: Sources existing script (no duplication)
- **Atomic Operations**: Uses claim_or_dlq Lua function
- **Real-time Events**: WebSocket broadcasting
- **Comprehensive Logging**: SLF4J with detailed context
- **Error Handling**: Try-with-resources, proper exception handling
- **Thread Safety**: Concurrent collections, atomic operations
- **Async Processing**: @Async for long-running tests
- **Validation**: Jakarta validation on all inputs

## Running the Backend

```bash
# Build the project
mvn clean install

# Run the application
mvn spring-boot:run

# Or run the JAR
java -jar target/redis-messaging-patterns-1.0.0.jar
```

The backend will:
1. Start on port 8080
2. Connect to Redis (localhost:6379 by default)
3. Load the claim_or_dlq Lua function from lua/stream_utils.claim_or_dlq.lua
4. Expose REST API at http://localhost:8080/api/dlq/*
5. Expose WebSocket at ws://localhost:8080/ws/dlq-events

## API Endpoints

### DLQ Operations

- `POST /api/dlq/init` - Initialize consumer group
- `POST /api/dlq/claim` - Execute claim_or_dlq
- `POST /api/dlq/produce` - Produce a test message
- `GET /api/dlq/stats` - Get stream statistics
- `DELETE /api/dlq/cleanup` - Clean up streams

### Test Scenarios

- `POST /api/dlq/test` - Run test scenario (step-by-step or high-volume)
- `POST /api/dlq/test/stop` - Stop high-volume test
- `GET /api/dlq/test/status` - Get test status

### WebSocket

- `ws://localhost:8080/ws/dlq-events` - Real-time event stream

## Frontend Implementation (Angular 21)

### Required Setup

```bash
# Create Angular 21 application
ng new redis-dlq-frontend --routing --style=scss
cd redis-dlq-frontend

# Install dependencies
npm install @stomp/stompjs sockjs-client
npm install --save-dev @types/sockjs-client
```

### Project Structure

```
frontend/
├── src/
│   ├── app/
│   │   ├── components/
│   │   │   ├── dlq-dashboard/
│   │   │   │   ├── dlq-dashboard.component.ts
│   │   │   │   ├── dlq-dashboard.component.html
│   │   │   │   └── dlq-dashboard.component.scss
│   │   │   ├── parameter-panel/
│   │   │   ├── event-log/
│   │   │   ├── statistics-panel/
│   │   │   └── test-controls/
│   │   ├── services/
│   │   │   ├── dlq-api.service.ts
│   │   │   └── websocket.service.ts
│   │   └── models/
│   │       ├── dlq-parameters.model.ts
│   │       ├── dlq-event.model.ts
│   │       └── test-scenario.model.ts
│   └── environments/
│       └── environment.ts
```

### Key Components to Implement

1. **DLQ Dashboard** - Main container with tabs for step-by-step and high-volume tests
2. **Parameter Panel** - Form for configuring DLQ parameters (pre-filled with defaults)
3. **Event Log** - Real-time display of WebSocket events
4. **Statistics Panel** - Live stats (stream length, DLQ length, pending count)
5. **Test Controls** - Buttons to start/stop tests

### WebSocket Service Example

```typescript
import { Injectable } from '@angular/core';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Subject } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class WebSocketService {
  private client: Client;
  private eventSubject = new Subject<any>();
  
  connect() {
    this.client = new Client({
      webSocketFactory: () => new SockJS('http://localhost:8080/ws/dlq-events'),
      onConnect: () => {
        this.client.subscribe('/topic/dlq-events', (message) => {
          this.eventSubject.next(JSON.parse(message.body));
        });
      }
    });
    this.client.activate();
  }
  
  getEvents() {
    return this.eventSubject.asObservable();
  }
}
```

## Testing the Implementation

### Step-by-Step Test

1. Open the web interface
2. Configure parameters (use defaults: maxDeliveries=2)
3. Click "Run Step-by-Step"
4. Watch events in real-time:
   - Message produced
   - Message consumed (pending)
   - Message reclaimed
   - Message acknowledged
   - Second message goes to DLQ

### High-Volume Test

1. Configure parameters
2. Set message count (e.g., 5000)
3. Set failure rate (e.g., 1%)
4. Click "Run High-Volume Test"
5. Watch progress updates
6. See DLQ accumulate failed messages
7. Click "Stop" to halt execution

## Best Practices Implemented

- ✅ No code duplication (Lua script sourced from original location)
- ✅ Specified versions (Spring Boot 3.5.7, Jedis 7.1.0, Angular 21, Redis 8.4)
- ✅ Lua script loaded on application startup after Redis connection
- ✅ Comprehensive logging with context
- ✅ Proper error handling and resource cleanup
- ✅ Thread-safe operations
- ✅ Detailed code comments for maintainability
- ✅ Validation on all inputs
- ✅ Real-time event streaming
- ✅ Async processing for long operations
- ✅ Connection pooling for performance
- ✅ Docker-ready configuration

## Next Steps

1. Complete Angular 21 frontend implementation
2. Add unit tests for services
3. Add integration tests
4. Configure CORS properly for production
5. Add authentication/authorization
6. Add monitoring and metrics
7. Dockerize the application
8. Deploy to production environment

