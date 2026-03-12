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

### 3. Report Issue to Jira

**What was fixed:**
- Fixed CORS policy blocking issue report submissions
- Removed `credentials: 'include'` from fetch calls to match server's `allowCredentials=false` configuration
- Fixed JWT authentication header being blocked by CORS preflight
- Made `validateJiraConnection` use dynamic URLs for cloud deployment
- Updated issue report service to work with both self-hosted and cloud deployments

**Files modified:**
- `ontology-vscode-extension/webview-src/components/ReportIssueModal.tsx` - Removed credentials mode
- `ontology-vscode-extension/src/services/issueReportService.ts` - Dynamic URL support
- `ontology-vscode-extension/src/extension.ts` - Initialize issue service with correct URL
- `ontology-gateway/src/main/java/.../GatewayCorsConfig.java` - Fixed CORS credentials
- `ontology-editor/src/main/java/.../IssueReportController.java` - Fixed CORS annotation

**How to test issue reporting:**

#### Prerequisites:
1. **Backend must be configured with Jira credentials** in `ontology-editor/src/main/resources/application.properties`:
   ```properties
   jira.url=https://<your-domain>.atlassian.net
   jira.email=<your-email>
   jira.api.token=<your-api-token>
   jira.project.key=<project-key>
   ```

2. **For cloud deployment (EC2):** Services must be running at your cloud gateway URL (e.g., `http://13.218.153.101`)

3. **User must be logged in** with valid JWT token

#### Test Case 1: Report Issue from VS Code Web Interface (Cloud Deployment)

**Steps:**
1. **Open VS Code web interface:**
   - Navigate to `http://13.218.153.101:3000` (or your cloud URL)
   - Login with your credentials

2. **Clear browser cache** (IMPORTANT - ensures latest code is loaded):
   - Press `F12` to open DevTools
   - Go to **Application** tab → **Storage** section
   - Click **"Clear site data"** button
   - Perform hard refresh: `Ctrl+Shift+R` (Windows) or `Cmd+Shift+R` (Mac)
   - Verify new JavaScript bundle loaded (check Network tab for new file hash)

3. **Open issue report modal:**
   - In the top menu, click **"Help"** → **"Report Issue"**
   - Or use command palette: `Ctrl+Shift+P` → "OntoCode: Report Issue"

4. **Verify modal opens with pre-filled system info:**
   - Modal title: "Report Issue to Jira"
   - Bug icon visible in header
   - System info automatically collected:
     - OS Name (e.g., win32, darwin, linux)
     - OS Version
     - VS Code Version
     - Extension Version
   - Project context (if ontology file is open):
     - Project Name
     - Ontology File Path

5. **Fill in required fields:**
   - **Title** (required): Enter "Test Issue - Authentication Flow"
   - **Description** (required): Enter detailed description:
     ```
     Testing issue reporting functionality after CORS fixes.
     Verifying that JWT authentication works correctly.
     ```
   - **Priority**: Select "Medium"
   - **Issue Type**: Select "Bug"
   - **Steps to Reproduce** (optional): Enter step-by-step reproduction
   - **User Email** (optional): Enter contact email

6. **Add attachments** (optional):
   - Click "Choose File" or drag-and-drop files
   - Add screenshots (PNG, JPG)
   - Add log files (TXT, LOG)
   - Add ontology files (OWL, RDF, TTL)
   - **Verify:** File icons appear with correct colors (green for images, yellow for text, etc.)
   - **Verify:** File size displayed correctly
   - **Verify:** Remove button (X) works for each attachment

7. **Monitor network request** (DevTools):
   - Open **Network** tab in DevTools
   - Keep it open during submission

8. **Submit the issue:**
   - Click **"Submit Issue"** button
   - **Verify loading state:** Button shows "Submitting..." with disabled state

9. **Verify network request details:**
   - Request URL: `http://13.218.153.101/api/v1/issues/report`
   - Method: `POST`
   - **Critical checks:**
     - ✅ Request Headers include: `Authorization: Bearer <token>`
     - ✅ Request does NOT have `Cookie` header
     - ✅ Preflight OPTIONS request succeeds (200 status)
     - ✅ Preflight response includes: `Access-Control-Allow-Headers: *`
     - ✅ Main POST request succeeds (200 status)
     - ✅ Response headers include: `Access-Control-Allow-Credentials: false`

10. **Verify success response:**
    - **Expected:** Green success notification appears
    - **Expected:** Message: "Issue reported successfully!"
    - **Expected:** Jira issue URL displayed (clickable link)
    - **Expected:** Modal auto-closes after 3 seconds

