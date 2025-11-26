# DLQ Visualization Architecture

## 🎯 Design Principle: Single Consumer Pattern

This application uses a **single consumer group** for message processing, with **read-only visualization** that doesn't interfere with message consumption.

---

## 📊 Architecture Overview

### **Two Separate Concerns**

1. **Visualization (Read-Only)**
   - Uses `XREVRANGE` to read messages
   - No consumer group
   - No PENDING entries
   - Shows the current state of the stream

2. **Processing (Consumer Group)**
   - Uses `XREADGROUP` with `test-group`
   - Creates PENDING entries for tracking
   - Handles ACK/NACK for retry logic
   - Processes messages via "Process & Success/Fail" buttons

---

## 🔍 Why This Architecture?

### ❌ Previous Problem (Two Consumers)

**Before:**
- `monitor-group`: Used by `StreamMonitorService` for visualization
  - Consumed messages with `XREADGROUP`
  - Created PENDING entries
  - ACK'd immediately
  - **Problem:** Interfered with real processing

- `test-group`: Used by Process buttons
  - Consumed messages with `XREADGROUP`
  - Created PENDING entries
  - ACK'd only on success

**Result:** Confusion! Two consumers reading the same messages, creating unnecessary PENDING entries.

### ✅ Current Solution (Single Consumer)

**Now:**
- **No consumer for visualization**: Uses `XREVRANGE` (read-only)
- **One consumer for processing**: `test-group` only

**Benefits:**
- ✅ No PENDING entries for visualization
- ✅ Clear separation of concerns
- ✅ Simpler architecture
- ✅ No interference between visualization and processing

---

## 🔄 Data Flow

### **1. Initial Page Load**

```
User opens /dlq page
    ↓
Frontend calls GET /api/dlq/messages?streamName=test-stream
    ↓
Backend executes XREVRANGE (read-only)
    ↓
Returns last 10 messages
    ↓
Frontend displays messages
```

**Redis Command:**
```bash
XREVRANGE test-stream + - COUNT 10
```

**Key Point:** No consumer group, no PENDING entries created.

---

### **2. Process & Success (Happy Path)**

```
User clicks "Process & Success"
    ↓
Frontend calls POST /api/dlq/process { shouldSucceed: true }
    ↓
Backend:
  1. getNextMessages() → XREADGROUP with test-group
  2. Message added to PENDING (deliveryCount = 1)
  3. acknowledgeMessage() → XACK
  4. Message removed from PENDING
  5. Broadcast MESSAGE_DELETED event via WebSocket
    ↓
Frontend receives MESSAGE_DELETED event
    ↓
Frontend removes message from display (optimistic update)
```

**Redis Commands:**
```bash
# Step 1: Read message (creates PENDING entry)
XREADGROUP GROUP test-group consumer-1 COUNT 1 STREAMS test-stream >

# Step 2: Acknowledge (removes from PENDING)
XACK test-stream test-group <message-id>
```

**Result:** Message disappears from stream viewer.

---

### **3. Process & Fail (Retry Path)**

**First Attempt:**
```
User clicks "Process & Fail"
    ↓
Backend:
  1. getNextMessages() → XREADGROUP with test-group
  2. Message added to PENDING (deliveryCount = 1)
  3. NO XACK (simulates failure)
  4. No WebSocket event
    ↓
Frontend: No change (message still visible)
```

**Second Attempt (after 5 seconds):**
```
User clicks "Process & Fail" again
    ↓
Backend:
  1. claimOrDLQ() → XCLAIM (deliveryCount = 2)
  2. Still < maxDeliveries (default: 2)
  3. Message reclaimed for retry
  4. NO XACK
  5. No WebSocket event
    ↓
Frontend: No change (message still visible)
```

**Third Attempt (after 5 seconds, deliveryCount >= maxDeliveries):**
```
User clicks "Process & Fail" again
    ↓
Backend:
  1. claimOrDLQ() → XCLAIM (deliveryCount = 3)
  2. deliveryCount >= maxDeliveries
  3. Lua script routes to DLQ:
     - XADD test-stream:dlq (copy message)
     - XACK test-stream (remove from PENDING)
  4. Broadcast events:
     - MESSAGE_DELETED for test-stream
     - MESSAGE_PRODUCED for test-stream:dlq
    ↓
Frontend receives both events:
  1. Removes message from test-stream viewer
  2. Adds message to test-stream:dlq viewer
```

