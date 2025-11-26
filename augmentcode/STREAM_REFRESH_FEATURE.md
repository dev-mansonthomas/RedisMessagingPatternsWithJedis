# Stream Refresh Feature

## ✨ Feature: Automatic Stream Refresh After Clear

Added automatic refresh of all stream viewers after clearing streams with the "Clear All Streams" button.

---

## 🎯 What It Does

**Before:**
- User clicks "Clear All Streams"
- Streams are deleted in Redis
- UI still shows old messages (until manual F5 refresh)

**After:**
- User clicks "Clear All Streams"
- Streams are deleted in Redis
- **All stream viewers automatically refresh** ✅
- UI immediately shows "No messages"

---

## 🏗️ Architecture

### **Service-Based Communication**

Created a new service to coordinate refresh events across components:

```
┌─────────────────────┐
│  DlqActionsComponent│
│                     │
│  [Clear All Streams]│
│         ↓           │
│  triggerRefresh()   │
└──────────┬──────────┘
           │
           ↓
┌──────────────────────┐
│ StreamRefreshService │  ← New Service
│                      │
│  refresh$ Observable │
└──────────┬───────────┘
           │
           ↓ (broadcasts to all subscribers)
           │
    ┌──────┴──────┬──────────────┐
    ↓             ↓              ↓
┌─────────┐  ┌─────────┐  ┌─────────┐
│ Stream  │  │ Stream  │  │ Stream  │
│ Viewer  │  │ Viewer  │  │ Viewer  │
│   #1    │  │   #2    │  │   #3    │
│         │  │         │  │         │
│ test-   │  │ test-   │  │ (any    │
│ stream  │  │ stream: │  │ other)  │
│         │  │ dlq     │  │         │
└─────────┘  └─────────┘  └─────────┘
     ↓            ↓            ↓
  Reload       Reload       Reload
   Data         Data         Data
```

---

## 🔧 Implementation

### **1. New Service: StreamRefreshService**

**File:** `frontend/src/app/services/stream-refresh.service.ts`

```typescript
import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class StreamRefreshService {
  private refreshSubject = new Subject<void>();
  
  /**
   * Observable that emits when streams should be refreshed.
   */
  refresh$ = this.refreshSubject.asObservable();
  
  /**
   * Trigger a refresh of all stream viewers.
   */
  triggerRefresh(): void {
    console.log('StreamRefreshService: Triggering refresh for all streams');
    this.refreshSubject.next();
  }
}
```

**Key Features:**
- **Singleton service** (`providedIn: 'root'`)
- **RxJS Subject** for event broadcasting
- **Observable** for components to subscribe to

---

### **2. Modified: StreamViewerComponent**

**File:** `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`

**Changes:**

1. **Import the service:**
```typescript
import { StreamRefreshService } from '../../services/stream-refresh.service';
```

2. **Inject the service:**
```typescript
private refreshService = inject(StreamRefreshService);
private refreshSubscription?: Subscription;
```

3. **Subscribe to refresh events:**
```typescript
ngOnInit(): void {
  if (this.stream && this.group && this.consumer) {
    this.loadInitialData();
    this.connectWebSocket();
    this.subscribeToRefresh();  // ← New
  }
}

private subscribeToRefresh(): void {
  this.refreshSubscription = this.refreshService.refresh$.subscribe(() => {
    console.log(`StreamViewer [${this.stream}]: Received refresh event, reloading data`);
    this.loadInitialData();  // ← Reload data
  });
}
```

4. **Cleanup on destroy:**
```typescript
ngOnDestroy(): void {
  this.eventSubscription?.unsubscribe();
  this.statusSubscription?.unsubscribe();
  this.refreshSubscription?.unsubscribe();  // ← New
}
```

---

### **3. Modified: DlqActionsComponent**

**File:** `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`

**Changes:**

1. **Import the service:**
```typescript
import { StreamRefreshService } from '../../services/stream-refresh.service';
```

2. **Inject the service:**
```typescript
private refreshService = inject(StreamRefreshService);
```

3. **Trigger refresh after successful deletion:**
```typescript
clearAllStreams(): void {
  // ... deletion logic ...
  
  streams.forEach((streamName) => {
    this.http.delete(`${this.apiUrl}/stream/${streamName}`).subscribe({
      next: (response) => {
        completed++;
        if (completed + errors === streams.length) {
          // ... status messages ...
          
          // Trigger refresh of all stream viewers
          this.refreshService.triggerRefresh();  // ← New
          
          setTimeout(() => this.statusMessage.set(''), 3000);
        }
      },
      error: (error) => {
        errors++;
        if (completed + errors === streams.length) {
          // ... error handling ...
          
          // Trigger refresh even on partial success
          if (completed > 0) {
            this.refreshService.triggerRefresh();  // ← New
          }
        }
      }
    });
  });
}
```

---

## 🔄 Data Flow

### **Complete Flow: Clear All Streams**

```
1. User clicks "Clear All Streams"
   ↓
2. Confirmation dialog appears
   ↓
3. User confirms
   ↓
4. DlqActionsComponent sends DELETE requests
   ↓
5. Backend deletes streams from Redis
   ↓
6. Backend returns success response
   ↓
7. DlqActionsComponent receives response
   ↓
8. DlqActionsComponent calls refreshService.triggerRefresh()
   ↓
9. StreamRefreshService broadcasts refresh event
   ↓
10. All StreamViewerComponents receive event
   ↓
11. Each StreamViewer calls loadInitialData()
   ↓
12. StreamViewers fetch fresh data from backend
   ↓
13. UI updates to show "No messages"
```

