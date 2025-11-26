# Developer Guide: claim_or_dlq Function

## 📚 Overview

The `claim_or_dlq` Lua function processes **PENDING** (unacknowledged) messages from a Redis Stream and routes them based on their delivery count.

- **Messages with `deliveryCount < maxDeliveries`**: Reclaimed for reprocessing
- **Messages with `deliveryCount >= maxDeliveries`**: Routed to DLQ and acknowledged

### ⚠️ Important

This function does **NOT** read new messages. It only processes messages that have already been read but not acknowledged (PENDING).

---

## 🎯 Recommended Usage

Use the **unified Java API** `getNextMessages()` which abstracts the complexity:

```java
// Single loop for everything!
List<DLQMessage> messages = dlqService.getNextMessages(params, 10);

for (DLQMessage msg : messages) {
    try {
        processMessage(msg);
        dlqService.acknowledgeMessage(
            msg.getStreamName(),
            msg.getConsumerGroup(),
            msg.getId()
        );
    } catch (Exception e) {
        // No ACK = automatic retry
    }
}
```

**Benefits:**
- ✅ Single loop (no duplication)
- ✅ Full context available (deliveryCount, isRetry)
- ✅ Automatic retry (no ACK = retry)
- ✅ Simpler and more maintainable code

---

## 📋 Complete Processing Flow

### Step 1: XREADGROUP (read new messages)

```
→ Message moves from unread to PENDING
→ deliveryCount = 1
→ Message assigned to consumer
```

### Step 2: Process the message

```
→ If success: XACK (message removed from stream)
→ If failure: No ACK (message remains PENDING)
```

### Step 3: claim_or_dlq (after minIdleMs)

```
→ XPENDING finds idle messages
→ If deliveryCount < maxDeliveries:
    - XCLAIM (deliveryCount incremented)
    - Message returned for reprocessing
→ If deliveryCount >= maxDeliveries:
    - XCLAIM (to increment count)
    - XADD to DLQ
    - XACK in source stream
    - Message NOT returned (it's in the DLQ)
```

### Step 4: Repeat steps 2-3 until success or DLQ

---

## 📊 Concrete Example

### Configuration

```
- Stream: "orders"
- DLQ: "orders:dlq"
- Group: "processors"
- Consumer: "worker-1"
- minIdleMs: 5000 (5 seconds)
- maxDeliveries: 3
```

### Detailed Scenario

#### 1) XREADGROUP reads message "1234-0"

```
→ deliveryCount = 1, status = PENDING
```

#### 2) Processing fails, no ACK

```
→ Message remains PENDING with deliveryCount = 1
```

#### 3) Wait 5 seconds, then claim_or_dlq

```bash
FCALL claim_or_dlq 2 orders orders:dlq processors worker-1 5000 10 3
```

```
→ XPENDING finds the message (idle >= 5000ms)
→ deliveryCount (1) < maxDeliveries (3)
→ XCLAIM: deliveryCount goes from 1 to 2
→ Message returned for reprocessing
```

#### 4) Processing fails again, no ACK

```
→ Message remains PENDING with deliveryCount = 2
```

#### 5) Wait 5 seconds, then claim_or_dlq

```
→ deliveryCount (2) < maxDeliveries (3)
→ XCLAIM: deliveryCount goes from 2 to 3
→ Message returned for reprocessing
```

#### 6) Processing fails again, no ACK

```
→ Message remains PENDING with deliveryCount = 3
```

#### 7) Wait 5 seconds, then claim_or_dlq

```
→ deliveryCount (3) >= maxDeliveries (3)
→ XCLAIM (to increment count)
→ XADD to "orders:dlq"
→ XACK in "orders"
→ Message NOT returned (it's in the DLQ)
```

---

## 🔧 Function Parameters

### KEYS

- `keys[1]`: Source stream (e.g., `"orders"`)
- `keys[2]`: DLQ stream (e.g., `"orders:dlq"`)

### ARGS

- `args[1]`: Group (e.g., `"processors"`)
- `args[2]`: Consumer (e.g., `"worker-1"`)
- `args[3]`: minIdle in ms (e.g., `5000` = 5 seconds)
- `args[4]`: count (max messages to process, e.g., `10`)
- `args[5]`: maxDeliveries (retry threshold, e.g., `3`)

### Return Value

Array of reclaimed messages for reprocessing (Redis Stream format).

**Note:** Messages routed to the DLQ are **NOT** returned.

---

## 💡 Best Practice

For new development, use the **unified Java API**:

- `DLQMessagingService.getNextMessages()`
- `DLQMessage` class with full context
- See: `DEVELOPER_GUIDE.md`

This Lua function is used **internally** by the Java API.

---

## 📚 References

- **Complete developer guide**: `DEVELOPER_GUIDE.md`
- **Code example**: `src/main/java/com/redis/patterns/example/SimpleMessageProcessor.java`
- **Changelog**: `CHANGELOG_API_SIMPLIFICATION.md`

