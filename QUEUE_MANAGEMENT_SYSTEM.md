# Import Queue Management System

## Problem Statement

Currently, the OntoCode system experiences long-running imports (14+ minutes) that block other users from uploading files. When GraphDB is processing a large ontology file, subsequent uploads must wait without any visibility into:

- Their position in the queue
- Estimated wait time
- When processing will start
- How many files are ahead of them

## Solution: Import Queue Management

### Features

1. **FIFO Queue System** - Fair first-in-first-out processing
2. **Queue Position Tracking** - Users see their position (#1, #2, #3, etc.)
3. **Estimated Wait Time** - Based on average processing time
4. **Real-time Notifications** - WebSocket updates about queue status
5. **Queue Dashboard** - View all pending imports
6. **Processing Limit** - Only 1 concurrent import to avoid GraphDB conflicts

### Architecture

```
┌─────────────┐
│   Upload    │
└──────┬──────┘
       │
       ▼
┌─────────────────────────┐
│  ImportQueueManager     │
│  - enqueue()            │
│  - dequeue()            │
│  - getStatus()          │
│  - notifyQueuePosition()│
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│  Queue: LinkedList      │
│  [Item1, Item2, Item3]  │
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│  ProjectImportService   │
│  - runImport()          │
│  - markCompleted()      │
└─────────────────────────┘
```

### Data Models

#### ImportQueueItem
```java
class ImportQueueItem {
    String projectId;
    String filename;
    String ownerEmail;
    Path owlFile;
    Instant queuedAt;
    Instant startedAt;
    ImportStatus status;  // QUEUED, PROCESSING, COMPLETED, FAILED
    int queuePosition;
    long estimatedDurationMs;
}
```

#### QueueStatusMessage (WebSocket)
```java
class QueueStatusMessage {
    String projectId;
    int queuePosition;        // Your position (1, 2, 3...)
    int totalInQueue;         // Total files waiting
    long estimatedWaitTimeMs; // How long to wait
    String status;            // QUEUED, PROCESSING
    String message;           // "You are #2 in queue, estimated wait: 5 minutes"
    QueueStats queueStats;    // Overall queue statistics
}
```

### Implementation Status

✅ **Created:**
- `ImportQueueItem.java` - Queue item data model
- `QueueStatusMessage.java` - WebSocket message format
- `ImportQueueManager.java` - Queue management service

⏳ **TODO:**
1. Update `ProjectImportService.java` to use queue
2. Add REST API endpoints for queue status
3. Create WebSocket subscriptions for queue updates
4. Update frontend to display queue status
5. Add queue management UI component

### API Endpoints

#### Get Queue Status
```http
GET /api/ontology/queue/status/{projectId}

Response:
{
  "projectId": "go-plus",
  "queuePosition": 2,
  "totalInQueue": 5,
  "estimatedWaitTimeMs": 600000,
  "status": "QUEUED",
  "message": "You are #2 in queue (1 ahead, estimated wait: 10 minutes)"
}
```

#### Get Overall Queue Statistics
```http
GET /api/ontology/queue/stats

Response:
{
  "activeImports": 1,
  "queuedImports": 4,
  "averageProcessingTimeMs": 300000,
  "queue": [
    {
      "projectId": "pizza",
      "filename": "pizza.owl",
      "position": 1,
      "estimatedWaitTimeMs": 300000,
      "queuedSinceMs": 120000
    },
    {
      "projectId": "go-plus",
      "filename": "go-plus.owl",
      "position": 2,
      "estimatedWaitTimeMs": 600000,
      "queuedSinceMs": 60000
    }
  ]
}
```

### WebSocket Topics

#### Subscribe to Queue Updates
```javascript
// Subscribe to your project's queue status
stompClient.subscribe('/topic/queue/{projectId}', (message) => {
  const queueStatus = JSON.parse(message.body);
  console.log(`Position: ${queueStatus.queuePosition}`);
  console.log(`Wait time: ${queueStatus.estimatedWaitTimeMs}ms`);
});

// Subscribe to overall queue stats
stompClient.subscribe('/topic/queue/stats', (message) => {
  const stats = JSON.parse(message.body);
  console.log(`Active: ${stats.queueStats.activeImports}`);
  console.log(`Queued: ${stats.queueStats.queuedImports}`);
});
```

### Frontend UI Components

#### Queue Status Indicator
```typescript
interface QueueStatus {
  position: number;
  total: number;
  estimatedWaitMs: number;
  status: 'QUEUED' | 'PROCESSING';
  message: string;
}

// Display in UI:
// "🕐 Position #2 in queue (1 file ahead)
//  Estimated wait: 10 minutes"
```

#### Processing Flow with Queue

1. **User uploads file**
   ```
   → File uploaded
   → Added to queue at position #3
   → WebSocket notification: "You are #3 in queue"
   ```

2. **Queue progresses**
   ```
   → File #1 completes
   → Your position updates to #2
   → Notification: "You are #2 in queue, estimated wait: 5 minutes"
   ```

3. **Processing starts**
   ```
   → Your turn!
   → Notification: "Processing started (waited 8 minutes in queue)"
   → Progress updates: 10%, 20%, ... 100%
   ```

4. **Completion**
   ```
   → Processing complete
   → LoadingChoiceDialog closes
   → Data loaded and ready
   ```

### Configuration

#### Max Concurrent Imports
```java
private static final int MAX_CONCURRENT_IMPORTS = 1;
```

**Why 1?** GraphDB Community Edition has limitations:
- Single repository access
- Memory constraints
- No transaction isolation

**For Enterprise:** Can increase to 2-3 concurrent imports

#### Estimated Processing Time
```java
private static final long DEFAULT_ESTIMATED_DURATION_MS = 5 * 60 * 1000; // 5 minutes
```

Updates dynamically based on last 10 completed imports.

### Benefits

1. **User Visibility** - Know exactly where you are in queue
2. **Fair Processing** - First-come, first-served
3. **No Timeouts** - Files wait in queue instead of failing
4. **Better Planning** - Users can see wait times and plan accordingly
5. **System Stability** - Controlled concurrent processing prevents GraphDB overload

### Monitoring & Logs

#### Queue Logs
```
[Queue] Added project go-plus to queue at position 3 (total in queue: 3)
[Queue] Started processing project pizza (waited 120000 ms, queue size now: 2)
[Queue] Completed project pizza in 300000 ms (avg: 280000 ms)
```

#### GraphDB Monitor
The 14-minute query you see is a normal import. With the queue:
- Other uploads don't start new queries
- They wait their turn
- Users are informed

### Next Steps

1. **Phase 1: Backend Queue** ✅ DONE
   - ImportQueueManager service created
   - Data models defined

2. **Phase 2: Integration** ⏳ TODO
   - Update ProjectImportService
   - Add queue status to ProjectLoadController
   - WebSocket configuration

3. **Phase 3: Frontend** ⏳ TODO
   - QueueStatusIndicator component
   - Update LoadingChoiceDialog with queue info
   - Global queue stats display

4. **Phase 4: Testing**
   - Upload multiple large files
   - Verify queue ordering
   - Test notifications

### Example User Experience

**Before (Current):**
```
User: *uploads go-plus.owl*
System: "Uploading..."
System: "Processing..."
[LoadingChoiceDialog shows, but closes unexpectedly]
User: "Is it working? How long will this take?"
[14 minutes pass...]
System: "Complete!"
```

**After (With Queue):**
```
User: *uploads go-plus.owl*
System: "File uploaded successfully"
System: "🕐 Position #2 in queue"
System: "1 file ahead of you (pizza.owl is processing)"
System: "Estimated wait: 10 minutes"

[5 minutes later]
System: "🕐 Position #1 in queue"
System: "Next to process! Estimated wait: 5 minutes"

[5 minutes later]
System: "✓ Processing started!"
System: "Importing... 10%"
...
System: "✓ Import complete!"
```

### Code Locations

- **Queue Manager**: `ImportQueueManager.java` ✅ Created
- **Queue Item Model**: `ImportQueueItem.java` ✅ Created
- **WebSocket Messages**: `QueueStatusMessage.java` ✅ Created
- **Import Service**: `ProjectImportService.java` ⏳ Needs update
- **Controller**: `ProjectLoadController.java` ⏳ Needs queue status endpoint
- **Frontend**: Dashboard.tsx ⏳ Needs queue UI component

All foundation code has been created. Integration and UI work remains.
