# Flash Error Feature

## ✨ Feature: Visual Feedback for Failed Processing

Added red flash animation on messages when "Process & Fail" is clicked, providing immediate visual feedback.

---

## 🎯 What It Does

**Before:**
- User clicks "Process & Fail"
- Message stays in the stream (no visual feedback)
- User doesn't know which message was affected

**After:**
- User clicks "Process & Fail"
- **Message flashes red 3 times** ✅
- Clear visual indication of failed processing
- Animation lasts 1.8 seconds

---

## 🎨 Visual Effect

### **Animation Details**

```
Flash Cycle (0.6s each × 3 = 1.8s total):

0.0s: ⬜ White background (normal)
0.3s: 🟥 Red background (#fee2e2) + red border (#ef4444) + shadow
0.6s: ⬜ White background (normal)
0.9s: 🟥 Red background + red border + shadow
1.2s: ⬜ White background (normal)
1.5s: 🟥 Red background + red border + shadow
1.8s: ⬜ White background (normal) - DONE
```

**CSS Animation:**
```css
@keyframes flashRed {
  0%, 100% {
    background: white;
    border-color: #e2e8f0;
  }
  50% {
    background: #fee2e2;        /* Light red */
    border-color: #ef4444;      /* Red border */
    box-shadow: 0 0 8px rgba(239, 68, 68, 0.3);  /* Red glow */
  }
}

.message-cell.flash-error {
  animation: flashRed 0.6s ease-in-out 3;  /* 3 iterations */
}
```

---

## 🏗️ Architecture

### **Data Flow**

```
1. User clicks "Process & Fail"
   ↓
2. Backend: processNextMessage(shouldSucceed=false)
   ↓
3. Backend: Message NOT acknowledged (stays PENDING)
   ↓
4. Backend: Broadcast MESSAGE_RECLAIMED event via WebSocket
   ↓
5. Frontend: WebSocketService receives event
   ↓
6. Frontend: StreamViewerComponent.handleWebSocketEvent()
   ↓
7. Frontend: flashMessage(messageId) called
   ↓
8. Frontend: message.isFlashing = true
   ↓
9. Frontend: CSS animation triggers (1.8s)
   ↓
10. Frontend: setTimeout removes isFlashing after 1.8s
```

---

## 🔧 Implementation

### **1. Backend: Broadcast MESSAGE_RECLAIMED Event**

**File:** `src/main/java/com/redis/patterns/service/DLQMessagingService.java`

**Changes in `processNextMessage()` method:**

```java
} else {
    // Simulate failed processing - do NOT acknowledge (message will retry)
    response.put("success", true);
    response.put("message", String.format("✗ Message %s processing failed (will retry, deliveryCount: %d)",
        message.getId(), message.getDeliveryCount()));
    response.put("messageId", message.getId());
    response.put("deliveryCount", message.getDeliveryCount());
    response.put("wasRetry", message.isRetry());

    // Broadcast MESSAGE_RECLAIMED event for visual feedback (flash effect)
    webSocketEventService.broadcastEvent(DLQEvent.builder()
        .eventType(DLQEvent.EventType.MESSAGE_RECLAIMED)
        .messageId(message.getId())
        .payload(message.getFields())
        .deliveryCount(message.getDeliveryCount())
        .consumer(params.getConsumerName())
        .streamName(params.getStreamName())
        .details("Processing failed - will retry")
        .build());

    log.info("Message {} not acknowledged - will be retried", message.getId());
}
```

**Key Points:**
- Event type: `MESSAGE_RECLAIMED`
- Contains `messageId` for targeting the specific message
- Contains `streamName` for filtering by stream
- Contains `deliveryCount` for tracking retry attempts

---

### **2. Frontend: StreamMessage Interface**

**File:** `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

**Added `isFlashing` property:**

```typescript
export interface StreamMessage {
  id: string;
  fields: Record<string, string>;
  timestamp: string;
  isFlashing?: boolean;  // For visual feedback on failed processing
}
```

---

### **3. Frontend: Template with Flash Class**

**File:** `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

**Modified template:**

```html
<div *ngFor="let message of displayedMessages" 
     class="message-cell"
     [class.flash-error]="message.isFlashing">
  <div class="message-header">
    <span class="message-id">{{ message.id }}</span>
  </div>
  <div class="message-content">
    <div *ngFor="let field of getFields(message.fields)" class="field-row">
      <span class="field-key">{{ field.key }}</span>
      <span class="field-value">{{ field.value }}</span>
    </div>
  </div>
</div>
```

**Key Points:**
- `[class.flash-error]="message.isFlashing"` conditionally applies the CSS class
- Angular automatically adds/removes the class when `isFlashing` changes

---

### **4. Frontend: CSS Animation**

**File:** `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

**Added styles:**

```css
.message-cell.flash-error {
  animation: flashRed 0.6s ease-in-out 3;
}

@keyframes flashRed {
  0%, 100% {
    background: white;
    border-color: #e2e8f0;
  }
  50% {
    background: #fee2e2;
    border-color: #ef4444;
    box-shadow: 0 0 8px rgba(239, 68, 68, 0.3);
  }
}
```

---

### **5. Frontend: Handle MESSAGE_RECLAIMED Event**

**File:** `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

**Modified `handleWebSocketEvent()` method:**

