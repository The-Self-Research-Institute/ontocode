# Troubleshooting Guide

## Issue: Import Stuck at "PROCESSING" Status

### Symptoms:
- File upload completes but status remains "PROCESSING" for 5+ minutes
- Frontend shows: `[Dashboard] Project go-plus status (attempt 278/300): PROCESSING`
- Backend logs show: `Clearing dataset for project: go-plus` but never completes

### Root Cause:
The `clearDataset()` or `bulkLoad()` operation in GraphDB is hanging, likely due to:
1. Previous incomplete import left GraphDB in inconsistent state
2. GraphDB has too much data from failed imports
3. Network/connection issue between backend and GraphDB

---

## Solution 1: Restart Backend Service (Quickest)

This will kill the hung import thread:

```bash
# 1. Stop the backend (Ctrl+C in terminal where it's running)
# 2. Clear GraphDB manually (see below)
# 3. Restart backend
cd ontology-editor
mvn spring-boot:run
```

---

## Solution 2: Clear GraphDB Before Restart

### Option A: Using GraphDB Workbench (Recommended)

1. Open http://localhost:7200
2. Select "ontocode" repository
3. Go to **SPARQL** tab
4. Run:
   ```sparql
   DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }
   ```
5. Wait for completion (may take a minute)
6. Restart backend

### Option B: Drop and Recreate Repository (Fastest)

1. Open http://localhost:7200
2. Go to **Setup** → **Repositories**
3. Find "ontocode" repository
4. Click **Delete**
5. Click **Create new repository**
   - Repository ID: `ontocode`
   - Ruleset: `owl-horst-optimized` (or your preferred ruleset)
   - Click **Create**
6. Restart backend

### Option C: Use Cleanup Script

```cmd
cd scripts
clear-graphdb-simple.bat
```

---

## Solution 3: Clean MongoDB Collections

If imports keep failing, old metadata might be causing issues:

### Using MongoDB Compass:
1. Open MongoDB Compass
2. Connect to `mongodb://localhost:27017`
3. Navigate to `ontocode` database
4. Delete these collections:
   - `project_metadata`
   - `project_documents`
   - `draft_changes`
   - `ontology_changes`
5. Restart backend

### Using MongoDB Shell:
```bash
mongosh mongodb://localhost:27017/ontocode

# Then:
db.project_metadata.deleteMany({projectId: "go-plus"})
db.project_documents.deleteMany({id: "go-plus"})
db.draft_changes.deleteMany({projectId: "go-plus"})
db.ontology_changes.deleteMany({projectId: "go-plus"})
```

---

## Solution 4: Manual Status Fix (If Backend Won't Restart)

If you can't restart the backend and need to force status:

1. Open MongoDB Compass
2. Connect to `mongodb://localhost:27017`
3. Find `ontocode` → `project_documents` collection
4. Find document with `id: "go-plus"`
5. Edit the document:
   ```json
   {
     "status": "COMPLETED",
     "statusMessage": "Ontology imported successfully"
   }
   ```
6. Save
7. Refresh VS Code extension

---

## Prevention: Import Timeout Configuration

To prevent future hangs, we should add a timeout to the import process.

### Quick Fix for Future Imports:

**File:** `ontology-editor/src/main/java/self/research/ontology/owlEditor/service/GraphDBDatasetService.java`

Add connection timeout when creating repository connection:

```java
// Around line 416
try (RepositoryConnection conn = repo.getConnection()) {
    // Set query timeout (e.g., 5 minutes)
    conn.setQueryTimeout(300); // seconds

    conn.clear(conn.getValueFactory().createIRI(graphUri));
    log.info("Dataset cleared for project: {}", projectId);
}
```

---

## Verification Steps After Fix:

1. Check GraphDB triple count:
   ```sparql
   SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }
   ```
   Should be < 1000 before import

2. Check MongoDB status:
   ```bash
   mongosh mongodb://localhost:27017/ontocode --eval "db.project_documents.findOne({id: 'go-plus'}, {status: 1, statusMessage: 1})"
   ```
   Should show `COMPLETED` after successful import

3. Backend logs should show:
   ```
   Completed import for project go-plus
   ```

---

## For Large Ontology Files:

If you're importing a very large OWL file (>50MB or >100K triples):

1. **Increase JVM heap size:**
   ```bash
   export MAVEN_OPTS="-Xmx4g -Xms2g"
   mvn spring-boot:run
   ```

2. **Use streaming import** (already implemented, but verify):
   - Check that `bulkLoad()` uses InputStream
   - Avoid loading entire file into memory

3. **Consider alternative format:**
   - Convert OWL/XML to Turtle (TTL) - often faster to import
   - Or use N-Triples for maximum speed

---

## Emergency: Kill All and Start Fresh

If nothing else works:

```cmd
# 1. Kill all Java processes
taskkill /F /IM java.exe

# 2. Restart GraphDB
# (Restart GraphDB service from Services app or start GraphDB desktop)

# 3. Clean databases
cd scripts
clean-all-dbs.bat

# 4. Restart backend
cd ontology-editor
mvn spring-boot:run

# 5. Reload VS Code extension
# Press F5 or Ctrl+Shift+P -> "Developer: Reload Window"

# 6. Try import again
```

---

## Check System Resources:

Import hangs can also be caused by system resource exhaustion:

```cmd
# Check Java memory usage
tasklist /FI "IMAGENAME eq java.exe" /V

# Check GraphDB logs
# Usually in: C:\Users\YourUser\graphdb-home\logs\

# Check disk space
dir C:\

# Check if GraphDB is responding
curl http://localhost:7200/rest/repositories
```

---

##Need More Help?

Check these log files for detailed error messages:

1. **Backend logs:** Console output where `mvn spring-boot:run` is running
2. **GraphDB logs:** `{graphdb-home}/logs/main.log`
3. **MongoDB logs:** Check MongoDB service logs
4. **VS Code Extension logs:** Developer Tools Console (`Ctrl+Shift+I`)

Look for keywords: `timeout`, `OutOfMemoryError`, `RepositoryException`, `Connection refused`
