# Testing XREAD BLOCK Implementation

## 🧪 Test Plan

This document provides step-by-step instructions to test the XREAD BLOCK implementation.

---

## 📋 Prerequisites

1. **Redis running** on `localhost:6379`
2. **Spring Boot application** running
3. **Angular application** running on `http://localhost:4200`
4. **Redis Insight** or `redis-cli` for manual testing

---

## 🚀 Test Scenarios

### **Test 1: Clean Start**

**Objective**: Verify that the listener starts correctly and monitors the default streams.

**Steps**:
```bash
# 1. Clean Redis
redis-cli FLUSHALL

# 2. Start Spring Boot
mvn spring-boot:run

# 3. Check logs for listener startup
# Expected output:
# "Starting Redis Stream Listener Service with Virtual Threads"
# "Starting monitoring for stream 'test-stream'"
# "Virtual Thread started for stream 'test-stream'"
# "Stream monitor started for 'test-stream'"
# "Starting monitoring for stream 'test-stream:dlq'"
# "Virtual Thread started for stream 'test-stream:dlq'"
# "Stream monitor started for 'test-stream:dlq'"
```

**Expected Result**: ✅ Listener starts successfully, 2 Virtual Threads created.

---

### **Test 2: API-Generated Messages (Existing Behavior)**

**Objective**: Verify that messages generated via the Angular UI are still detected.

**Steps**:
```bash
# 1. Open Angular UI
open http://localhost:4200/dlq

# 2. Click "Generate Messages" button (generate 5 messages)

# 3. Observe the UI
```

**Expected Result**: 
- ✅ Messages appear instantly in the UI
- ✅ WebSocket connection status shows "Connected"
- ✅ Messages are displayed in the stream viewer

**Backend Logs**:
```
Producing message to stream 'test-stream': {type=order.shipped, order_id=123, ...}
Message produced with ID: 1234567890-0 (will be detected by stream listener)
Detected new message in 'test-stream': 1234567890-0
```

---

### **Test 3: Redis Insight Messages (NEW BEHAVIOR)** ⭐

**Objective**: Verify that messages added via Redis Insight are automatically detected.

**Steps**:
```bash
# 1. Open Redis Insight

# 2. Execute the following command:
XADD test-stream * type order.shipped order_id 999 tracking "EXTERNAL123"

# 3. Observe the Angular UI (WITHOUT reloading the page)
```

**Expected Result**: 
- ✅ Message appears **instantly** in the Angular UI
- ✅ No page reload required
- ✅ Message is displayed with all fields

**Backend Logs**:
```
Detected new message in 'test-stream': 1234567890-1
```

**Angular Console Logs**:
```
StreamViewer [test-stream]: Received WebSocket event: {eventType: "MESSAGE_PRODUCED", messageId: "1234567890-1", ...}
StreamViewer [test-stream]: Adding new message: {id: "1234567890-1", fields: {...}}
```

---

### **Test 4: CLI Messages (NEW BEHAVIOR)** ⭐

**Objective**: Verify that messages added via `redis-cli` are automatically detected.

**Steps**:
```bash
# 1. In terminal, execute:
redis-cli XADD test-stream * field1 value1 field2 value2

# 2. Observe the Angular UI (WITHOUT reloading the page)
```

**Expected Result**: 
- ✅ Message appears **instantly** in the Angular UI
- ✅ No page reload required

---

### **Test 5: DLQ Messages (NEW BEHAVIOR)** ⭐

**Objective**: Verify that messages routed to DLQ are automatically detected.

**Steps**:
```bash
# 1. Generate a message in Angular UI

# 2. Click "Process & Fail" 3 times (to exceed max deliveries)

# 3. Observe both stream viewers:
#    - Main stream: Message disappears
#    - DLQ stream: Message appears automatically
```

**Expected Result**: 
- ✅ Message disappears from main stream
- ✅ Message appears **instantly** in DLQ stream (no reload)
- ✅ DLQ message has all original fields + metadata

**Backend Logs**:
```
Message routed to DLQ (max deliveries reached)
Detected new message in 'test-stream:dlq': 1234567890-2
```

