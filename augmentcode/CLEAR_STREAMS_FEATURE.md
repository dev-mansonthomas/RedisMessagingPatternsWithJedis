# Clear All Streams Feature

## ✨ New Feature: Clear All Streams Button

Added a "Clear All Streams" button to the DLQ Actions component that deletes both the main stream and DLQ stream.

---

## 🎯 What It Does

**Button:** "Clear All Streams" (orange button with 🗑️ icon)

**Action:** 
- Deletes `test-stream` (if exists)
- Deletes `test-stream:dlq` (if exists)
- Shows confirmation dialog before deletion
- Cannot be undone

---

## 🎨 UI Layout

```
┌─────────────────────────────┐
│   Message Processing        │
├─────────────────────────────┤
│                             │
│  ⚡ Generate Messages       │  ← Blue (Generate)
│                             │
│  ─────────────────────────  │  ← Separator
│                             │
│  ✓ Process & Success        │  ← Green (Process)
│                             │
│  ✗ Process & Fail           │  ← Red (Fail)
│                             │
│  ─────────────────────────  │  ← Separator
│                             │
│  🗑️ Clear All Streams       │  ← NEW (Orange)
│                             │
└─────────────────────────────┘
```

**Button Colors:**
- **Generate Messages**: Blue (`#3b82f6`)
- **Process & Success**: Green (`#10b981`)
- **Process & Fail**: Red (`#ef4444`)
- **Clear All Streams**: Orange (`#f59e0b`)

---

## 🔒 Safety Features

### **1. Confirmation Dialog**

Before deleting, a browser confirmation dialog appears:

```
Are you sure you want to delete all streams 
(test-stream and test-stream:dlq)? 
This action cannot be undone.

[Cancel] [OK]
```

**User must click OK to proceed.**

### **2. Graceful Handling**

- If a stream doesn't exist, it's skipped (no error)
- Both streams are deleted independently
- Partial success is reported (e.g., "Cleared 1/2 streams")

---

## 🔧 Implementation

### **Frontend Component**

**File:** `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`

**Method: `clearAllStreams()`**

```typescript
clearAllStreams(): void {
  // Show confirmation dialog
  if (!confirm('Are you sure you want to delete all streams...')) {
    return;
  }

  this.isProcessing.set(true);
  this.statusMessage.set('Clearing streams...');

  // Delete both streams
  const streams = ['test-stream', 'test-stream:dlq'];
  
  streams.forEach((streamName) => {
    this.http.delete(`${this.apiUrl}/stream/${streamName}`).subscribe({
      next: (response) => {
        // Handle success
      },
      error: (error) => {
        // Handle error
      }
    });
  });
}
```

---

### **Backend Controller**

**File:** `src/main/java/com/redis/patterns/controller/DLQController.java`

**Endpoint: `DELETE /api/dlq/stream/{streamName}`**

```java
@DeleteMapping("/stream/{streamName}")
public ResponseEntity<Map<String, Object>> deleteStream(@PathVariable String streamName) {
    log.info("Deleting stream: {}", streamName);

    try {
        boolean deleted = dlqMessagingService.deleteStream(streamName);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", deleted);
        response.put("streamName", streamName);
        response.put("message", deleted ? "Stream deleted successfully" : "Stream does not exist");
        
        return ResponseEntity.ok(response);
    } catch (Exception e) {
        log.error("Error deleting stream '{}'", streamName, e);
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "Error: " + e.getMessage());
        return ResponseEntity.internalServerError().body(response);
    }
}
```

---

### **Backend Service**

**File:** `src/main/java/com/redis/patterns/service/DLQMessagingService.java`

**Method: `deleteStream(String streamName)`**

```java
public boolean deleteStream(String streamName) {
    log.info("Deleting stream: {}", streamName);
    
    try (var jedis = jedisPool.getResource()) {
        // Check if stream exists
        if (!jedis.exists(streamName)) {
            log.info("Stream '{}' does not exist", streamName);
            return false;
        }
        
        // Delete the stream
        jedis.del(streamName);
        log.info("Stream '{}' deleted successfully", streamName);
        
        // Broadcast event
        webSocketEventService.broadcastEvent(DLQEvent.builder()
            .eventType(DLQEvent.EventType.INFO)
            .streamName(streamName)
            .details("Stream deleted")
            .build());
        
        return true;
        
    } catch (Exception e) {
        log.error("Failed to delete stream '{}'", streamName, e);
        throw new RuntimeException("Failed to delete stream", e);
    }
}
```

