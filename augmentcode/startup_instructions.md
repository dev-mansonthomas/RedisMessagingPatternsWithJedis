# Redis Messaging Patterns - Startup Instructions

## Prerequisites

Before starting the application, ensure you have the following installed:

1. **Java 21** - Required for Spring Boot application
2. **Maven 3.8+** - For building the Spring Boot application
3. **Node.js 18+** - Required for Angular frontend
4. **npm 9+** - Comes with Node.js
5. **Redis 8.4+** - Redis server with Streams and Functions support

## Redis Setup

### Option 1: Using Docker (Recommended)

```bash
# Pull and run Redis 8.4
docker run -d --name redis-messaging \
  -p 6379:6379 \
  redis:8.4-alpine

# Verify Redis is running
docker ps | grep redis-messaging
```

### Option 2: Local Installation

Install Redis 8.4+ on your system and ensure it's running on port 6379.

```bash
# Start Redis server
redis-server

# In another terminal, verify connection
redis-cli ping
# Should return: PONG
```

## Load Redis Lua Functions

Before starting the Spring Boot application, load the required Lua functions:

```bash
# Navigate to the lua directory
cd lua

# Make the load script executable
chmod +x load.sh

# Load the Lua functions into Redis
./load.sh

# Verify the function is loaded
redis-cli FCALL claim_or_dlq 0
# Should return an error about missing arguments (this is expected and confirms the function exists)
```

## Starting the Spring Boot Backend

### Terminal 1: Spring Boot Application

```bash
# From the project root directory
cd /Users/thomas.manson/Projects/RedisDLQJedis

# Clean and build the project
mvn clean package -DskipTests

# Run the Spring Boot application
java -jar target/redis-messaging-patterns-1.0.0.jar

# Alternative: Run with Maven
mvn spring-boot:run
```

The Spring Boot application will start on **http://localhost:8080**

**Key Endpoints:**
- REST API: `http://localhost:8080/api/dlq/*`
- WebSocket: `ws://localhost:8080/api/ws/dlq-events` (with SockJS support)

**Note:** The context path is `/api`, so all endpoints are prefixed with `/api`.

**Verify Backend is Running:**
```bash
# Check health/status
curl http://localhost:8080/api/dlq/stats?streamName=test&dlqStreamName=test:dlq&groupName=test-group
```

## Starting the Angular Frontend

### Terminal 2: Angular Development Server

```bash
# Navigate to the frontend directory
cd /Users/thomas.manson/Projects/RedisDLQJedis/frontend

# Install dependencies (first time only)
npm install

# Start the development server
npm start
```

The Angular application will start on **http://localhost:4200**

**Alternative Commands:**
```bash
# Run with specific port
ng serve --port 4200

# Run with live reload
npm run watch
```

## Accessing the Application

Once both servers are running:

1. **Open your browser** and navigate to: **http://localhost:4200**

2. **Available Pages:**
   - **DLQ Dashboard**: http://localhost:4200/dlq
   - **Request/Reply**: http://localhost:4200/request-reply

3. **WebSocket Connection:**
   - The frontend automatically connects to the WebSocket endpoint
   - Check the browser console for connection status
   - Look for "WebSocket connection established" message

## Using the Stream Viewer Component

The `stream-viewer` component is now available for use in any page. Example usage:

```typescript
import { StreamViewerComponent } from './components/stream-viewer/stream-viewer.component';

@Component({
  // ...
  imports: [StreamViewerComponent],
  template: `
    <app-stream-viewer
      stream="my-stream"
      group="my-group"
      consumer="consumer-1"
      [pageSize]="10">
    </app-stream-viewer>
  `
})
```

**Component Parameters:**
- `stream`: Redis Stream name (required)
- `group`: Consumer Group name (required)
- `consumer`: Consumer name (required)
- `pageSize`: Number of messages to display (default: 10)

## Troubleshooting

### Redis Connection Issues

```bash
# Check if Redis is running
redis-cli ping

# Check Redis logs (Docker)
docker logs redis-messaging

# Verify Redis port
netstat -an | grep 6379
```

### Spring Boot Issues

```bash
# Check if port 8080 is available
lsof -i :8080

# View application logs
tail -f logs/application.log

# Check Redis connection in Spring Boot
curl http://localhost:8080/actuator/health
```

### Angular Issues

```bash
# Clear npm cache
npm cache clean --force

# Reinstall dependencies
rm -rf node_modules package-lock.json
npm install

# Check if port 4200 is available
lsof -i :4200
```

### WebSocket Connection Issues

1. **Check browser console** for WebSocket errors
2. **Verify backend is running** on port 8080
3. **Check CORS settings** in `WebSocketConfig.java`
4. **Try SockJS fallback** - it should work automatically

## Development Workflow

### Making Changes

**Backend Changes:**
1. Modify Java code
2. Rebuild: `mvn clean package -DskipTests`
3. Restart Spring Boot application

**Frontend Changes:**
1. Modify TypeScript/HTML/CSS
2. Changes are automatically reloaded (hot reload)
3. Check browser console for errors

### Running Tests

```bash
# Backend tests
mvn test

# Frontend tests
cd frontend
npm test

# Frontend linting
npm run lint
```

## Production Build

### Backend

```bash
mvn clean package -DskipTests
# JAR file will be in target/redis-messaging-patterns-1.0.0.jar
```

### Frontend

```bash
cd frontend
npm run build
# Build output will be in frontend/dist/
```

## Additional Resources

- **Redis Streams Documentation**: https://redis.io/docs/data-types/streams/
- **Spring Boot WebSocket**: https://spring.io/guides/gs/messaging-stomp-websocket/
- **Angular Documentation**: https://angular.io/docs
- **SockJS**: https://github.com/sockjs/sockjs-client

## Support

For issues or questions, check:
- `README.md` - Project overview
- `IMPLEMENTATION_GUIDE.md` - Detailed implementation guide
- `BUILD_STATUS.md` - Build and component status

