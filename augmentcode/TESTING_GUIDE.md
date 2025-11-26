# Testing Guide - Single Consumer Architecture

## 🎯 What We're Testing

After implementing the **single consumer architecture**, we need to verify:

1. ✅ Visualization uses `XREVRANGE` (no PENDING entries)
2. ✅ Only `test-group` consumer is active
3. ✅ `monitor-group` has no PENDING entries (service disabled)
4. ✅ Process & Success removes messages
5. ✅ Process & Fail routes to DLQ after max retries

---

## 🚀 Setup

### 1. Start Redis
```bash
redis-server
```

### 2. Reload Lua Function
```bash
./reload-lua.sh
```

### 3. Start Spring Boot
```bash
mvn spring-boot:run
```

### 4. Start Angular (in another terminal)
```bash
cd frontend
npm start
```

### 5. Open Browser
```
http://localhost:4200/dlq
```

---

## 🧪 Test Scenarios

### **Test 1: Verify No monitor-group PENDING Entries**

**Goal:** Confirm that `StreamMonitorService` is disabled and doesn't create PENDING entries.

**Steps:**

1. Open the DLQ page in browser
2. Refresh the page multiple times (F5)
3. Check Redis:

```bash
# Check consumer groups
redis-cli XINFO GROUPS test-stream

# Expected output:
# - test-group: exists
# - monitor-group: exists (but no consumers, no pending)

# Check PENDING for monitor-group
redis-cli XPENDING test-stream monitor-group

# Expected: Empty array [] or "No pending messages"
```

**✅ Success Criteria:**
- `monitor-group` has 0 PENDING entries
- Page loads show messages without creating PENDING entries

---

### **Test 2: Produce and Visualize Messages**

**Goal:** Verify that visualization works without consuming messages.

**Steps:**

1. In the UI, click "Produce Message" 3 times
2. Observe messages appear in the stream viewer
3. Check Redis:

```bash
# Check stream length
redis-cli XLEN test-stream
# Expected: 3

# Check PENDING for test-group
redis-cli XPENDING test-stream test-group
# Expected: Empty [] (no messages consumed yet)
```

