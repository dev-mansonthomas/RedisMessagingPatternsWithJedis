# Troubleshooting Guide

## Common Issues and Solutions

### 1. SockJS "global is not defined" Error

**Error Message:**
```
ReferenceError: global is not defined
    at node_modules/sockjs-client/lib/utils/browser-crypto.js
```

**Cause:**
SockJS library expects a `global` variable that exists in Node.js but not in browser environments. This is a common issue with libraries that support both Node.js and browser environments.

**Solution:**
Add a polyfill in `frontend/src/index.html`:

```html
<head>
  <!-- ... other head content ... -->
  <script>
    // Polyfill for SockJS compatibility
    window.global = window;
  </script>
</head>
```

**Status:** ✅ Fixed in commit

---

### 2. WebSocket Connection Failed (404 Not Found)

**Error Message:**
```
WebSocket connection to 'ws://localhost:8080/ws/dlq-events' failed
```

**Cause:**
The Spring Boot application uses a context path `/api`, so the WebSocket endpoint is at `/api/ws/dlq-events`, not `/ws/dlq-events`.

**Solution:**
The WebSocket service has been updated to use the correct URL:

```typescript
// Correct URL with context path
const url = `http://localhost:8080/api${endpoint}`;
```

**Configuration:**
Check `src/main/resources/application.yml`:
```yaml
server:
  servlet:
    context-path: /api
```

**Status:** ✅ Fixed in commit

---

### 3. Navigation Not Working / Blank Page

**Symptoms:**
- Clicking navigation items doesn't change the page
- Page stays blank when navigating to DLQ
- Console shows errors about components not loading

**Possible Causes:**

#### A. JavaScript Error Blocking Rendering
Check browser console for errors. Common errors:
- SockJS `global is not defined` (see #1 above)
- WebSocket connection errors (see #2 above)
- Component initialization errors

**Solution:** Fix the underlying JavaScript errors first.

#### B. Router Configuration Issue
Verify routes are properly configured in `frontend/src/app/app.routes.ts`:

```typescript
export const routes: Routes = [
  { path: '', redirectTo: '/dlq', pathMatch: 'full' },
  { path: 'dlq', loadComponent: () => import('./components/dlq/dlq.component').then(m => m.DlqComponent) },
  { path: 'request-reply', loadComponent: () => import('./components/request-reply/request-reply.component').then(m => m.RequestReplyComponent) },
  { path: '**', redirectTo: '/dlq' }
];
```

#### C. Missing RouterOutlet
Ensure `app.component.ts` has `<router-outlet></router-outlet>` in the template.

**Status:** ✅ Should be resolved after fixing SockJS and WebSocket issues

---

### 4. Redis Connection Refused

**Error Message:**
```
redis.clients.jedis.exceptions.JedisConnectionException: Could not get a resource from the pool
```

**Cause:**
Redis server is not running or not accessible on the configured host/port.

**Solution:**

1. **Check if Redis is running:**
   ```bash
   redis-cli ping
   # Should return: PONG
   ```

2. **Start Redis if not running:**
   ```bash
   # Using Docker (recommended)
   docker run -d --name redis-messaging -p 6379:6379 redis:8.4-alpine
   
   # Or start local Redis
   redis-server
   ```

3. **Verify Redis configuration in `application.yml`:**
   ```yaml
   redis:
     host: localhost
     port: 6379
   ```

---

### 5. Lua Function Not Found

**Error Message:**
```
redis.clients.jedis.exceptions.JedisDataException: ERR Function not found
```

**Cause:**
The `claim_or_dlq` Lua function has not been loaded into Redis.

**Solution:**

1. **Load the Lua function:**
   ```bash
   cd lua
   ./load.sh
   ```

2. **Verify function is loaded:**
   ```bash
   redis-cli FUNCTION LIST
   # Should show: stream_utils library with claim_or_dlq function
   ```

**Note:** The Spring Boot application automatically loads the Lua function on startup if it's not already loaded.

---

### 6. CORS Errors

**Error Message:**
```
Access to XMLHttpRequest at 'http://localhost:8080/api/dlq/stats' from origin 'http://localhost:4200' has been blocked by CORS policy
```

**Cause:**
CORS is not properly configured in the Spring Boot application.

**Solution:**
Verify CORS configuration in `DLQController.java`:

```java
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/dlq")
public class DLQController {
    // ...
}
```

And in `WebSocketConfig.java`:

```java
registry.addHandler(dlqEventWebSocketHandler, "/ws/dlq-events")
        .setAllowedOriginPatterns("*")  // Use allowedOriginPatterns for SockJS
        .withSockJS();
