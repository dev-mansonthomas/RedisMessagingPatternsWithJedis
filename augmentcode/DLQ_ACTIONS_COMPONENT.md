# DLQ Actions Component

## Overview

The **DLQ Actions Component** provides an interactive UI for processing messages with success or failure simulation. It's placed between the main stream and DLQ stream viewers, allowing developers to manually test message processing flows.

---

## Features

### Two Action Buttons

1. **Process & Success** (Green)
   - Gets the next available message (retries first, then new messages)
   - Processes and **acknowledges** the message
   - Message is removed from the stream
   - Shows success feedback

2. **Process & Fail** (Red)
   - Gets the next available message
   - Processes but **does NOT acknowledge** the message
   - Message remains PENDING and will be retried
   - Shows failure feedback

---

## Architecture

### Frontend Component

**File:** `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`

**Features:**
- Standalone Angular component
- Two styled buttons (success/fail)
- Loading state management
- Status message display
- HTTP client for REST API calls

**API Endpoint:**
```typescript
POST http://localhost:8080/api/dlq/process
Body: { shouldSucceed: true/false }
```

### Backend REST Endpoint

**File:** `src/main/java/com/redis/patterns/controller/DLQController.java`

**Endpoint:**
```java
@PostMapping("/process")
public ResponseEntity<Map<String, Object>> processMessage(@RequestBody Map<String, Object> request)
```

**Parameters:**
- `shouldSucceed` (boolean): Whether to simulate success or failure

**Response:**
```json
{
  "success": true,
  "message": "✓ Message 1234-0 processed successfully (deliveryCount: 1)",
  "messageId": "1234-0",
  "deliveryCount": 1,
  "wasRetry": false
}
```

### Backend Service Method

**File:** `src/main/java/com/redis/patterns/service/DLQMessagingService.java`

**Method:**
```java
public Map<String, Object> processNextMessage(boolean shouldSucceed)
```

**Flow:**
1. Gets DLQ configuration for "test-stream"
2. Calls `getNextMessages(params, 1)` to get next message
3. If `shouldSucceed == true`:
   - Calls `acknowledgeMessage()` to ACK the message
   - Message removed from stream
4. If `shouldSucceed == false`:
   - Does NOT acknowledge
   - Message remains PENDING for retry
5. Returns response with message details

---

## UI Layout

The component is placed in the DLQ page between the two stream viewers:

```
┌─────────────────────────────────────────────────────────────┐
│                    DLQ Configuration                        │
└─────────────────────────────────────────────────────────────┘

┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│              │  │   Message    │  │              │
│  Main Stream │  │  Processing  │  │  DLQ Stream  │
│              │  │              │  │              │
│  test-stream │  │ ┌──────────┐ │  │ test-stream  │
│              │  │ │ Process  │ │  │    :dlq      │
│              │  │ │ & Success│ │  │              │
│              │  │ └──────────┘ │  │              │
│              │  │              │  │              │
│              │  │ ┌──────────┐ │  │              │
│              │  │ │ Process  │ │  │              │
│              │  │ │  & Fail  │ │  │              │
│              │  │ └──────────┘ │  │              │
└──────────────┘  └──────────────┘  └──────────────┘
```

**CSS Grid Layout:**
```css
.streams-section {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  gap: 16px;
}

.actions-column {
  width: 200px;
}
```

---

## Usage Example

### 1. Start the application

```bash
# Backend
mvn spring-boot:run

# Frontend
cd frontend && npm start
```

### 2. Navigate to DLQ page

Open: `http://localhost:4200/dlq`

### 3. Add test messages

Use the DLQ Config component to produce test messages.

### 4. Process messages

- Click **"Process & Success"** to successfully process a message
- Click **"Process & Fail"** to simulate a failure (message will retry)

### 5. Observe behavior

- **Success**: Message disappears from main stream
- **Fail**: Message stays in main stream, delivery count increases
- After `maxDeliveries` failures: Message moves to DLQ stream

---

## Testing Flow

### Scenario 1: Successful Processing

1. Produce a message to `test-stream`
2. Click **"Process & Success"**
3. ✅ Message is acknowledged and removed
4. Status: "✓ Message 1234-0 processed successfully"

### Scenario 2: Failed Processing with Retry

1. Produce a message to `test-stream`
2. Click **"Process & Fail"** (1st attempt)
   - Message stays PENDING, deliveryCount = 1
3. Wait 5 seconds (minIdleMs)
4. Click **"Process & Fail"** (2nd attempt)
   - Message stays PENDING, deliveryCount = 2
5. Wait 5 seconds
6. Click **"Process & Fail"** (3rd attempt, assuming maxDeliveries = 2)
   - Message moves to DLQ stream
   - Status: "✗ Message processing failed (will retry)"

---

## Files Modified/Created

### Created Files

1. `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`
   - New standalone Angular component
   - Two action buttons with styling
   - HTTP client integration

### Modified Files

1. `frontend/src/app/components/dlq/dlq.component.ts`
   - Added `DlqActionsComponent` import
   - Added component to template between streams
   - Updated CSS grid layout (1fr auto 1fr)
   - Added `.actions-column` styles

2. `src/main/java/com/redis/patterns/controller/DLQController.java`
   - Added `@PostMapping("/process")` endpoint
   - Handles success/failure simulation

3. `src/main/java/com/redis/patterns/service/DLQMessagingService.java`
   - Added `processNextMessage(boolean shouldSucceed)` method
   - Uses unified `getNextMessages()` API
   - Handles acknowledgment based on success flag
   - Added `DLQConfigService` dependency injection
   - Added `DLQConfigRequest` import

---

## Benefits

✅ **Interactive Testing**: Manually test message processing flows  
✅ **Visual Feedback**: See messages move between streams in real-time  
✅ **Retry Simulation**: Test retry logic with failure scenarios  
✅ **DLQ Routing**: Verify messages route to DLQ after max retries  
✅ **Developer-Friendly**: Simple UI for understanding DLQ behavior  
✅ **Real-time Updates**: WebSocket integration shows live changes  

---

## Next Steps

- Add message details display (show current message being processed)
- Add batch processing (process multiple messages at once)
- Add statistics (success rate, retry count, etc.)
- Add message filtering (process specific message types)

