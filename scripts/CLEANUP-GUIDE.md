# Database Cleanup Guide

This guide explains how to clear both MongoDB and GraphDB databases.

## Option 1: Using Node.js Script (Recommended)

```bash
cd scripts
node clear-databases.js
```

**Requirements:**
- Node.js installed
- MongoDB running on localhost:27017
- GraphDB running on localhost:7200

---

## Option 2: Manual MongoDB Cleanup

### Using MongoDB Shell (mongosh/mongo)

```bash
# Connect to MongoDB
mongosh mongodb://localhost:27017/ontocode

# Or if using older mongo client:
mongo mongodb://localhost:27017/ontocode
```

Then in the MongoDB shell:

```javascript
// Show all collections
show collections

// Drop all collections
db.getCollectionNames().forEach(function(c) {
    print('Dropping: ' + c);
    db[c].drop();
});

// Verify
show collections  // Should show no collections
```

### Using MongoDB Compass (GUI)

1. Open MongoDB Compass
2. Connect to `mongodb://localhost:27017`
3. Select the `ontocode` database
4. For each collection:
   - Click on the collection
   - Click the trash icon to drop it
5. Or delete the entire database from the left sidebar

---

## Option 3: Manual GraphDB Cleanup

### Using GraphDB Workbench (GUI)

1. Open GraphDB Workbench at http://localhost:7200
2. Select the `ontocode` repository
3. Go to **SPARQL** tab
4. Run this update query:

```sparql
DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }
```

5. Click **Execute**

### Using cURL (Command Line)

**Windows:**
```cmd
cd scripts
clear-graphdb.bat
```

**Linux/Mac:**
```bash
cd scripts
chmod +x clear-graphdb.sh
./clear-graphdb.sh
```

**Direct cURL command:**
```bash
curl -X POST "http://localhost:7200/repositories/ontocode/statements" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "update=DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }"
```

---

## Option 4: Using MongoDB Standalone Script

**Windows:**
```cmd
mongosh mongodb://localhost:27017/ontocode scripts\clear-mongodb.js
```

**Linux/Mac:**
```bash
mongosh mongodb://localhost:27017/ontocode scripts/clear-mongodb.js
```

---

## Verification

### Verify MongoDB is empty:
```bash
mongosh mongodb://localhost:27017/ontocode --eval "db.getCollectionNames()"
```

Should return: `[]`

### Verify GraphDB is empty:

Visit: http://localhost:7200/sparql

Run query:
```sparql
SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }
```

Should return: `count = 0`

---

## Common Issues

### MongoDB not accessible
- Check if MongoDB is running: `mongosh --version` or `mongo --version`
- Check if MongoDB service is running: `systemctl status mongod` (Linux) or Task Manager (Windows)
- Default port should be 27017

### GraphDB not accessible
- Check if GraphDB is running: visit http://localhost:7200
- Default port should be 7200
- Check if repository exists: http://localhost:7200/rest/repositories

### Permission errors
- Run command prompt/terminal as Administrator (Windows)
- Use `sudo` for Linux/Mac commands

---

## After Cleanup

After clearing the databases:

1. **Restart backend services:**
   ```bash
   # Stop all services (Ctrl+C if running)
   # Then restart them
   ```

2. **Upload a new ontology file** through the VS Code extension

3. The system will recreate all necessary collections and data

---

## Safety Note

⚠️ **Warning:** These operations will permanently delete all data including:
- All ontology projects
- Draft changes
- Change history
- User data (if using local auth)
- All triples in GraphDB

Make backups if needed before running cleanup scripts!
