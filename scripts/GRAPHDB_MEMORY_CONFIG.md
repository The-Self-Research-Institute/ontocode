# GraphDB Configuration for Large File Imports

## Problem
230MB file import fails with:
```
java.net.SocketException: An established connection was aborted by the software in your host machine
org.apache.http.client.NonRepeatableRequestException: Cannot retry request with a non-repeatable request entity
```

## Root Cause
1. GraphDB runs out of memory during large imports
2. HTTP connection times out
3. Request entity (input stream) cannot be retried

## Solution: Increase GraphDB Memory

### Option 1: Edit GraphDB Configuration File (graphdb.properties)

**Location**: Find `graphdb.properties` in your GraphDB installation directory:
- Windows: `C:\Program Files\GraphDB\conf\graphdb.properties`
- Or wherever you installed GraphDB

**Add/Update these settings**:
```properties
# Increase heap memory for large imports (adjust based on your system RAM)
graphdb.heapsize=4g

# Increase transaction timeout (30 minutes)
graphdb.transaction.timeout=1800

# Increase HTTP request timeout
graphdb.connector.maxPostSize=524288000
```

### Option 2: Set JVM Options When Starting GraphDB

**Windows**:
```bash
SET GDB_HEAP_SIZE=4g
graphdb.cmd
```

**Linux/Mac**:
```bash
export GDB_HEAP_SIZE=4g
./graphdb
```

### Option 3: Modify graphdb.cmd (Windows) or graphdb (Linux/Mac)

Edit the startup script and add:
```bash
-Xms2g -Xmx4g
```

## Recommended Settings by File Size

| File Size | Heap Size | Why |
|-----------|-----------|-----|
| < 50MB | 2GB | Default |
| 50-200MB | 4GB | Moderate |
| 200-500MB | 8GB | Large (your case) |
| > 500MB | 16GB+ | Very large |

## After Configuration Change

1. **Restart GraphDB**
2. **Verify memory**: Check GraphDB workbench → Setup → System
3. **Try import again**

## Alternative: Split Large Files

If memory is limited, split your 230MB OWL file into smaller chunks:

```bash
# Install rapper (RDF parser)
# Then split the file
rapper -i rdfxml -o ntriples eco.owl > eco.nt
split -l 100000 eco.nt eco_part_
```

Then import each part separately.

## Check Current GraphDB Memory

1. Access http://localhost:7200
2. Go to Setup → System
3. Check "JVM Heap" values
