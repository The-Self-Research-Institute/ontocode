# WebSocket Import Notifications - Implementation Guide

## Overview

Replace polling-based import status checking with real-time WebSocket notifications for better performance and user experience.

---

## ✅ Backend Changes (COMPLETED)

### 1. Created ImportStatusMessage Model
**File:** `ontology-editor/src/main/java/self/research/ontology/owlEditor/model/collaboration/ImportStatusMessage.java`

```java
@Data
@Builder
public class ImportStatusMessage {
    private ImportStatusType type;  // IMPORT_STARTED, IMPORT_PROGRESS, IMPORT_COMPLETED, IMPORT_FAILED
    private String projectId;
    private String status;  // PROCESSING, COMPLETED, ERROR
    private String statusMessage;
    private String filename;
    private Integer progress;
    private long timestamp;
    private Object metadata;
}
```

### 2. Updated ProjectImportService
**File:** `ontology-editor/src/main/java/self/research/ontology/owlEditor/service/ProjectImportService.java`

Added:
- Dependency injection of `SimpMessagingTemplate`
- `sendImportNotification()` method
- Notifications at key points:
  - IMPORT_STARTED when import begins
  - IMPORT_COMPLETED when successful (with metadata)
  - IMPORT_FAILED on error

Messages sent to: `/topic/import/{projectId}`

---

## 🔧 Frontend Changes (TO BE COMPLETED)

### 1. Add Import Status Type to CollaborationManager

**File:** `ontology-vscode-extension/src/collaboration/types.ts`

Add interface:
```typescript
export interface ImportStatusMessage {
    type: 'IMPORT_STARTED' | 'IMPORT_PROGRESS' | 'IMPORT_COMPLETED' | 'IMPORT_FAILED';
    projectId: string;
    status: string;
    statusMessage: string;
    filename: string;
    progress?: number;
    timestamp: number;
    metadata?: any;
}
```

### 2. Update CollaborationManager

**File:** `ontology-vscode-extension/src/collaboration/CollaborationManager.ts`

#### A. Add callback property (after line 30):
```typescript
private onImportStatusUpdate?: (status: ImportStatusMessage) => void;
```

#### B. Add setter method (around line 40):
```typescript
setOnImportStatusUpdate(handler: (status: ImportStatusMessage) => void) {
    this.onImportStatusUpdate = handler;
}
```

#### C. Add subscription method (after `subscribeToLocks`):
```typescript
private subscribeToImportStatus(projectId: string): void {
    if (!this.client) return;

    const subscription = this.client.subscribe(
        `/topic/import/${projectId}`,
        (message: IMessage) => {
            try {
                const importStatus: ImportStatusMessage = JSON.parse(message.body);

                console.log('[CollaborationManager] Import status update:', importStatus);

                if (this.onImportStatusUpdate) {
                    this.onImportStatusUpdate(importStatus);
                }
            } catch (error) {
                console.error('Error parsing import status:', error);
            }
        }
    );

    this.subscriptions.set(`import-${projectId}`, subscription);
}
```

#### D. Call subscription in `joinProject` (after line 172):
```typescript
this.subscribeToImportStatus(projectId);
```

### 3. Update Extension.ts to Forward Messages

**File:** `ontology-vscode-extension/src/extension.ts`

In the `OntologyEditorPanel` class, add handler for import status:

```typescript
// In setupCollaborationHandlers() or similar
this.collaborationManager.setOnImportStatusUpdate((status) => {
    this.postMessage({
        type: 'importStatusUpdate',
        status: status
    });
});
```

### 4. Update Dashboard to Listen for Notifications

**File:** `ontology-vscode-extension/webview-src/components/Dashboard.tsx`

#### A. Add state for import status:
```typescript
const [importStatus, setImportStatus] = useState<string>('COMPLETED');
const [importMessage, setImportMessage] = useState<string>('');
```

#### B. Add useEffect to listen for WebSocket messages:
```typescript
useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
        const message = event.data;

        if (message.type === 'importStatusUpdate') {
            const status = message.status;
            console.log('[Dashboard] Import status update:', status);

            setImportStatus(status.status);
            setImportMessage(status.statusMessage);

            // If import completed, fetch data automatically
            if (status.type === 'IMPORT_COMPLETED' && status.projectId === projectId) {
                console.log('[Dashboard] Import completed, fetching data...');
                fetchData(status.projectId, false);
            }

            // If import failed, show error
            if (status.type === 'IMPORT_FAILED') {
                notificationService.error('Import Failed', status.statusMessage);
            }
        }
    };

    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
}, [projectId, fetchData]);
```