---

### **Test 6: Multiple Messages at Once**

**Objective**: Verify that the listener can handle multiple messages added simultaneously.

**Steps**:
```bash
# 1. In Redis Insight or redis-cli, execute:
XADD test-stream * msg 1
XADD test-stream * msg 2
XADD test-stream * msg 3
XADD test-stream * msg 4
XADD test-stream * msg 5

# 2. Observe the Angular UI
```

**Expected Result**: 
- ✅ All 5 messages appear in the UI
- ✅ Messages appear in correct order (newest first)
- ✅ No messages are missed

---

### **Test 7: Latency Test**

**Objective**: Measure the latency between XADD and UI display.

**Steps**:
```bash
# 1. Open browser console (F12)

# 2. In Redis Insight, execute:
XADD test-stream * timestamp $(date +%s%3N)

# 3. Check the timestamp in the console log
```

**Expected Result**: 
- ✅ Latency < 2 seconds (typically < 1 second)

---

### **Test 8: Error Recovery**

**Objective**: Verify that the listener recovers from Redis connection errors.

**Steps**:
```bash
# 1. Stop Redis
docker stop redis  # or redis-cli SHUTDOWN

# 2. Check backend logs
# Expected: "Error reading from stream 'test-stream': ..."

# 3. Restart Redis
docker start redis

# 4. Add a message
redis-cli XADD test-stream * test recovery

# 5. Observe the Angular UI
```

**Expected Result**: 
- ✅ Listener automatically reconnects
- ✅ Message appears in UI after Redis restart

---

### **Test 9: WebSocket Reconnection**

**Objective**: Verify that Angular reconnects to WebSocket after disconnection.

**Steps**:
```bash
# 1. Restart Spring Boot (while Angular is running)

# 2. Observe Angular UI connection status
# Expected: "Disconnected" → "Connected"

# 3. Add a message via Redis Insight

# 4. Observe the Angular UI
```

**Expected Result**: 
- ✅ Angular reconnects automatically
- ✅ Message appears after reconnection

---

## 📊 Performance Benchmarks

### **Latency Measurement**

```bash
# Run this script to measure latency
for i in {1..100}; do
  START=$(date +%s%3N)
  redis-cli XADD test-stream * timestamp $START > /dev/null
  # Check Angular console for arrival time
done
```

**Expected Results** (according to Redis docs):
- 74% of messages: 0-1ms
- 25% of messages: 1-2ms
- 99.9% of messages: ≤ 2ms

---

## 🔍 Debugging

### **Check Virtual Threads**

```bash
# In Spring Boot logs, search for:
grep "Virtual Thread started" logs/redis-messaging-patterns.log

# Expected output:
# Virtual Thread started for stream 'test-stream'
# Virtual Thread started for stream 'test-stream:dlq'
```

### **Check XREAD BLOCK Calls**

Enable debug logging in `application.yml`:
```yaml
logging:
  level:
    com.redis.patterns.service.RedisStreamListenerService: DEBUG
```

Then check logs for:
```
Detected new message in 'test-stream': 1234567890-0
```

### **Check WebSocket Events**

In Angular console (F12), check for:
```javascript
StreamViewer [test-stream]: Received WebSocket event: {eventType: "MESSAGE_PRODUCED", ...}
```

---

## ✅ Success Criteria

All tests should pass with the following results:

- ✅ **Test 1**: Listener starts successfully
- ✅ **Test 2**: API messages detected
- ✅ **Test 3**: Redis Insight messages detected ⭐ **NEW**
- ✅ **Test 4**: CLI messages detected ⭐ **NEW**
- ✅ **Test 5**: DLQ messages detected ⭐ **NEW**
- ✅ **Test 6**: Multiple messages handled
- ✅ **Test 7**: Latency < 2 seconds
- ✅ **Test 8**: Error recovery works
- ✅ **Test 9**: WebSocket reconnection works

---

## 🎯 Conclusion

If all tests pass, the XREAD BLOCK implementation is **production-ready**! 🚀

