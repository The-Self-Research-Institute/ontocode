# GraphDB to MongoDB Sync Implementation

## Overview
Implemented automatic synchronization of GraphDB history changes to MongoDB, enabling full collaboration features (approve, reject, comments, conflict resolution) on real-time ontology edits.

## Architecture

### Data Flow
1. **Real-time Changes** → GraphDB (primary storage)
2. **GraphDB** → MongoDB (automatic sync for collaboration)
3. **Collaboration Actions** → MongoDB (approve/reject/comments/conflicts)

### Components Created

#### 1. HistoryChange Entity
**Location**: `ontology-editor/src/main/java/.../model/HistoryChange.java`

MongoDB document model for synced GraphDB changes with collaboration fields:
- **Core Fields**: projectId, editId, userId, username, timestamp
- **Change Data**: operationType, entityType, entityIRI, entityLabel, oldValue, newValue
- **Collaboration**: status (PENDING/APPROVED/REJECTED), approvedBy, rejectedBy
- **Comments**: Map<String, CommentEntry> for threaded discussions
- **Conflicts**: hasConflict, conflictResolution, resolvedBy
- **Tracking**: syncedAt timestamp for deduplication

#### 2. HistoryChangeRepository
**Location**: `ontology-editor/src/main/java/.../repository/HistoryChangeRepository.java`

MongoDB repository interface with query methods:
- `findByProjectIdOrderByTimestampDesc` - All changes for project
- `findByProjectIdAndStatusOrderByTimestampDesc` - Filter by status
- `findByProjectIdAndEditId` - Lookup by GraphDB edit ID
- `existsByProjectIdAndEditId` - Check if already synced
- `findByProjectIdAndHasConflictOrderByTimestampDesc` - Get conflicts
- `findByProjectIdAndUserIdOrderByTimestampDesc` - User's changes

#### 3. HistorySyncService
**Location**: `ontology-editor/src/main/java/.../service/HistorySyncService.java`

Core sync service with methods:
- `syncChange(projectId, editId, changeData)` - Sync single change with deduplication
- `syncRecentChanges(projectId, count)` - Batch sync for catch-up
- `approveChange(changeId, userId, username)` - Mark change approved
- `rejectChange(changeId, userId, username)` - Mark change rejected
- `addComment(changeId, userId, username, text)` - Add comment to change
- `resolveConflict(changeId, userId, username, resolution)` - Resolve conflict
- `getHistoryChanges(projectId)` - Get all synced changes
- `getHistoryChangesByStatus(projectId, status)` - Filter by status
- `getConflicts(projectId)` - Get changes with conflicts

#### 4. GraphDBHistoryService Enhancement
**Location**: `ontology-editor/src/main/java/.../service/GraphDBHistoryService.java`

Modified `recordEdit()` to automatically sync changes:
```java
// After recording to GraphDB, sync to MongoDB
historySyncService.syncChange(projectId, editId, changeData);
```

Prevents circular dependency with `@Lazy` injection:
```java
@Autowired
@Lazy
private HistorySyncService historySyncService;
```

#### 5. ChangeTrackingController Updates
**Location**: `ontology-editor/src/main/java/.../controller/ChangeTrackingController.java`

Updated endpoints to use HistorySyncService:
- `POST /{projectId}/changes/{changeId}/approve` - Uses `historySyncService.approveChange()`
- `POST /{projectId}/changes/{changeId}/reject` - Uses `historySyncService.rejectChange()`
- `POST /{projectId}/changes/{changeId}/comments` - Uses `historySyncService.addComment()`
- `POST /{projectId}/changes/{changeId}/resolve-conflict` - Uses `historySyncService.resolveConflict()`
- `GET /{projectId}/changes/recent` - Triggers sync on load with `historySyncService.syncRecentChanges()`
- `GET /{projectId}/changes/synced` - New endpoint to get MongoDB changes with collaboration data

## Sync Mechanism

### Automatic Sync
Every time a change is recorded to GraphDB via `GraphDBHistoryService.recordEdit()`:
1. Change is saved as RDF triples in GraphDB
2. Change data is converted to Map<String, Object>
3. `historySyncService.syncChange()` is called automatically
4. MongoDB document is created in `history_changes` collection
5. Deduplication check prevents duplicate syncs

### Manual Sync
When plugin loads changes via `GET /api/ontology/{projectId}/changes/recent`:
1. GraphDB changes are retrieved
2. `historySyncService.syncRecentChanges()` is called
3. Any GraphDB changes not yet in MongoDB are synced
4. Ensures no missed changes after downtime

### Deduplication
Uses `editId` (GraphDB edit IRI) as unique identifier:
- `existsByProjectIdAndEditId()` checks before creating
- Prevents duplicate MongoDB documents
- Safe for multiple sync calls