#### C. Replace `waitForProcessingComplete` with WebSocket-based approach:
```typescript
// REMOVE the polling loop from waitForProcessingComplete
// Instead, just check current status once:
const waitForProcessingComplete = useCallback(async (currentProjectId: string): Promise<boolean> => {
    try {
        const statusRes = await apiClient.get<any>(`/api/ontology/status/${currentProjectId}`);
        const status = statusRes?.data?.status || statusRes?.status;

        console.log(`[Dashboard] Initial status check: ${status}`);

        if (status === 'COMPLETED') {
            return true;
        }

        if (status === 'ERROR') {
            return false;
        }

        // If PROCESSING, wait for WebSocket notification (return true to continue)
        console.log('[Dashboard] Status is PROCESSING, waiting for WebSocket notification...');
        return new Promise((resolve) => {
            // Set up one-time listener for import completion
            const handler = (event: MessageEvent) => {
                const message = event.data;
                if (message.type === 'importStatusUpdate' &&
                    message.status.projectId === currentProjectId) {

                    if (message.status.type === 'IMPORT_COMPLETED') {
                        window.removeEventListener('message', handler);
                        resolve(true);
                    } else if (message.status.type === 'IMPORT_FAILED') {
                        window.removeEventListener('message', handler);
                        resolve(false);
                    }
                }
            };

            window.addEventListener('message', handler);

            // Timeout after 5 minutes
            setTimeout(() => {
                window.removeEventListener('message', handler);
                console.warn('[Dashboard] Timeout waiting for import completion');
                resolve(false);
            }, 300000);
        });

    } catch (error) {
        console.error('[Dashboard] Error checking status:', error);
        return false;
    }
}, []);
```

### 5. Update CollaborationContext (Optional Enhancement)

**File:** `ontology-vscode-extension/webview-src/contexts/CollaborationContext.tsx`

Add to message handler switch:
```typescript
case 'importStatusUpdate':
    handleImportStatusUpdate(message.status);
    break;
```

Add callback:
```typescript
const handleImportStatusUpdate = useCallback((status: any) => {
    console.log('[CollaborationContext] Import status:', status);
    // Could add to state if needed for displaying in UI
}, []);
```

---

## Benefits of WebSocket Approach

1. **No Polling** - Server pushes status immediately
2. **Real-time Updates** - Users see progress instantly
3. **Lower Server Load** - No repeated status checks
4. **Better UX** - Instant feedback when import completes
5. **Scalable** - Works for multiple concurrent imports

---

## Testing Steps

1. **Build Backend:**
   ```bash
   cd ontology-editor
   mvn clean package -DskipTests
   ```

2. **Build Frontend:**
   ```bash
   cd ontology-vscode-extension/webview-src
   npm run build
   cd ..
   npm run compile
   ```

3. **Restart Services:**
   - Stop backend (Ctrl+C)
   - Restart: `mvn spring-boot:run`
   - Reload VS Code extension (F5)

4. **Test Upload:**
   - Upload an OWL file
   - Watch console for WebSocket messages:
     - `[CollaborationManager] Import status update: IMPORT_STARTED`
     - `[CollaborationManager] Import status update: IMPORT_COMPLETED`
   - Dashboard should load automatically when complete

5. **Test Large File:**
   - Upload a large ontology (>50MB)
   - Should see progress without polling
   - No timeout errors

---

## Rollback Plan

If issues occur:
1. Keep the backend changes (already compiled)
2. Comment out WebSocket subscription code
3. Revert to polling approach
4. The system will work as before with 5-minute timeout

---

## Current Status

✅ Backend: COMPLETED & COMPILED
- ImportStatusMessage model created
- ProjectImportService updated with notifications
- WebSocket topic configured: `/topic/import/{projectId}`

🔧 Frontend: NEEDS IMPLEMENTATION
- Follow steps above to add WebSocket listening
- Replace polling with event-driven approach
- Test with sample uploads

---

## Next Steps

1. Implement frontend changes listed above
2. Test with small and large ontology files
3. Verify no polling occurs
4. Confirm instant updates when import completes
5. Update TROUBLESHOOTING.md if needed