11. **Verify Jira issue created:**
    - Click the Jira URL link (should open in new tab)
    - **Verify issue contains:**
      - Title matches your input
      - Description includes your text
      - System info section with OS, VS Code version, etc.
      - Attachments uploaded correctly
      - Error logs section (if any errors were logged)
      - Project context (if ontology was open)

#### Test Case 2: Report Issue from Desktop Extension (Self-Hosted)

**Steps:**
1. **Open VS Code desktop application**
2. **Open workspace with OntoCode extension installed**
3. **Start self-hosted services:**
   ```powershell
   cd c:\Users\Jeeva\Desktop\ontology\ontocode
   docker-compose up -d
   ```

4. **Set deployment type to self-hosted:**
   - Open Command Palette: `Ctrl+Shift+P`
   - Run: "OntoCode: Change Deployment Type"
   - Select: "self-hosted"

5. **Follow steps 3-11 from Test Case 1**, but:
   - Request URL should be: `http://localhost:8083/api/v1/issues/report`
   - No browser cache clearing needed (desktop extension reloads automatically)

#### Test Case 3: Error Scenarios

**3.1 Test Unauthorized (No JWT Token):**
1. Logout from OntoCode
2. Try to open "Report Issue" modal
3. **Expected:** Modal should not open OR show login required message
4. **Expected:** No request sent to backend without auth token

**3.2 Test Missing Required Fields:**
1. Open issue report modal
2. Leave "Title" field empty
3. Click "Submit Issue"
4. **Expected:** Validation error highlighting title field
5. **Expected:** Tooltip: "Title is required"

**3.3 Test Backend Unreachable:**
1. Stop the editor service: `docker-compose stop owl-editor`
2. Open issue report modal and fill fields
3. Submit the issue
4. **Expected:** Error notification: "Network error: Failed to submit issue report"
5. **Expected:** Modal remains open (doesn't auto-close)
6. **Expected:** Submit button re-enabled
7. Restart service: `docker-compose start owl-editor`

**3.4 Test Jira Configuration Invalid:**
1. Edit `ontology-editor/src/main/resources/application.properties`
2. Set invalid Jira credentials (wrong API token)
3. Restart editor service
4. Submit an issue
5. **Expected:** Error notification from backend: "Jira authentication failed"
6. **Expected:** Response status: 401 or 500

**3.5 Test Large Attachments:**
1. Open issue report modal
2. Try to attach a file larger than 1GB
3. **Expected:** Browser may warn or fail to select
4. **Expected:** If uploaded, backend should reject with "File too large" error

#### Verification Checklist:

- [ ] Issue report modal opens without errors
- [ ] System information auto-collected correctly
- [ ] Project context captured when ontology file is open
- [ ] All form fields editable and validation works
- [ ] File attachments can be added/removed
- [ ] File icons display correctly based on type
- [ ] Submit button shows loading state during submission
- [ ] JWT token sent in Authorization header (check DevTools)
- [ ] No CORS errors in console
- [ ] No credentials mode in fetch request
- [ ] Preflight OPTIONS request succeeds
- [ ] Main POST request succeeds
- [ ] Success notification displays with Jira URL
- [ ] Modal auto-closes after success
- [ ] Jira issue created with all details
- [ ] Attachments uploaded to Jira
- [ ] Works on both cloud and self-hosted deployments
- [ ] Error handling works for all error scenarios

#### Backend Logs to Check:

**In `ontology-auth` service logs:**
```
[OntoCode] ✓ JWT Token validated successfully
[JwtAuthenticationFilter] JWT validation SUCCESS
```

**In `ontology-editor` service logs:**
```
[IssueReportController] Received issue report request
[IssueReportController] Jira issue created: <issue-key>
```

**In `ontology-gateway` service logs:**
```
[CORS] Processing preflight OPTIONS request
[CORS] CORS headers added: Access-Control-Allow-Credentials=false
```

#### Common CORS Issues:

**Issue: "Access-Control-Allow-Credentials must be 'true' when credentials mode is 'include'"**
- **Cause:** Old JavaScript bundle cached in browser
- **Solution:** Clear browser cache completely:
  1. F12 → Application → Clear site data
  2. Hard refresh: Ctrl+Shift+R
  3. Verify new bundle hash in Network tab
  4. If still cached, try incognito mode

**Issue: "Authorization header not allowed by Access-Control-Allow-Headers"**
- **Cause:** Gateway CORS filter not allowing Authorization header
- **Solution:** Verify `GatewayCorsConfig.java` has:
  ```java
  headers.add("Access-Control-Allow-Headers", "*");
  ```

**Issue: "Request has no credentials mode but server expects cookies"**
- **Cause:** Mismatch between frontend and backend authentication
- **Solution:** OntoCode uses JWT in Authorization header, NOT cookies
  - Frontend should NOT include `credentials: 'include'`
  - Backend should set `allowCredentials: false`

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