## API Endpoints

### GET /api/ontology/{projectId}/changes/recent?count=100
Returns GraphDB changes and triggers sync to MongoDB.

**Response**:
```json
{
  "success": true,
  "projectId": "proj123",
  "changeCount": 10,
  "changes": [
    {
      "id": "http://ontology.research/history#edit/uuid",
      "userId": "user1",
      "username": "John Doe",
      "timestamp": 1733234567890,
      "changeType": "ANNOTATION_ADDED",
      "changeCategory": "ANNOTATION",
      "entityIRI": "http://example.org/Person",
      "entityLabel": "Person",
      "oldValue": null,
      "newValue": "A human being",
      "description": "Added annotation",
      "reverted": false
    }
  ]
}
```

### GET /api/ontology/{projectId}/changes/synced?status=PENDING
Returns MongoDB synced changes with collaboration features.

**Response**:
```json
{
  "success": true,
  "projectId": "proj123",
  "changeCount": 5,
  "changes": [
    {
      "id": "mongoId123",
      "projectId": "proj123",
      "editId": "http://ontology.research/history#edit/uuid",
      "userId": "user1",
      "username": "John Doe",
      "timestamp": "2025-12-03T12:00:00",
      "operationType": "ANNOTATION_ADDED",
      "entityType": "ANNOTATION",
      "entityIRI": "http://example.org/Person",
      "status": "PENDING",
      "comments": {
        "comment1": {
          "userId": "user2",
          "username": "Jane Smith",
          "text": "Looks good!",
          "timestamp": "2025-12-03T12:05:00"
        }
      },
      "hasConflict": false
    }
  ]
}
```

### POST /api/ontology/{projectId}/changes/{changeId}/approve
Approves a synced change.

**Request Body**:
```json
{
  "userId": "user1",
  "username": "John Doe"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Change approved",
  "changeId": "mongoId123"
}
```

### POST /api/ontology/{projectId}/changes/{changeId}/reject
Rejects a synced change.

**Request Body**:
```json
{
  "userId": "user1",
  "username": "John Doe"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Change rejected",
  "changeId": "mongoId123"
}
```

### POST /api/ontology/{projectId}/changes/{changeId}/comments
Adds a comment to a change.

**Request Body**:
```json
{
  "userId": "user1",
  "username": "John Doe",
  "text": "This looks good!"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Comment added",
  "changeId": "mongoId123",
  "comment": "This looks good!"
}
```

### POST /api/ontology/{projectId}/changes/{changeId}/resolve-conflict
Resolves a conflict on a change.

**Request Body**:
```json
{
  "userId": "user1",
  "username": "John Doe",
  "resolution": "Accepted version from user2"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Conflict resolved",
  "changeId": "mongoId123",
  "resolution": "Accepted version from user2"
}
```

## Database Collections

### MongoDB: `history_changes`
Synced changes with collaboration features.

**Indexes**:
- `projectId` - For project-scoped queries
- `editId` - For deduplication checks
- `userId` - For user-scoped queries
- `timestamp` - For chronological ordering

**Document Structure**:
```javascript
{
  _id: "mongoId",
  projectId: "proj123",
  editId: "http://ontology.research/history#edit/uuid",
  userId: "user1",
  username: "John Doe",
  timestamp: ISODate("2025-12-03T12:00:00"),
  operationType: "ANNOTATION_ADDED",
  entityType: "ANNOTATION",
  entityIRI: "http://example.org/Person",
  entityLabel: "Person",
  oldValue: null,
  newValue: "A human being",
  description: "Added annotation",
  status: "PENDING",
  approvedBy: null,
  approvedAt: null,
  rejectedBy: null,
  rejectedAt: null,
  comments: {
    "commentId1": {
      userId: "user2",
      username: "Jane Smith",
      text: "Looks good!",
      timestamp: ISODate("2025-12-03T12:05:00")
    }
  },
  hasConflict: false,
  conflictResolution: null,
  resolvedBy: null,
  resolvedAt: null,
  metadata: {},
  syncedAt: ISODate("2025-12-03T12:00:01")
}
```

### GraphDB: Named Graph `http://ontology.research/history#graph/{projectId}`
Primary storage for edit history as RDF triples.

**RDF Structure**:
```turtle
@prefix hist: <http://ontology.research/history#> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

hist:edit/uuid123 rdf:type hist:EditOperation ;
    hist:hasUserId "user1" ;
    hist:hasUsername "John Doe" ;
    hist:hasTimestamp 1733234567890 ;
    hist:hasOperationType "ANNOTATION_ADDED" ;
    hist:hasEntityIRI "http://example.org/Person" ;
    hist:hasEntityLabel "Person" ;
    hist:hasNewValue "A human being" ;
    hist:hasDescription "Added annotation" .
```

