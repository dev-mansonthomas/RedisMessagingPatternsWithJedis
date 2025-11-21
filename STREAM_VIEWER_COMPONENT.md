# Stream Viewer Component Documentation

## Overview

The `stream-viewer` component is a reusable Angular component that displays Redis Stream messages with real-time updates via WebSocket. It provides a clean, table-based interface for monitoring message flow in Redis Streams.

## Features

✅ **Real-time Updates** - Automatically receives new messages via WebSocket  
✅ **Reverse Chronological Order** - Newest messages at top, oldest at bottom  
✅ **Pagination** - Configurable page size with "more messages..." indicator  
✅ **Connection Status** - Visual indicator showing WebSocket connection state  
✅ **Message Details** - Displays message ID and all field key-value pairs  
✅ **Responsive Design** - Mobile-friendly layout  
✅ **Empty State** - Clear messaging when no messages are present  
✅ **Loading State** - Shows loading indicator during initial data fetch  

## Component Location

```
frontend/src/app/components/stream-viewer/stream-viewer.component.ts
```

## Dependencies

### Services Created

1. **WebSocketService** (`frontend/src/app/services/websocket.service.ts`)
   - Manages WebSocket connection to Spring Boot backend
   - Uses SockJS for fallback support
   - Handles reconnection logic
   - Provides connection status observable

2. **RedisApiService** (`frontend/src/app/services/redis-api.service.ts`)
   - HTTP client for REST API calls
   - Methods for stats, initialization, and cleanup
   - Type-safe interfaces for API responses

## Usage

### Basic Usage

```typescript
import { StreamViewerComponent } from './components/stream-viewer/stream-viewer.component';

@Component({
  selector: 'app-my-page',
  standalone: true,
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
export class MyPageComponent {}
```

### Input Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `stream` | string | Yes | `''` | Redis Stream name to monitor |
| `group` | string | Yes | `''` | Consumer Group name |
| `consumer` | string | Yes | `''` | Consumer name within the group |
| `pageSize` | number | No | `10` | Number of messages to display |

### Example: DLQ Page

The DLQ component demonstrates dual stream monitoring:

```typescript
// Main stream
<app-stream-viewer
  stream="test-stream"
  group="test-group"
  consumer="consumer-1"
  [pageSize]="10">
</app-stream-viewer>

// DLQ stream
<app-stream-viewer
  stream="test-stream:dlq"
  group="dlq-group"
  consumer="dlq-consumer"
  [pageSize]="10">
</app-stream-viewer>
```

## WebSocket Integration

### Connection

The component automatically connects to the WebSocket endpoint on initialization:

```
ws://localhost:8080/api/ws/dlq-events
```

**Note:** The Spring Boot application uses context path `/api`, so the WebSocket endpoint is `/api/ws/dlq-events`.

### Event Format

The backend sends `DLQEvent` objects:

```typescript
interface DLQEvent {
  eventType: string;        // e.g., "MESSAGE_PRODUCED", "MESSAGE_CONSUMED"
  messageId?: string;       // Redis Stream message ID
  payload?: Record<string, string>;  // Message fields
  streamName?: string;      // Stream name
  consumer?: string;        // Consumer name
  details?: string;         // Additional details
  timestamp?: string;       // Event timestamp
}
```

### Event Filtering

The component automatically filters events to show only messages for its configured stream:

```typescript
if (event.streamName !== this.stream) {
  return; // Ignore events from other streams
}
```

## Visual Design

### Color Scheme

- **Header Background**: Light gray gradient (`#f8fafc` to `#f1f5f9`)
- **Connected Status**: Green (`#dcfce7` background, `#166534` text)
- **Disconnected Status**: Red (`#fee2e2` background, `#991b1b` text)
- **More Messages Indicator**: Yellow (`#fef3c7` background, `#92400e` text)
- **Table Header**: Sticky, light gray (`#f8fafc`)
- **Row Hover**: Light gray (`#f8fafc`)

### Layout

```
┌─────────────────────────────────────────┐
│ Stream Name          [●] Connected      │ ← Header
├─────────────────────────────────────────┤
│ Message ID          │ Content           │ ← Table Header (sticky)
├─────────────────────────────────────────┤
│ ... 5 more messages ...                 │ ← More indicator (if applicable)
├─────────────────────────────────────────┤
│ 1234567890-0        │ key1: value1      │ ← Message rows
│                     │ key2: value2      │
├─────────────────────────────────────────┤
│ 1234567891-0        │ key1: value1      │
│                     │ key2: value2      │
├─────────────────────────────────────────┤
│ 10 of 15 messages                       │ ← Footer
└─────────────────────────────────────────┘
```

## Configuration

### HttpClient Provider

Ensure `provideHttpClient()` is added to your app providers:

```typescript
// frontend/src/main.ts
import { provideHttpClient } from '@angular/common/http';

bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(routes),
    provideAnimations(),
    provideHttpClient()  // ← Required for RedisApiService
  ]
});
```

## Backend Requirements

### WebSocket Endpoint

The Spring Boot backend must provide:

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(dlqEventWebSocketHandler, "/ws/dlq-events")
                .setAllowedOrigins("*")
                .withSockJS();
    }
}
```

### Event Broadcasting

The backend should broadcast events to all connected clients:

```java
@Service
public class WebSocketEventService {
    public void broadcastEvent(DLQEvent event) {
        String json = objectMapper.writeValueAsString(event);
        for (WebSocketSession session : sessions) {
            session.sendMessage(new TextMessage(json));
        }
    }
}
```

## Future Enhancements

### Potential Improvements

1. **Initial Data Loading** - Fetch existing messages from REST API on component init
2. **Manual Refresh** - Add button to reload messages
3. **Message Acknowledgment** - Add UI to acknowledge/reject messages
4. **Filtering** - Filter messages by field values
5. **Search** - Search within message content
6. **Export** - Export messages to CSV/JSON
7. **Pagination Controls** - Next/Previous page buttons
8. **Auto-scroll** - Option to auto-scroll to newest messages
9. **Message Details Modal** - Click to view full message details
10. **Performance** - Virtual scrolling for large message lists

## Troubleshooting

### WebSocket Not Connecting

1. Check backend is running on port 8080
2. Verify WebSocket endpoint is configured
3. Check browser console for errors
4. Ensure CORS is properly configured

### Messages Not Appearing

1. Verify stream name matches backend
2. Check WebSocket connection status indicator
3. Ensure backend is broadcasting events
4. Check browser console for filtering issues

### Styling Issues

1. Ensure component styles are not being overridden
2. Check for CSS conflicts with global styles
3. Verify responsive breakpoints work on your device

## Testing

### Manual Testing

1. Start Redis server
2. Start Spring Boot backend
3. Start Angular frontend
4. Navigate to page with stream-viewer
5. Produce messages to the stream (via backend API or Redis CLI)
6. Verify messages appear in real-time

### Redis CLI Testing

```bash
# Add messages to stream
redis-cli XADD test-stream * field1 value1 field2 value2

# Verify messages
redis-cli XRANGE test-stream - +
```

## Related Files

- `frontend/src/app/services/websocket.service.ts` - WebSocket connection management
- `frontend/src/app/services/redis-api.service.ts` - REST API client
- `frontend/src/app/components/dlq/dlq.component.ts` - Example usage
- `STARTUP_INSTRUCTIONS.md` - How to start the application
- `IMPLEMENTATION_GUIDE.md` - Full implementation details