**✅ Success Criteria:**
- Messages appear in UI
- Stream length = 3
- No PENDING entries (visualization doesn't consume)

---

### **Test 3: Process & Success (Happy Path)**

**Goal:** Verify that messages are consumed, processed, and removed.

**Steps:**

1. Ensure you have messages in the stream (from Test 2)
2. Click "Process & Success" button
3. Observe message disappears from UI
4. Check Redis:

```bash
# Check stream length (should decrease)
redis-cli XLEN test-stream
# Expected: 2 (one message processed)

# Check PENDING
redis-cli XPENDING test-stream test-group
# Expected: Empty [] (message was ACK'd)
```

5. Click "Process & Success" 2 more times to process remaining messages

**✅ Success Criteria:**
- Each click removes one message from UI
- Stream length decreases
- No PENDING entries (messages ACK'd immediately)

---

### **Test 4: Process & Fail (Retry Path)**

**Goal:** Verify retry logic and DLQ routing after max deliveries.

**Setup:**
```bash
# Check current maxDeliveries setting
redis-cli HGET dlq:config:test-stream maxDeliveries
# Default should be 2
```

**Steps:**

1. Produce a new message:
   - Click "Produce Message" in UI

2. **First Attempt** - Click "Process & Fail"
   - Message stays visible in UI
   - Check PENDING:
   ```bash
   redis-cli XPENDING test-stream test-group
   # Expected: 1 pending message, deliveryCount = 1
   ```

3. **Wait 5 seconds** (minIdleMs)

4. **Second Attempt** - Click "Process & Fail" again
   - Message still visible in UI
   - Check PENDING:
   ```bash
   redis-cli XPENDING test-stream test-group
   # Expected: 1 pending message, deliveryCount = 2
   ```

5. **Wait 5 seconds**

6. **Third Attempt** - Click "Process & Fail" again
   - Message disappears from main stream
   - Message appears in DLQ stream
   - Check Redis:
   ```bash
   # Main stream: message removed
   redis-cli XLEN test-stream
   # Expected: 0
   
   # DLQ stream: message added
   redis-cli XLEN test-stream:dlq
   # Expected: 1
   
   # PENDING: cleared
   redis-cli XPENDING test-stream test-group
   # Expected: Empty []
   ```

**✅ Success Criteria:**
- Message stays in main stream for 2 failed attempts
- After 3rd attempt (deliveryCount >= maxDeliveries):
  - Message removed from main stream
  - Message added to DLQ
  - PENDING cleared

---

### **Test 5: WebSocket Real-Time Updates**

**Goal:** Verify WebSocket events update the UI in real-time.

**Steps:**

1. Open the DLQ page in **two browser tabs** side-by-side
2. In Tab 1: Click "Produce Message"
3. Observe: Message appears in **both tabs** instantly
4. In Tab 2: Click "Process & Success"
5. Observe: Message disappears from **both tabs** instantly

**✅ Success Criteria:**
- Changes in one tab are reflected in other tabs immediately
- No page refresh needed

---

### **Test 6: Verify Single Consumer Group**

**Goal:** Confirm only `test-group` is actively consuming messages.

**Steps:**

1. Produce 5 messages
2. Click "Process & Success" once
3. Check consumer groups:

```bash
# List all consumer groups
redis-cli XINFO GROUPS test-stream

# Expected output:
# 1) name: "test-group"
#    consumers: 1
#    pending: 0 (after ACK)
# 2) name: "monitor-group"
#    consumers: 0  ← No active consumers
#    pending: 0    ← No pending entries

# Check consumers in test-group
redis-cli XINFO CONSUMERS test-stream test-group

# Expected: 1 consumer (e.g., "consumer-1")

# Check consumers in monitor-group
redis-cli XINFO CONSUMERS test-stream monitor-group

# Expected: Empty [] (service disabled)
```

**✅ Success Criteria:**
- `test-group`: 1 active consumer
- `monitor-group`: 0 consumers, 0 pending

---

## 🔍 Debugging

### Check Logs

**Spring Boot logs:**
```bash
tail -f logs/redis-messaging-patterns.log | grep -E "StreamMonitorService|getNextMessages|acknowledgeMessage"
```

**Expected:**
- No `StreamMonitorService` initialization logs (service disabled)
- `getNextMessages` logs when clicking Process buttons
- `acknowledgeMessage` logs when clicking Process & Success

### Check WebSocket Events

**Browser Console (F12):**
```javascript
// You should see logs like:
StreamViewer [test-stream]: Received WebSocket event: {eventType: "MESSAGE_DELETED", ...}
StreamViewer [test-stream]: Event ignored (processing event: MESSAGE_RECLAIMED)
```

**Expected:**
- `MESSAGE_PRODUCED`: When producing messages
- `MESSAGE_DELETED`: When processing with success or routing to DLQ
- `MESSAGE_RECLAIMED`, `INFO`: Ignored (not displayed)

---

## 📊 Expected Redis State

### After All Tests

```bash
# Main stream: empty (all processed)
redis-cli XLEN test-stream
# Expected: 0

# DLQ stream: contains failed messages
redis-cli XLEN test-stream:dlq
# Expected: 1+ (from Test 4)

# Consumer groups
redis-cli XINFO GROUPS test-stream
# test-group: 0 pending
# monitor-group: 0 pending, 0 consumers

# Consumer groups for DLQ
redis-cli XINFO GROUPS test-stream:dlq
# test-group-dlq: 0 pending
```

---

## ✅ Success Checklist

- [ ] `monitor-group` has 0 PENDING entries
- [ ] Page loads don't create PENDING entries
- [ ] Process & Success removes messages
- [ ] Process & Fail (3x) routes to DLQ
- [ ] WebSocket updates work in real-time
- [ ] Only `test-group` is actively consuming
- [ ] UI shows accurate message counts
- [ ] No phantom messages appear

---

## 🐛 Common Issues

### Issue: Messages don't disappear after Process & Success

**Cause:** WebSocket event filtering might be wrong.

**Fix:** Check browser console for `MESSAGE_DELETED` events.

### Issue: Messages don't move to DLQ after 3 failures

**Cause:** `minIdleMs` not elapsed between attempts.

**Fix:** Wait 5+ seconds between clicks.

### Issue: monitor-group still has PENDING entries

**Cause:** `StreamMonitorService` not disabled.

**Fix:** Verify `@Profile("disabled")` annotation is present.

---

## 📚 Related Documentation

- `ARCHITECTURE_VISUALIZATION.md` - Architecture details
- `WEBSOCKET_EVENTS_FIX.md` - WebSocket event handling
- `DEVELOPER_GUIDE.md` - API usage guide