## Plugin Integration

### Change Assistant Plugin
**Location**: `plugins/change-assistant-plugin/src/ChangeAssistant.tsx`

The plugin uses the `/api/ontology/${projectId}/changes/recent` endpoint:
- Loads GraphDB changes on initial render
- Automatically triggers MongoDB sync
- Displays changes in timeline and graph views
- Enables approve/reject/comment actions

**Change ID Mapping**:
- Plugin receives GraphDB `id` (edit IRI) from `/changes/recent`
- For collaboration actions, plugin should use MongoDB `id` from `/changes/synced`
- Both endpoints work in sync

### Recommended Update
To use MongoDB IDs for collaboration:
```typescript
// Load synced changes with collaboration data
const response = await fetch(
  `/api/ontology/${projectId}/changes/synced`,
  { headers: { 'Authorization': `Bearer ${apiKey}` } }
);
```

This provides direct access to status, comments, and conflict data.

## Testing

### 1. Make an Ontology Edit
Edit an annotation in the VS Code extension.

**Expected**:
- GraphDB records edit as RDF triples
- MongoDB receives synced document in `history_changes`
- Change appears in Change Assistant plugin

### 2. Approve a Change
Click "Approve" in Change Assistant plugin.

**Expected**:
- MongoDB document updated: `status: "APPROVED"`, `approvedBy`, `approvedAt`
- Change status reflects in UI

### 3. Add a Comment
Add comment to a change.

**Expected**:
- MongoDB document updated: new entry in `comments` map
- Comment appears in plugin UI

### 4. Check Sync After Restart
1. Restart backend
2. Load Change Assistant plugin

**Expected**:
- `/changes/recent` triggers sync
- All GraphDB changes present in MongoDB
- No duplicates created

## Benefits

✅ **Single Source of Truth**: GraphDB stores authoritative history
✅ **Rich Collaboration**: MongoDB enables approve/reject/comments
✅ **Automatic Sync**: No manual intervention needed
✅ **Deduplication**: Safe to sync multiple times
✅ **Catch-up Mechanism**: Syncs missed changes on load
✅ **Performance**: MongoDB indexed queries for fast collaboration lookups
✅ **Backwards Compatible**: Existing GraphDB history preserved

## Future Enhancements

### 1. Conflict Detection
Automatically detect conflicting edits:
```java
public void detectConflicts(String projectId) {
    // Compare concurrent edits to same entity
    // Set hasConflict = true on affected changes
}
```

### 2. Change Notifications
WebSocket notifications when changes are approved/rejected:
```java
@Autowired
private SimpMessagingTemplate messagingTemplate;

public void notifyApproval(String projectId, String changeId) {
    messagingTemplate.convertAndSend(
        "/topic/project/" + projectId + "/changes",
        Map.of("type", "APPROVED", "changeId", changeId)
    );
}
```

### 3. Batch Operations
Approve/reject multiple changes at once:
```java
public boolean approveBatch(List<String> changeIds, String userId, String username) {
    // Update all changes in single MongoDB operation
}
```

### 4. Sync Status Monitoring
Admin endpoint to check sync health:
```java
@GetMapping("/admin/sync-status")
public Map<String, Object> getSyncStatus() {
    // Return sync lag, failed syncs, etc.
}
```

## Troubleshooting

### Changes not syncing
1. Check backend logs for sync errors
2. Verify MongoDB connection
3. Manually trigger: `GET /api/ontology/{projectId}/changes/recent`
4. Check `history_changes` collection in MongoDB

### Duplicate changes in MongoDB
1. Should be prevented by `editId` deduplication
2. If duplicates exist, run cleanup:
```javascript
db.history_changes.aggregate([
  { $group: { _id: "$editId", count: { $sum: 1 }, docs: { $push: "$_id" } } },
  { $match: { count: { $gt: 1 } } }
]).forEach(doc => {
  doc.docs.shift(); // Keep first
  db.history_changes.deleteMany({ _id: { $in: doc.docs } });
});
```

### Collaboration actions not working
1. Verify change exists in MongoDB: `db.history_changes.findOne({ _id: "changeId" })`
2. Check HistorySyncService is autowired
3. Verify endpoint receives correct changeId (MongoDB ID, not GraphDB editId)
4. Check backend logs for service errors

## Summary

This implementation bridges GraphDB (authoritative history storage) with MongoDB (collaboration features) automatically. Every ontology edit recorded in GraphDB is instantly available in MongoDB for approval workflows, comments, and conflict resolution - providing the best of both worlds for the Change Assistant plugin.
