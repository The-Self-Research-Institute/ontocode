# OntoCode Testing Guide

## Recent Fixes Applied

### 1. File Upload Size Limit (Now Supports 1GB Files)

**What was fixed:**
- Increased file upload limits from 10MB to 1GB across all services
- Updated `ontology-editor`, `ontology-gateway`, and `ontology-swrl` services
- Added proper Spring Boot multipart configuration

**Files modified:**
- `ontology-editor/src/main/resources/application.properties`
- `ontology-gateway/src/main/resources/application.properties`
- `ontology-swrl/src/main/resources/application.properties`

**Configuration added:**
```properties
spring.servlet.multipart.max-file-size=1GB
spring.servlet.multipart.max-request-size=1GB
spring.servlet.multipart.enabled=true
```

**How to test:**
1. **Restart the backend services** (IMPORTANT - configuration changes require restart):
   ```powershell
   # Stop services if running
   cd e:\oct22\ontocode\scripts
   .\stop-services.bat
   
   # Start services
   .\start-services.bat
   ```

2. **Test file upload:**
   - Open VS Code with OntoCode extension
   - Try uploading a large OWL file (100MB+, up to 1GB)
   - File → Open → Select your large .owl file
   - Extension should upload without "File too large" errors

3. **Expected behavior:**
   - Files up to 1GB should upload successfully
   - You'll see loading dialog during processing
   - Once processed, ontology will load in the editor

---

### 2. Class Creation Fixed

**What was verified:**
- `handleCreateClass` function works correctly
- `AddClassDialog` component properly captures user input
- Backend `OntologyMutationService.createClass` endpoint is functional
- SPARQL PREFIX declarations are present (fixed in previous session)

**The class creation flow:**
1. User selects a class in the hierarchy
2. Clicks "Add Subclass" (+) or "Add Sibling" (⚃) button
3. `AddClassDialog` opens with:
   - Name input field
   - Auto-generated IRI preview
   - Cancel/Create buttons
4. User enters class name and presses Enter or clicks Create
5. Frontend calls `ontologyMutationService.createClass(projectId, newIri, name, parentIri)`
6. Backend executes SPARQL INSERT with proper prefixes
7. Local state updates to show new class immediately
8. Success notification appears

**How to test class creation:**

1. **Start the services** (if not already running):
   ```powershell
   cd e:\oct22\ontocode\scripts
   .\start-services.bat
   ```

2. **Open an ontology:**
   - In VS Code, open any .owl file
   - Wait for it to load in OntoCode editor

3. **Test Add Subclass:**
   - Click on any class in the Classes hierarchy (e.g., "BloodPressure")
   - Click the **+** (Add Subclass) button in the toolbar
   - Dialog opens with "Create New Subclass" title
   - Enter name: `TestSubclass`
   - Press Enter or click "Create"
   - **Expected:** New class appears as child of selected class
   - **Expected:** Notification: "Class 'TestSubclass' created successfully!"

4. **Test Add Sibling:**
   - Click on any class (not owl:Thing)
   - Click the **⚃** (Add Sibling) button
   - Dialog opens with "Create New Sibling Class" title
   - Enter name: `TestSibling`
   - Press Enter or click "Create"
   - **Expected:** New class appears at same level as selected class
   - **Expected:** Success notification

5. **Verify persistence:**
   - Reload the ontology (File → Select same file)
   - **Expected:** Your new classes are still there
   - **Expected:** Classes persist in TDB2 triplestore

---

## Common Issues and Solutions

### Issue: "File too large" error when uploading
**Solution:** Make sure you've restarted the backend services after the configuration changes.

```powershell
cd e:\oct22\ontocode\scripts
.\stop-services.bat
.\start-services.bat
```

### Issue: Class creation dialog doesn't open
**Solution:** 
1. Make sure you've rebuilt the frontend:
   ```powershell
   cd e:\oct22\ontocode\ontology-vscode-extension\webview-src
   npm run build
   ```
2. Reload VS Code window: Press `Ctrl+Shift+P` → "Developer: Reload Window"

### Issue: "Unresolved prefixed name: owl:Class" error
**Solution:** This was fixed in previous session. Make sure backend is compiled:
```powershell
cd e:\oct22\ontocode
mvnd clean compile
```

### Issue: No notification appears after creating class
**Solution:** Notifications use VS Code's message system. Check:
1. VS Code notification area (bottom right)
2. Output panel → OntoCode (if extension has logging)
3. Browser console for any errors (Ctrl+Shift+I in webview)

---

## Verification Checklist

After restarting services, verify:

- [ ] Backend services running (editor:8083, gateway:8082, auth:8086)
- [ ] Can upload files over 10MB
- [ ] Can upload files up to 1GB
- [ ] Add Subclass button opens dialog
- [ ] Add Sibling button opens dialog
- [ ] Class name input accepts text
- [ ] Enter key triggers creation
- [ ] Create button works
- [ ] New classes appear in hierarchy
- [ ] Notifications appear in VS Code
- [ ] New classes persist after reload
- [ ] No console errors

---

## Backend Service Status Check

```powershell
# Check if services are running
curl http://localhost:8082/actuator/health  # Gateway
curl http://localhost:8083/actuator/health  # Editor
curl http://localhost:8086/actuator/health  # Auth

# Check multipart configuration (should show 1GB limits)
# Look at startup logs for:
# spring.servlet.multipart.max-file-size=1GB
```

---

## Technical Details

### File Upload Configuration Chain:
1. **VS Code Extension** → Posts file to gateway
2. **Gateway (8082)** → Routes to editor service with 1GB limit
3. **Editor (8083)** → Receives file with 1GB limit
4. **StorageManager** → Saves to `data/projects/{projectId}/ontology.original.owl`
5. **ProjectImportService** → Processes async into TDB2

### Class Creation Flow:
1. **Dashboard.tsx** → `handleAddItem()` opens `AddClassDialog`
2. **AddClassDialog** → User enters name, clicks Create
3. **Dashboard.tsx** → `handleCreateClass()` called
4. **ontologyMutationService.ts** → `createClass()` sends mutation
5. **OntologyCrudController.java** → Receives mutation request
6. **OntologyMutationService.java** → Generates SPARQL INSERT with prefixes
7. **Tdb2DatasetService.java** → Executes against TDB2
8. **Dashboard.tsx** → Updates local state, shows notification

---

## Next Steps

1. **Restart backend services** to apply 1GB upload limit
2. **Test with a large ontology file** (e.g., SNOMED CT, UMLS, FoodOn)
3. **Create several test classes** to verify persistence
4. **Check logs** if any errors occur:
   - Backend: `ontology-editor/logs/owl-editor.log`
   - Frontend: VS Code Developer Tools console

---

## Support

If issues persist:
1. Check backend logs: `ontology-editor/logs/owl-editor.log`
2. Check MongoDB connection: `mongodb://localhost:27017`
3. Check TDB2 data: `ontology-editor/data/tdb2/{projectId}`
4. Verify Neo4j if used: `bolt://localhost:7687`
5. Check gateway routing: `http://localhost:8082/actuator/gateway/routes`
