# WebSocket Events Fix

## Problem

When using the DLQ Actions component:

1. **Process & Success**: New messages were appearing in the stream viewer instead of disappearing
2. **Process & Fail**: Messages were not appearing in the DLQ stream after max retries

## Root Cause

### Issue 1: Frontend Adding Messages on Processing Events

The `stream-viewer` component was adding messages to the display for **ALL** WebSocket events with a `messageId` and `payload`, including:
- `MESSAGE_RECLAIMED` (when a message is claimed for retry)
- `INFO` (general information events)

These are **processing events**, not new messages, so they should not be displayed as new messages.

### Issue 2: ACK Events Not Removing Messages

When a message was acknowledged, the backend sent a `MESSAGE_PROCESSED` event, but the frontend only removed messages on `MESSAGE_DELETED` events.

### Issue 3: DLQ Routing Not Visible

When the Lua script routed messages to the DLQ (after max retries), the backend didn't detect or broadcast these events, so the frontend never showed them.

---

## Solution

### 1. Frontend: Filter Processing Events

**File:** `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

**Changes:**
- Ignore `MESSAGE_RECLAIMED`, `INFO`, and `ERROR` events (lines 356-361)
- Only add messages for `MESSAGE_PRODUCED` events (line 364)

**Before:**
```typescript
// Add new message to the list
if (event.messageId && event.payload) {
  // Added for ALL events with messageId and payload
  this.displayedMessages.unshift(newMessage);
}
```

**After:**
```typescript
// Ignore processing events (not new messages)
if (event.eventType === 'MESSAGE_RECLAIMED' || 
    event.eventType === 'INFO' || 
    event.eventType === 'ERROR') {
  return;
}

// Add new message to the list (only for MESSAGE_PRODUCED events)
if (event.eventType === 'MESSAGE_PRODUCED' && event.messageId && event.payload) {
  this.displayedMessages.unshift(newMessage);
}
```

---

### 2. Backend: Send MESSAGE_DELETED on ACK

**File:** `src/main/java/com/redis/patterns/service/DLQMessagingService.java`

**Method:** `acknowledgeMessage()` (line 312-323)

**Changes:**
- Changed event type from `MESSAGE_PROCESSED` to `MESSAGE_DELETED`
- This ensures the frontend removes the message from the display

**Before:**
```java
webSocketEventService.broadcastEvent(DLQEvent.builder()
    .eventType(DLQEvent.EventType.MESSAGE_PROCESSED)
    .messageId(messageId)
    .streamName(streamName)
    .details("Message acknowledged")
    .build());
```

**After:**
```java
webSocketEventService.broadcastEvent(DLQEvent.builder()
    .eventType(DLQEvent.EventType.MESSAGE_DELETED)
    .messageId(messageId)
    .streamName(streamName)
    .details("Message acknowledged and removed")
    .build());
```

---

### 3. Lua Script: Return DLQ Messages

**File:** `lua/stream_utils.claim_or_dlq.lua`

**Changes:**
- Modified return value to include both reclaimed and DLQ messages
- Returns: `{ reclaimed: [...], dlq: [...] }`

**Before:**
```lua
local result = {}
-- ... populate result with reclaimed messages ...
return result
```

**After:**
```lua
local reclaimed = {}
local dlq_ids = {}

-- ... populate reclaimed and dlq_ids ...

return { reclaimed, dlq_ids }
```

Each DLQ entry contains:
- `[1]`: Original message ID
- `[2]`: Message fields
- `[3]`: New DLQ message ID

---

### 4. Backend: Parse DLQ Messages and Broadcast Events

**File:** `src/main/java/com/redis/patterns/service/DLQMessagingService.java`

**Method:** `claimOrDLQ()` (lines 170-264)

**Changes:**
- Parse the new return format: `{ reclaimed: [...], dlq: [...] }`
- For each message routed to DLQ:
  - Broadcast `MESSAGE_DELETED` for the main stream
  - Broadcast `MESSAGE_PRODUCED` for the DLQ stream

**Code:**
```java
// Second element: messages sent to DLQ
if (resultList.size() > 1 && resultList.get(1) instanceof List) {
    List<Object> dlqList = (List<Object>) resultList.get(1);

    for (Object entry : dlqList) {
        // Parse: [originalId, fields, newDlqId]
        String originalId = entryList.get(0).toString();
        String newDlqId = entryList.get(2).toString();

        // Broadcast: message deleted from main stream
        webSocketEventService.broadcastEvent(DLQEvent.builder()
            .eventType(DLQEvent.EventType.MESSAGE_DELETED)
            .messageId(originalId)
            .streamName(params.getStreamName())
            .build());

        // Broadcast: message added to DLQ
        webSocketEventService.broadcastEvent(DLQEvent.builder()
            .eventType(DLQEvent.EventType.MESSAGE_PRODUCED)
            .messageId(newDlqId)
            .payload(payload)
            .streamName(params.getDlqStreamName())
            .build());
    }
}
```

---

## Expected Behavior After Fix

### Process & Success

1. Click "Process & Success"
2. Backend gets next message via `getNextMessages()`
3. Backend acknowledges message with `acknowledgeMessage()`
4. Backend broadcasts `MESSAGE_DELETED` event
5. ✅ Frontend removes message from main stream display
6. Message count decreases

### Process & Fail

1. Click "Process & Fail" (1st time)
2. Backend gets next message
3. Backend does NOT acknowledge
4. Message stays PENDING, deliveryCount = 1
5. No WebSocket events (message still pending)

6. Wait 5 seconds (minIdleMs)

7. Click "Process & Fail" (2nd time)
8. Same as above, deliveryCount = 2

9. Wait 5 seconds

10. Click "Process & Fail" (3rd time, assuming maxDeliveries = 2)
11. Backend calls `claimOrDLQ()`
12. Lua script detects deliveryCount >= maxDeliveries
13. Lua script routes message to DLQ and returns DLQ info
14. Backend broadcasts:
    - `MESSAGE_DELETED` for main stream
    - `MESSAGE_PRODUCED` for DLQ stream
15. ✅ Frontend removes message from main stream
16. ✅ Frontend adds message to DLQ stream
17. Message appears in DLQ viewer

---

## Files Modified

### Frontend
- `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

### Backend
- `src/main/java/com/redis/patterns/service/DLQMessagingService.java`

### Lua
- `lua/stream_utils.claim_or_dlq.lua`

### Scripts
- `reload-lua.sh` (new file for reloading Lua functions)

---

## Testing

1. **Reload Lua function:**
   ```bash
   ./reload-lua.sh
   ```

2. **Restart Spring Boot:**
   ```bash
   mvn spring-boot:run
   ```

3. **Test Process & Success:**
   - Produce a message
   - Click "Process & Success"
   - ✅ Message should disappear from main stream

4. **Test Process & Fail:**
   - Produce a message
   - Click "Process & Fail" 3 times (with 5 second waits)
   - ✅ After 3rd click, message should move to DLQ

---

## Benefits

✅ **Correct WebSocket filtering**: Only real new messages are displayed  
✅ **ACK removes messages**: Acknowledged messages disappear from view  
✅ **DLQ routing visible**: Messages moving to DLQ are shown in real-time  
✅ **Better UX**: Users see exactly what's happening with their messages  
✅ **No phantom messages**: Processing events don't create duplicate displays  

