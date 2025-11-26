# Developer Guide: DLQ Messaging API

## 🎯 Overview

This guide shows you how to use the simplified DLQ (Dead Letter Queue) messaging API. The API provides a **unified interface** that combines new messages and retries in a single call.

---

## ✅ Unified API Usage

### **Single Loop for All Messages**

```java
@Service
public class OrderProcessor {
    
    @Autowired
    private DLQMessagingService dlqService;
    
    public void processOrders() {
        DLQParameters params = DLQParameters.builder()
            .streamName("orders")
            .dlqStreamName("orders:dlq")
            .consumerGroup("order-processors")
            .consumerName("processor-1")
            .minIdleMs(5000)      // Retry after 5 seconds
            .maxDeliveries(3)     // Max 3 attempts before DLQ
            .build();
        
        while (running) {
            // 🎯 Get next batch of messages (retries + new messages)
            List<DLQMessage> messages = dlqService.getNextMessages(params, 10);
            
            if (messages.isEmpty()) {
                Thread.sleep(1000);
                continue;
            }
            
            // Process all messages with a single loop
            for (DLQMessage msg : messages) {
                try {
                    // Your business logic here
                    processOrder(msg);
                    
                    // Acknowledge on success
                    dlqService.acknowledgeMessage(
                        msg.getStreamName(),
                        msg.getConsumerGroup(),
                        msg.getId()
                    );
                    
                    log.info("✅ Processed: {} ({})", 
                        msg.getId(), 
                        msg.getStateDescription());
                    
                } catch (Exception e) {
                    // Don't acknowledge - message will be retried automatically
                    log.error("❌ Failed: {} ({})", 
                        msg.getId(), 
                        msg.getStateDescription(), 
                        e);
                }
            }
            
            Thread.sleep(100);
        }
    }
    
    private void processOrder(DLQMessage msg) {
        String orderId = msg.getField("order_id");
        String type = msg.getField("type");
        
        // Check if this is a retry
        if (msg.isRetry()) {
            log.warn("Retrying order {} (attempt #{})", 
                orderId, 
                msg.getDeliveryCount());
        }
        
        // Your business logic
        orderService.process(orderId, type);
    }
}
```

### **Key Benefits**

✅ **One loop instead of two** - No need to manage separate loops for new messages and retries  
✅ **No code duplication** - Processing logic written once  
✅ **Automatic retry** - Just don't acknowledge failed messages  
✅ **Context available** - Know if it's a retry, delivery count, etc.  
✅ **Simpler error handling** - Catch exception = automatic retry  

---

## 📊 DLQMessage API Reference

### **Properties**

| Property | Type | Description |
|----------|------|-------------|
| `id` | String | Message ID (e.g., "1234567890-0") |
| `fields` | Map<String, String> | Message payload |
| `deliveryCount` | int | Number of delivery attempts (1, 2, 3...) |
| `retry` | boolean | true if this is a retry, false if new |
| `streamName` | String | Source stream name |
| `consumerGroup` | String | Consumer group name |
| `consumerName` | String | Consumer name |

### **Helper Methods**

```java
// Get a field value
String orderId = msg.getField("order_id");

// Check if first attempt
if (msg.isFirstAttempt()) {
    log.info("Processing new order");
}

// Check if retry
if (msg.hasBeenRetried()) {
    log.warn("This is a retry attempt");
}

// Get human-readable state
String state = msg.getStateDescription();
// Returns: "New message (first attempt)" or "Retry attempt #2"

// Get summary for logging
log.debug(msg.getSummary());
// Returns: "Message[id=123-0, deliveryCount=2, retry=true, fields={...}]"
```

---

## 🔄 Message Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    getNextMessages(10)                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ Priority: Retries│
                    └─────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │ claim_or_dlq()    │
                    │ (pending messages)│
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │ Found 3 retries   │
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │ Need 7 more?      │
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │ XREADGROUP        │
                    │ (new messages)    │
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │ Return 10 messages│
                    │ (3 retries + 7 new)│
                    └───────────────────┘
```

---

## 🚀 Best Practices

### 1. **Always use getNextMessages()**
```java
// ✅ Recommended
List<DLQMessage> messages = dlqService.getNextMessages(params, 10);
```

### 2. **Use message context for logging**
```java
for (DLQMessage msg : messages) {
    if (msg.isRetry()) {
        log.warn("Retry #{} for order {}", 
            msg.getDeliveryCount() - 1, 
            msg.getField("order_id"));
    }
}
```

### 3. **Handle retries differently if needed**
```java
for (DLQMessage msg : messages) {
    try {
        if (msg.getDeliveryCount() > 2) {
            // Last attempt - try harder or notify someone
            processWithExtraValidation(msg);
        } else {
            processNormally(msg);
        }
        
        dlqService.acknowledgeMessage(
            msg.getStreamName(), 
            msg.getConsumerGroup(), 
            msg.getId()
        );
    } catch (Exception e) {
        // Will retry automatically
    }
}
```

---

## 📝 Summary

**Key Benefits:**
- ✅ Single loop (no duplication)
- ✅ Full context (deliveryCount, isRetry)
- ✅ Automatic retry handling
- ✅ Simple and maintainable

**Recommendation:** Always use `getNextMessages()` for message processing! 🎉

