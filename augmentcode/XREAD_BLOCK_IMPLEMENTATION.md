# XREAD BLOCK Implementation Guide

## 📋 Overview

This document describes the implementation of **XREAD BLOCK** for automatic Redis Streams message detection using **Java 21 Virtual Threads**.

---

## 🎯 Architecture

### **Before (Manual Broadcast)**
```
Angular "Generate Messages" 
    ↓
POST /api/dlq/produce
    ↓
DLQMessagingService.produceMessage()
    ↓
jedis.xadd() + webSocketEventService.broadcastEvent(MESSAGE_PRODUCED)  ← Manual broadcast
    ↓
Angular receives MESSAGE_PRODUCED via WebSocket
    ↓
Message appears instantly in UI
```

**Problem**: Messages added externally (e.g., via Redis Insight) are NOT detected.

---

### **After (XREAD BLOCK with Virtual Threads)**
```
Any source (API or Redis Insight)
    ↓
XADD test-stream * field1 value1 field2 value2
    ↓
RedisStreamListenerService (XREAD BLOCK) detects new message  ← Automatic detection
    ↓
webSocketEventService.broadcastEvent(MESSAGE_PRODUCED)
    ↓
Angular receives MESSAGE_PRODUCED via WebSocket
    ↓
Message appears instantly in UI
```

**Solution**: ALL messages are detected automatically, regardless of source.

---

## 🔧 Implementation Details

### **1. RedisStreamListenerService**

**Location**: `src/main/java/com/redis/patterns/service/RedisStreamListenerService.java`

**Key Features**:
- ✅ Uses **Java 21 Virtual Threads** (lightweight, scalable)
- ✅ One Virtual Thread per monitored stream
- ✅ **XREAD BLOCK 1000** (blocks for 1 second, then retries)
- ✅ Reads up to 100 messages per iteration
- ✅ Automatic reconnection on errors
- ✅ Graceful shutdown support
- ✅ Broadcasts `MESSAGE_PRODUCED` events via WebSocket

**Code Snippet**:
```java
@Service
@RequiredArgsConstructor
public class RedisStreamListenerService implements CommandLineRunner {
    
    @Override
    public void run(String... args) {
        // Start monitoring default streams on application startup
        startMonitoring("test-stream");
        startMonitoring("test-stream:dlq");
    }
    
    public void startMonitoring(String streamName) {
        // Start Virtual Thread for this stream
        Thread.ofVirtual()
            .name("stream-listener-" + streamName)
            .start(new StreamMonitor(streamName));
    }
    
    private class StreamMonitor implements Runnable {
        @Override
        public void run() {
            while (running.get()) {
                // XREAD BLOCK 1000 STREAMS <streamName> <lastId>
                List<Map.Entry<String, List<StreamEntry>>> result = jedis.xread(
                    XReadParams.xReadParams()
                        .block(1000)  // Block for 1 second
                        .count(100),   // Read up to 100 messages
                    Collections.singletonMap(streamName, lastId)
                );
                
                // Broadcast new messages via WebSocket
                for (StreamEntry entry : result) {
                    webSocketEventService.broadcastEvent(
                        DLQEvent.builder()
                            .eventType(MESSAGE_PRODUCED)
                            .messageId(entry.getID().toString())
                            .payload(entry.getFields())
                            .streamName(streamName)
                            .build()
                    );
                }
            }
        }
    }
}
```

---

### **2. DLQMessagingService Changes**

**Modified Methods**:

#### **produceMessage()**
- ❌ **Removed**: Manual `MESSAGE_PRODUCED` broadcast
- ✅ **Now**: Only performs `XADD`, listener detects automatically

```java
public String produceMessage(String streamName, Map<String, String> payload) {
    try (var jedis = jedisPool.getResource()) {
        StreamEntryID messageId = jedis.xadd(streamName, XAddParams.xAddParams(), payload);
        
        // NOTE: No WebSocket broadcast here - RedisStreamListenerService handles it
        
        return messageId.toString();
    }
}
```

#### **claimOrDLQ()**
- ❌ **Removed**: Manual `MESSAGE_PRODUCED` broadcast for DLQ messages
- ✅ **Now**: Listener detects DLQ messages automatically

---

### **3. Angular (No Changes Required)**

The Angular `StreamViewerComponent` already handles `MESSAGE_PRODUCED` events correctly:

```typescript
private handleWebSocketEvent(event: DLQEvent): void {
    // Add new message to the list (only for MESSAGE_PRODUCED events)
    if (event.eventType === 'MESSAGE_PRODUCED' && event.messageId && event.payload) {
        const newMessage: StreamMessage = {
            id: event.messageId,
            fields: event.payload,
            timestamp: event.timestamp || new Date().toISOString()
        };
        
        // Add to beginning (newest first)
        this.displayedMessages.unshift(newMessage);
        this.totalMessages++;
        
        this.cdr.detectChanges();
    }
}
```

---

## 🚀 Benefits

| Feature | Before | After |
|---------|--------|-------|
| **Detects API messages** | ✅ Yes | ✅ Yes |
| **Detects Redis Insight messages** | ❌ No | ✅ Yes |
| **Detects external XADD** | ❌ No | ✅ Yes |
| **Latency** | < 1ms | < 2ms |
| **CPU Usage** | Low | Low (Virtual Threads) |
| **Threads** | 0 | 1 per stream (Virtual) |
| **Complexity** | Low | Low |

---

## 📊 Performance

### **Latency (according to Redis docs)**
- 74.11% of messages: 0-1ms
- 25.80% of messages: 1-2ms
- 99.9% of messages: ≤ 2ms

### **Resource Usage**
- **Virtual Threads**: Extremely lightweight (< 1KB per thread)
- **Connections**: 1 Redis connection per stream (from pool)
- **CPU**: Minimal (blocking I/O, no polling)

---

## 🧪 Testing

### **Test 1: API-generated messages**
```bash
# In Angular UI
Click "Generate Messages" button

# Expected: Messages appear instantly in UI
```

### **Test 2: Redis Insight messages**
```bash
# In Redis Insight
XADD test-stream * type order.shipped order_id 999 tracking "EXTERNAL123"

# Expected: Message appears instantly in Angular UI (no reload needed!)
```

### **Test 3: CLI messages**
```bash
redis-cli XADD test-stream * field1 value1 field2 value2

# Expected: Message appears instantly in Angular UI
```

---

## 🔍 Monitoring

### **Check active monitors**
```java
@Autowired
private RedisStreamListenerService listenerService;

Set<String> monitored = listenerService.getMonitoredStreams();
// Returns: ["test-stream", "test-stream:dlq"]
```

### **Start/Stop monitoring**
```java
// Start monitoring a new stream
listenerService.startMonitoring("my-new-stream");

// Stop monitoring a stream
listenerService.stopMonitoring("my-new-stream");
```

---

## 📝 Configuration

No additional configuration required! The service starts automatically on application startup via `CommandLineRunner`.

**Default monitored streams**:
- `test-stream`
- `test-stream:dlq`

To monitor additional streams, call `startMonitoring()` programmatically.

---

## 🎯 Conclusion

This implementation follows **Redis best practices** for stream consumption:
- ✅ Uses **XREAD BLOCK** (official recommendation)
- ✅ Leverages **Java 21 Virtual Threads** (scalable, lightweight)
- ✅ Detects **ALL** messages (API + external)
- ✅ Low latency (< 2ms)
- ✅ Simple architecture (no Pub/Sub complexity)

**Result**: A robust, production-ready solution for real-time stream monitoring! 🚀