**Redis Commands:**
```bash
# Attempt 1: Read (no ACK)
XREADGROUP GROUP test-group consumer-1 COUNT 1 STREAMS test-stream >

# Attempt 2: Claim (no ACK)
XCLAIM test-stream test-group consumer-1 5000 <message-id>

# Attempt 3: Route to DLQ (via Lua script)
FCALL claim_or_dlq 2 test-stream test-stream:dlq test-group consumer-1 5000 1 2
  → XADD test-stream:dlq * <fields>
  → XACK test-stream test-group <message-id>
```

**Result:** Message moves from main stream to DLQ.

---

## 📡 WebSocket Events

### **Event Types**

| Event Type | Meaning | Frontend Action |
|------------|---------|-----------------|
| `MESSAGE_PRODUCED` | New message added to stream | Add to display |
| `MESSAGE_DELETED` | Message removed/ACK'd | Remove from display |
| `MESSAGE_RECLAIMED` | Message claimed for retry | **Ignore** (not a new message) |
| `INFO` | General information | **Ignore** (not a new message) |
| `ERROR` | Error occurred | **Ignore** (not a new message) |

### **Why Ignore Some Events?**

- `MESSAGE_RECLAIMED`: Indicates a retry, not a new message
- `INFO`: General processing information
- `ERROR`: Error details

**Only `MESSAGE_PRODUCED` and `MESSAGE_DELETED` affect the visualization.**

---

## 🔧 Key Components

### **Backend**

1. **`DLQMessagingService.readMessages()`**
   - Uses `XREVRANGE` for read-only access
   - No consumer group
   - Used by visualization

2. **`DLQMessagingService.getNextMessages()`**
   - Uses `XREADGROUP` with `test-group`
   - Creates PENDING entries
   - Used by Process buttons

3. **`DLQMessagingService.acknowledgeMessage()`**
   - Uses `XACK` to remove from PENDING
   - Broadcasts `MESSAGE_DELETED` event

4. **`StreamMonitorService`** (DISABLED)
   - Previously used `XREADGROUP` with `monitor-group`
   - Now disabled with `@Profile("disabled")`

### **Frontend**

1. **`StreamViewerComponent`**
   - Loads initial data with `XREVRANGE` (via REST API)
   - Subscribes to WebSocket events
   - Filters events: only `MESSAGE_PRODUCED` and `MESSAGE_DELETED`

2. **`DlqActionsComponent`**
   - "Process & Success" button
   - "Process & Fail" button
   - Triggers backend processing

---

## 📋 Redis State

### **What You'll See in Redis Insight**

**test-stream:**
- Contains all unprocessed messages
- `XLEN test-stream` shows total count

**test-group (Consumer Group):**
- PENDING: Messages currently being processed (not ACK'd)
- `XPENDING test-stream test-group` shows PENDING entries

**test-stream:dlq:**
- Contains messages that failed after max retries
- `XLEN test-stream:dlq` shows DLQ count

**monitor-group (DISABLED):**
- Should have 0 PENDING entries (service disabled)

---

## ✅ Benefits of This Architecture

1. **Clear Separation**
   - Visualization = Read-only (`XREVRANGE`)
   - Processing = Consumer group (`XREADGROUP`)

2. **No Interference**
   - Visualization doesn't create PENDING entries
   - Only real processing creates PENDING entries

3. **Accurate Display**
   - Frontend shows actual stream state
   - WebSocket events provide real-time updates

4. **Simple Debugging**
   - Only one consumer group to monitor
   - PENDING entries = actual work in progress

---

## 🧪 Testing

### **Verify Single Consumer**

```bash
# Check consumer groups
redis-cli XINFO GROUPS test-stream

# Should see:
# - test-group (active, used by Process buttons)
# - monitor-group (0 consumers, 0 pending - disabled)
```

### **Verify Visualization**

```bash
# Check that visualization doesn't create PENDING
redis-cli XPENDING test-stream test-group

# Should only show PENDING from Process buttons, not from page loads
```

---

## 🔄 Migration Notes

### **What Changed**

- ✅ Disabled `StreamMonitorService`
- ✅ Visualization uses `XREVRANGE` only
- ✅ Single consumer: `test-group`
- ✅ WebSocket events from actions only

### **What Stayed the Same**

- ✅ REST API endpoints
- ✅ WebSocket infrastructure
- ✅ Frontend components
- ✅ Process buttons behavior

---

## 📚 Related Documentation

- `WEBSOCKET_EVENTS_FIX.md` - WebSocket event filtering
- `DEVELOPER_GUIDE.md` - Java API usage
- `lua/CLAIM_OR_DLQ_GUIDE.md` - Lua function details

