# GraphDB Setup Guide

This project uses **Ontotext GraphDB** as the triple store for ontology storage. Follow these steps to set it up.

## Prerequisites

- Java 11 or higher
- GraphDB Free Edition (or higher)

## Installation

### Option 1: Download GraphDB Free Edition

1. Visit [Ontotext GraphDB Downloads](https://www.ontotext.com/products/graphdb/download/)
2. Download **GraphDB Free Edition** (desktop standalone)
3. Extract the archive
4. Run GraphDB:
   - **Windows**: `bin\graphdb.cmd`
   - **Linux/Mac**: `bin/graphdb`

### Option 2: Using Docker

```bash
docker run -d -p 7200:7200 --name graphdb ontotext/graphdb:10.7.0-free
```

## Create Repository

1. Open GraphDB Workbench: [http://localhost:7200](http://localhost:7200)

2. Navigate to **Setup → Repositories**

3. Click **Create new repository**

4. Configure the repository:
   - **Repository ID**: `ontocode` (must match `application.properties`)
   - **Repository title**: `OntoCode Ontology Repository`
   - **Ruleset**: `OWL2-RL (Optimized)` (recommended for OWL reasoning)
   - **Storage**: Keep defaults
   
5. Click **Create**

## Configuration

The application is configured in `ontology-editor/src/main/resources/application.properties`:

```properties
# GraphDB Configuration
graphdb.url=${GRAPHDB_URL:http://localhost:7200}
graphdb.repository=${GRAPHDB_REPOSITORY:ontocode}

# SPARQL Endpoints
sparql.endpointUrl=${SPARQL_ENDPOINT:http://localhost:7200/repositories/ontocode}
sparql.updateEndpointUrl=${SPARQL_UPDATE_ENDPOINT:http://localhost:7200/repositories/ontocode/statements}
```

### Environment Variables (Optional)

You can override defaults using environment variables:

```bash
export GRAPHDB_URL=http://localhost:7200
export GRAPHDB_REPOSITORY=ontocode
export SPARQL_ENDPOINT=http://localhost:7200/repositories/ontocode
export SPARQL_UPDATE_ENDPOINT=http://localhost:7200/repositories/ontocode/statements
```

## Verify Setup

### 1. Check GraphDB is Running

```bash
curl http://localhost:7200/rest/repositories
```

Expected output: JSON list of repositories including `ontocode`

### 2. Test SPARQL Query

Open: [http://localhost:7200/sparql](http://localhost:7200/sparql)

Run a test query:
```sparql
SELECT * WHERE { ?s ?p ?o } LIMIT 10
```

### 3. Start the Application

```bash
cd ontology-editor
mvn spring-boot:run
```

Check logs for:
```
Successfully connected to GraphDB repository at http://localhost:7200
```

## Troubleshooting

### Error: `RepositoryException: unable to start transaction. HTTP error code 404`

**Cause**: Repository doesn't exist or GraphDB is not running

**Solution**:
1. Ensure GraphDB is running: [http://localhost:7200](http://localhost:7200)
2. Verify repository exists: [http://localhost:7200/repository](http://localhost:7200/repository)
3. Create repository if missing (see **Create Repository** above)

### Error: `Connection refused`

**Cause**: GraphDB is not running

**Solution**:
```bash
# Start GraphDB (standalone)
bin/graphdb

# OR using Docker
docker start graphdb
```

### Error: `Repository 'ontocode' not found`

**Cause**: Repository name mismatch or repository not created

**Solution**:
1. Check `application.properties` has `graphdb.repository=ontocode`
2. Create repository named `ontocode` in GraphDB Workbench
3. Or update `graphdb.repository` to match existing repository name

## GraphDB Features Used

- **Named Graphs**: Each project stored in separate named graph: `http://ontocode.org/project/{projectId}`
- **OWL2-RL Reasoning**: Automatic inference of OWL axioms
- **SPARQL 1.1**: Full SPARQL query and update support
- **HTTP Repository**: RDF4J HTTP protocol for remote access

## Performance Tips

1. **Increase Memory** (for large ontologies):
   ```bash
   # Edit graphdb.in.sh (Linux/Mac) or graphdb.in.cmd (Windows)
   GDB_HEAP_SIZE=4g
   ```

2. **Enable Caching**:
   - GraphDB Workbench → Setup → Repositories → Edit Repository
   - Increase entity pool and query cache sizes

3. **Bulk Loading**:
   - Use RDF/XML or Turtle formats (faster parsing)
   - Load during off-peak hours for large datasets

## Resources

- [GraphDB Documentation](https://graphdb.ontotext.com/documentation/)
- [SPARQL Query Reference](https://www.w3.org/TR/sparql11-query/)
- [RDF4J Documentation](https://rdf4j.org/documentation/)
