# Generate Messages Feature

## ✨ New Feature: Generate Random Messages

Added a "Generate Messages" button to the DLQ Actions component that creates 4 random order messages with realistic data.

---

## 🎯 What It Does

**Button:** "Generate Messages" (blue button with ⚡ icon)

**Action:** Creates 4 random messages with:
- **80% chance**: `order.created` (successful orders)
- **20% chance**: `order.cancelled` (cancelled orders)

---

## 📋 Message Types

### **order.created** (80% probability)

```json
{
  "type": "order.created",
  "order_id": "1234",
  "amount": "59.90"
}
```

**Fields:**
- `type`: Always `"order.created"`
- `order_id`: Random number between 1000-9999
- `amount`: Random amount between 10.00-110.00 (2 decimal places)

---

### **order.cancelled** (20% probability)

```json
{
  "type": "order.cancelled",
  "order_id": "1003",
  "reason": "payment_failed"
}
```

**Fields:**
- `type`: Always `"order.cancelled"`
- `order_id`: Random number between 1000-9999
- `reason`: One of:
  - `payment_failed`
  - `out_of_stock`
  - `customer_request`
  - `fraud_detected`

---

## 🎨 UI Layout

```
┌─────────────────────────────┐
│   Message Processing        │
├─────────────────────────────┤
│                             │
│  ⚡ Generate Messages       │  ← NEW (Blue button)
│                             │
│  ─────────────────────────  │  ← Separator
│                             │
│  ✓ Process & Success        │  ← Existing (Green button)
│                             │
│  ✗ Process & Fail           │  ← Existing (Red button)
│                             │
│  Status: Generated 4 msgs   │  ← Status message
│                             │
└─────────────────────────────┘
```

---

## 🔧 Implementation Details

### **Frontend Component**

**File:** `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`

**Key Methods:**

#### `generateMessages()`
- Creates 4 random messages using `createRandomMessages(4)`
- Sends each message to the backend via POST `/api/dlq/produce`
- Staggers requests by 100ms to avoid overwhelming the server
- Shows progress and success/error status

#### `createRandomMessages(count: number)`
- Generates `count` random messages
- 80% probability for `order.created`
- 20% probability for `order.cancelled`
- Random order IDs (1000-9999)
- Random amounts (10.00-110.00) for created orders
- Random reasons for cancelled orders

---

## 📊 Example Output

**Clicking "Generate Messages" might produce:**

```
Message 1:
  type: order.created
  order_id: 1542
  amount: 73.25

Message 2:
  type: order.created
  order_id: 8901
  amount: 42.50

Message 3:
  type: order.cancelled
  order_id: 3456
  reason: payment_failed

Message 4:
  type: order.created
  order_id: 7123
  amount: 89.99
```

**Expected distribution over 100 clicks:**
- ~320 `order.created` messages (80%)
- ~80 `order.cancelled` messages (20%)

---

## 🎯 Use Cases

### **1. Quick Testing**
Instead of manually producing messages one by one, generate 4 at once for testing.

### **2. Realistic Data**
Messages have realistic order IDs, amounts, and cancellation reasons.

### **3. Demo Scenarios**
Quickly populate the stream for demonstrations or screenshots.

### **4. Load Testing**
Click multiple times to generate many messages for testing processing performance.

---

## 🔄 Workflow Example

**Scenario: Test the full DLQ flow**

1. **Generate Messages**
   - Click "Generate Messages"
   - 4 messages appear in the stream viewer
   - Status: "✓ Generated 4 messages successfully"

2. **Process Some Successfully**
   - Click "Process & Success" 2 times
   - 2 messages disappear from the stream
   - Remaining: 2 messages

3. **Simulate Failures**
   - Click "Process & Fail" 3 times on one message
   - After 3rd attempt, message moves to DLQ
   - Remaining in main stream: 1 message

4. **Generate More**
   - Click "Generate Messages" again
   - 4 new messages added
   - Total in main stream: 5 messages

---

## 🎨 Styling

**Button Colors:**
- **Generate Messages**: Blue gradient (`#3b82f6` → `#2563eb`)
- **Process & Success**: Green gradient (`#10b981` → `#059669`)
- **Process & Fail**: Red gradient (`#ef4444` → `#dc2626`)

**Visual Separator:**
- Thin gray line between Generate and Process buttons
- Helps distinguish between message creation and processing

---

## 📝 Status Messages

**Success:**
```
✓ Generated 4 messages successfully
```

**Partial Success:**
```
⚠ Generated 3/4 messages (1 failed)
```

**Error:**
```
Error: Failed to produce message
```

**Auto-dismiss:** Status messages disappear after 3 seconds.

---

## 🧪 Testing

### **Test 1: Generate Messages**

1. Click "Generate Messages"
2. Verify 4 messages appear in stream viewer
3. Check Redis:
   ```bash
   redis-cli XLEN test-stream
   # Expected: +4
   ```

### **Test 2: Message Distribution**

1. Click "Generate Messages" 10 times (40 messages total)
2. Count message types in Redis Insight
3. Expected distribution:
   - ~32 `order.created` (80%)
   - ~8 `order.cancelled` (20%)

### **Test 3: Realistic Data**

1. Click "Generate Messages"
2. Inspect messages in stream viewer
3. Verify:
   - Order IDs are 4-digit numbers
   - Amounts have 2 decimal places
   - Cancellation reasons are valid

---

## 🔍 Backend Endpoint Used

**Endpoint:** `POST /api/dlq/produce`

**Request Body:**
```json
{
  "streamName": "test-stream",
  "payload": {
    "type": "order.created",
    "order_id": "1234",
    "amount": "59.90"
  }
}
```

**Response:**
```json
{
  "success": true,
  "messageId": "1763741315741-0",
  "streamName": "test-stream"
}
```

---

## 📚 Related Files

**Modified:**
- `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`

**Related:**
- `src/main/java/com/redis/patterns/controller/DLQController.java` (produce endpoint)
- `src/main/java/com/redis/patterns/service/DLQMessagingService.java` (produceMessage method)

---

## ✅ Benefits

1. **Faster Testing**: Generate 4 messages with one click
2. **Realistic Data**: Random but realistic order data
3. **Consistent Distribution**: 80/20 split matches real-world scenarios
4. **Better UX**: Clear visual separation from processing buttons
5. **Batch Operations**: Easy to populate stream for testing

---

## 🎉 Summary

✅ **New button**: "Generate Messages" (blue, with ⚡ icon)  
✅ **Creates 4 messages**: 80% order.created, 20% order.cancelled  
✅ **Random data**: Order IDs, amounts, cancellation reasons  
✅ **Visual separator**: Clear distinction from processing buttons  
✅ **Status feedback**: Shows success/error messages  

**The feature is ready to use! Restart the frontend and test it out.** 🚀