```

**Important:** For WebSocket with SockJS, use `setAllowedOriginPatterns("*")` instead of `setAllowedOrigins("*")` because SockJS enables credentials by default.

---

### 6b. WebSocket CORS Error - allowedOrigins with credentials

**Error Message:**
```
java.lang.IllegalArgumentException: When allowCredentials is true, allowedOrigins cannot contain the special value "*" since that cannot be set on the "Access-Control-Allow-Origin" response header. To allow credentials to a set of origins, list them explicitly or consider using "allowedOriginPatterns" instead.
```

**Cause:**
SockJS enables credentials by default, and Spring Security doesn't allow `allowedOrigins("*")` when credentials are enabled.

**Solution:**
Change `setAllowedOrigins("*")` to `setAllowedOriginPatterns("*")` in `WebSocketConfig.java`:

```java
@Override
public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
    registry.addHandler(dlqEventWebSocketHandler, "/ws/dlq-events")
            .setAllowedOriginPatterns("*")  // ✅ Use this for SockJS
            .withSockJS();
}
```

**Status:** ✅ Fixed in commit

---

### 7. Port Already in Use

**Error Message:**
```
Web server failed to start. Port 8080 was already in use.
```

**Solution:**

1. **Find process using the port:**
   ```bash
   lsof -i :8080
   ```

2. **Kill the process:**
   ```bash
   kill -9 <PID>
   ```

3. **Or use a different port:**
   Edit `application.yml`:
   ```yaml
   server:
     port: 8081
   ```
   
   And update frontend services to use the new port.

---

### 8. Angular Build Errors

**Error Message:**
```
✘ [ERROR] TS2348: Value of type 'EventEmitter<boolean>' is not callable
```

**Solution:**
Run linting to identify issues:
```bash
cd frontend
npm run lint
```

Fix any linting errors, then rebuild:
```bash
npm start
```

---

### 9. Stream Viewer Not Showing Messages

**Symptoms:**
- Stream viewer component loads but shows "No messages in stream"
- WebSocket connection indicator shows "Connected"
- No errors in console

**Possible Causes:**

#### A. No Messages in Stream
Verify messages exist in Redis:
```bash
redis-cli XRANGE test-stream - +
```

#### B. Wrong Stream Name
Check that the stream name in the component matches the actual Redis stream:
```typescript
<app-stream-viewer
  stream="test-stream"  <!-- Must match actual stream name -->
  group="test-group"
  consumer="consumer-1">
</app-stream-viewer>
```

#### C. Backend Not Broadcasting Events
Check Spring Boot logs for WebSocket event broadcasting:
```
DEBUG com.redis.patterns.service.WebSocketEventService - Broadcasting event to X clients
```

---

### 10. Hot Reload Not Working

**Symptoms:**
- Changes to TypeScript/HTML/CSS files don't appear in browser
- Need to manually refresh browser

**Solution:**

1. **Restart Angular dev server:**
   ```bash
   # Stop with Ctrl+C
   npm start
   ```

2. **Clear browser cache:**
   - Chrome: Ctrl+Shift+R (hard refresh)
   - Firefox: Ctrl+F5

3. **Check for console errors:**
   - Open browser DevTools (F12)
   - Look for compilation errors

---

## Getting Help

If you encounter an issue not covered here:

1. **Check browser console** (F12) for JavaScript errors
2. **Check Spring Boot logs** for backend errors
3. **Check Redis logs** (if using Docker: `docker logs redis-messaging`)
4. **Verify all services are running:**
   - Redis: `redis-cli ping`
   - Spring Boot: `curl http://localhost:8080/api/dlq/stats?streamName=test&dlqStreamName=test:dlq&groupName=test`
   - Angular: Open http://localhost:4200 in browser

## Useful Commands

```bash
# Check all ports
lsof -i :6379  # Redis
lsof -i :8080  # Spring Boot
lsof -i :4200  # Angular

# View logs
tail -f logs/redis-messaging-patterns.log  # Spring Boot logs
docker logs -f redis-messaging             # Redis logs (Docker)

# Clean restart
# Terminal 1: Redis
docker stop redis-messaging && docker rm redis-messaging
docker run -d --name redis-messaging -p 6379:6379 redis:8.4-alpine

# Terminal 2: Spring Boot
mvn clean package -DskipTests
java -jar target/redis-messaging-patterns-1.0.0.jar

# Terminal 3: Angular
cd frontend
rm -rf node_modules package-lock.json
npm install
npm start
```