```typescript
// Handle MESSAGE_RECLAIMED (flash effect for failed processing)
if (event.eventType === 'MESSAGE_RECLAIMED' && event.messageId) {
  console.log(`StreamViewer [${this.stream}]: Message reclaimed (failed processing):`, event.messageId);
  this.flashMessage(event.messageId);
  return;
}
```

---

### **6. Frontend: flashMessage() Method**

**File:** `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

**New method:**

```typescript
/**
 * Flash a message with red animation (for failed processing).
 * The animation lasts 1.8 seconds (3 flashes × 0.6s).
 */
private flashMessage(messageId: string): void {
  console.log(`StreamViewer [${this.stream}]: Flashing message ${messageId}`);
  
  // Find the message and set isFlashing to true
  const message = this.displayedMessages.find(m => m.id === messageId);
  if (message) {
    message.isFlashing = true;
    this.cdr.detectChanges();
    
    // Remove the flash class after animation completes (1.8 seconds)
    setTimeout(() => {
      message.isFlashing = false;
      this.cdr.detectChanges();
    }, 1800);
  } else {
    console.warn(`StreamViewer [${this.stream}]: Message ${messageId} not found for flashing`);
  }
}
```

**Key Points:**
- Finds the message by ID
- Sets `isFlashing = true` to trigger animation
- Uses `setTimeout` to remove the flag after 1.8s
- Calls `cdr.detectChanges()` to force Angular to update the view

---

## 🧪 Testing

### **Test 1: Basic Flash**

1. Generate messages
2. Click "Process & Fail"
3. **Expected:** The processed message flashes red 3 times (1.8 seconds total)

---

### **Test 2: Multiple Clicks**

1. Generate 4 messages
2. Click "Process & Fail" (message 1 flashes)
3. Wait 0.5 seconds
4. Click "Process & Fail" again (message 2 flashes)
5. **Expected:** Both messages flash independently

---

### **Test 3: Browser Console Verification**

1. Open browser console (F12)
2. Click "Process & Fail"
3. **Expected logs:**
   ```
   StreamViewer [test-stream]: Received WebSocket event: {eventType: "MESSAGE_RECLAIMED", messageId: "1764..."}
   StreamViewer [test-stream]: Message reclaimed (failed processing): 1764...
   StreamViewer [test-stream]: Flashing message 1764...
   ```

---

## 📊 Behavior Scenarios

### **Scenario 1: First Failure (deliveryCount = 1)**

```
Before:
  Message visible in test-stream (deliveryCount = 0)

User clicks "Process & Fail"

During (1.8s):
  Message flashes red 3 times ✅
  Status: "✗ Message ... processing failed (will retry, deliveryCount: 1)"

After:
  Message still visible (not acknowledged)
  Ready for retry after 100ms
```

---

### **Scenario 2: Second Failure (deliveryCount = 2)**

```
Before:
  Message visible in test-stream (deliveryCount = 1)

Wait 200ms (minIdleMs = 100ms)

User clicks "Process & Fail" again

During (1.8s):
  Message flashes red 3 times ✅
  Status: "✗ Message ... processing failed (will retry, deliveryCount: 2)"

After:
  Message still visible (not acknowledged)
  Ready for DLQ routing after 100ms
```

---

### **Scenario 3: Third Failure → DLQ (deliveryCount > maxDeliveries)**

```
Before:
  Message visible in test-stream (deliveryCount = 2)

Wait 200ms

User clicks "Process & Fail" again

Result:
  Message does NOT flash (routed to DLQ instead) ✅
  Message disappears from test-stream
  Message appears in test-stream:dlq
  Status: "✓ Message ... routed to DLQ (deliveryCount: 2)"
```

**Note:** The flash only happens when the message stays in the stream (not acknowledged). When routed to DLQ, the message is deleted from the main stream, so no flash occurs.

---

## 🎯 Benefits

### **1. Immediate Visual Feedback**
- User knows exactly which message was processed
- No need to check logs or status messages

### **2. Clear Error Indication**
- Red color universally indicates error/failure
- Flash animation draws attention

### **3. Non-Intrusive**
- Animation is short (1.8s)
- Doesn't block user interaction
- Automatically clears after animation

### **4. Consistent with UX Patterns**
- Similar to form validation errors
- Familiar to users

---

## 🔍 Technical Details

### **Why 3 Flashes?**

- **1 flash:** Too subtle, might be missed
- **2 flashes:** Feels incomplete
- **3 flashes:** Clear indication without being annoying
- **4+ flashes:** Too long, distracting

### **Why 0.6s per Flash?**

- **< 0.5s:** Too fast, hard to see
- **0.6s:** Comfortable viewing speed
- **> 1s:** Too slow, feels sluggish

### **Why setTimeout Instead of animationend Event?**

- **setTimeout:** Simple, predictable timing
- **animationend:** Requires event listener, more complex
- For this use case, setTimeout is sufficient

---

## ✅ Summary

✅ **Red flash animation** added for "Process & Fail"  
✅ **3 flashes × 0.6s** = 1.8 seconds total  
✅ **MESSAGE_RECLAIMED event** broadcasted from backend  
✅ **Automatic cleanup** after animation completes  
✅ **Non-intrusive** visual feedback  
✅ **Compilation successful** for both backend and frontend  

**The feature is ready to use! Restart both backend and frontend to test it out.** 🚀