**Redis Command Used:** `DEL <streamName>`

---

## 📊 Behavior

### **Scenario 1: Both Streams Exist**

```
Before:
  test-stream: 10 messages
  test-stream:dlq: 3 messages

User clicks "Clear All Streams" → Confirms

After:
  test-stream: deleted ✅
  test-stream:dlq: deleted ✅

Status: "✓ Cleared 2 streams successfully"
```

---

### **Scenario 2: Only Main Stream Exists**

```
Before:
  test-stream: 5 messages
  test-stream:dlq: doesn't exist

User clicks "Clear All Streams" → Confirms

After:
  test-stream: deleted ✅
  test-stream:dlq: skipped (didn't exist)

Status: "✓ Cleared 1 streams successfully"
```

---

### **Scenario 3: No Streams Exist**

```
Before:
  test-stream: doesn't exist
  test-stream:dlq: doesn't exist

User clicks "Clear All Streams" → Confirms

After:
  (no changes)

Status: "✓ Cleared 0 streams successfully"
```

---

### **Scenario 4: User Cancels**

```
User clicks "Clear All Streams" → Clicks "Cancel"

Result: Nothing happens, streams remain intact
```

---

## 📝 Status Messages

**Success (both deleted):**
```
✓ Cleared 2 streams successfully
```

**Partial success:**
```
⚠ Cleared 1/2 streams (1 failed)
```

**No streams existed:**
```
✓ Cleared 0 streams successfully
```

**Error:**
```
Error: Failed to clear streams
```

**Auto-dismiss:** Status messages disappear after 3 seconds.

---

## 🧪 Testing

### **Test 1: Delete Both Streams**

```bash
# Create messages in both streams
redis-cli XADD test-stream * type order.created order_id 1001
redis-cli XADD test-stream:dlq * type order.cancelled order_id 1002

# Verify they exist
redis-cli EXISTS test-stream test-stream:dlq
# Expected: (integer) 2

# Click "Clear All Streams" in UI → Confirm

# Verify they're deleted
redis-cli EXISTS test-stream test-stream:dlq
# Expected: (integer) 0
```

---

### **Test 2: Confirmation Dialog**

1. Click "Clear All Streams"
2. Confirmation dialog appears
3. Click "Cancel"
4. Verify streams still exist

---

### **Test 3: UI Updates**

1. Generate messages (stream viewer shows messages)
2. Click "Clear All Streams" → Confirm
3. Verify stream viewer shows "No messages"

---

## 🎯 Use Cases

### **1. Reset Demo**
Quickly clear all data between demonstrations.

### **2. Clean Slate**
Start fresh when testing new scenarios.

### **3. Troubleshooting**
Remove corrupted or problematic messages.

### **4. Development**
Clear test data during development.

---

## ⚠️ Important Notes

### **1. Cannot Be Undone**
Once deleted, messages are permanently lost.

### **2. Deletes Consumer Groups**
Deleting a stream also deletes all associated consumer groups and PENDING entries.

### **3. No Backup**
There is no automatic backup before deletion.

### **4. Production Warning**
This feature should be disabled or restricted in production environments.

---

## 🔍 What Gets Deleted

When you click "Clear All Streams", the following are deleted:

**For `test-stream`:**
- All messages in the stream
- Consumer group `test-group`
- Consumer group `monitor-group`
- All PENDING entries
- Stream metadata

**For `test-stream:dlq`:**
- All messages in the DLQ stream
- Consumer group `test-group-dlq`
- All PENDING entries
- Stream metadata

---

## 📚 Related Files

**Modified:**
- `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`
- `src/main/java/com/redis/patterns/controller/DLQController.java`
- `src/main/java/com/redis/patterns/service/DLQMessagingService.java`

---

## ✅ Benefits

1. **Quick Reset**: Clear all data with one click
2. **Safe**: Confirmation dialog prevents accidental deletion
3. **Graceful**: Handles non-existent streams without errors
4. **Feedback**: Clear status messages
5. **Visual**: Orange color indicates destructive action

---

## 🎉 Summary

✅ **New button**: "Clear All Streams" (orange, with 🗑️ icon)  
✅ **Deletes both streams**: test-stream and test-stream:dlq  
✅ **Confirmation dialog**: Prevents accidental deletion  
✅ **Graceful handling**: Skips non-existent streams  
✅ **Status feedback**: Shows success/error messages  
✅ **Visual separator**: Clear distinction from other buttons  

**The feature is ready to use! Restart Spring Boot and test it out.** 🚀