---

## 📊 Behavior

### **Scenario 1: Both Streams Cleared**

```
Before:
  test-stream viewer: Shows 10 messages
  test-stream:dlq viewer: Shows 3 messages

User clicks "Clear All Streams" → Confirms

After (immediately):
  test-stream viewer: Shows "No messages" ✅
  test-stream:dlq viewer: Shows "No messages" ✅
```

---

### **Scenario 2: Partial Success**

```
Before:
  test-stream viewer: Shows 5 messages
  test-stream:dlq viewer: Shows 2 messages

User clicks "Clear All Streams" → Confirms
Backend deletes test-stream ✅
Backend fails to delete test-stream:dlq ❌

After (immediately):
  test-stream viewer: Shows "No messages" ✅
  test-stream:dlq viewer: Shows 2 messages (still there) ✅
  Status: "⚠ Cleared 1/2 streams (1 failed)"
```

**Note:** Refresh is triggered even on partial success, so the UI accurately reflects the current state.

---

### **Scenario 3: Multiple Tabs**

```
Tab 1: Shows test-stream with 10 messages
Tab 2: Shows test-stream with 10 messages

User in Tab 1: Clicks "Clear All Streams" → Confirms

Result:
  Tab 1: Refreshes immediately ✅
  Tab 2: Refreshes immediately ✅ (via WebSocket)
```

**Note:** The refresh service works within a single tab. For multi-tab sync, WebSocket events handle the updates.

---

## 🧪 Testing

### **Test 1: Basic Refresh**

1. Generate messages (stream viewer shows messages)
2. Click "Clear All Streams" → Confirm
3. **Expected:** Stream viewers immediately show "No messages"
4. **No F5 refresh needed**

---

### **Test 2: Multiple Stream Viewers**

1. Open DLQ page (shows both test-stream and test-stream:dlq viewers)
2. Generate messages in both streams
3. Click "Clear All Streams" → Confirm
4. **Expected:** Both viewers refresh simultaneously

---

### **Test 3: Browser Console Verification**

1. Open browser console (F12)
2. Click "Clear All Streams" → Confirm
3. **Expected logs:**
   ```
   StreamRefreshService: Triggering refresh for all streams
   StreamViewer [test-stream]: Received refresh event, reloading data
   StreamViewer [test-stream:dlq]: Received refresh event, reloading data
   StreamViewer [test-stream]: Loading initial data (pageSize: 10)
   StreamViewer [test-stream:dlq]: Loading initial data (pageSize: 10)
   ```

---

### **Test 4: Partial Success**

1. Stop Redis (to simulate failure)
2. Generate messages
3. Start Redis
4. Delete only test-stream manually: `redis-cli DEL test-stream`
5. Click "Clear All Streams" → Confirm
6. **Expected:** 
   - test-stream viewer shows "No messages"
   - test-stream:dlq viewer shows "No messages" (was already empty)
   - Status shows partial success

---

## 🎯 Benefits

### **1. Better UX**
- No manual F5 refresh needed
- Immediate visual feedback

### **2. Consistency**
- UI always reflects actual Redis state
- No stale data displayed

### **3. Scalability**
- Works with any number of stream viewers
- Easy to add more viewers

### **4. Decoupled Architecture**
- Components don't need direct references
- Service-based communication

### **5. Reusable**
- Can be used for other refresh scenarios
- Not limited to "Clear All Streams"

---

## 🔍 Technical Details

### **RxJS Subject vs BehaviorSubject**

**Why Subject?**
- We only want to trigger refresh on-demand
- No need to store the last value
- Subscribers only react to new events

**If we used BehaviorSubject:**
- Would need an initial value
- New subscribers would immediately trigger a refresh
- Not desired behavior for this use case

---

### **Memory Management**

**Subscription Cleanup:**
```typescript
ngOnDestroy(): void {
  this.refreshSubscription?.unsubscribe();  // ← Prevents memory leaks
}
```

**Why important?**
- Prevents memory leaks
- Removes event listeners when component is destroyed
- Angular best practice

---

## 📚 Related Files

**New:**
- `frontend/src/app/services/stream-refresh.service.ts`

**Modified:**
- `frontend/src/app/components/stream-viewer/stream-viewer.component.ts`
- `frontend/src/app/components/dlq-actions/dlq-actions.component.ts`

---

## 🚀 Future Enhancements

### **Potential Use Cases:**

1. **Refresh on Generate Messages**
   - Trigger refresh after generating messages
   - Ensures all viewers show new messages

2. **Manual Refresh Button**
   - Add a refresh button to each stream viewer
   - Calls `refreshService.triggerRefresh()`

3. **Periodic Auto-Refresh**
   - Add optional auto-refresh every N seconds
   - Useful for monitoring scenarios

4. **Selective Refresh**
   - Refresh only specific streams
   - Modify service to accept stream name parameter

---

## ✅ Summary

✅ **New service:** `StreamRefreshService` for coordinating refreshes  
✅ **Automatic refresh:** Stream viewers reload after "Clear All Streams"  
✅ **No manual refresh:** No F5 needed  
✅ **Decoupled architecture:** Service-based communication  
✅ **Memory safe:** Proper subscription cleanup  
✅ **Scalable:** Works with any number of viewers  

**The feature is ready to use! Restart the frontend and test it out.** 🚀

