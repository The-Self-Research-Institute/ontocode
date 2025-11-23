# Graph View Optimization for Large Ontologies

## New API Endpoints Created

### 1. Get Root Classes
```
GET /api/ontology/{projectId}/hierarchy/roots
```
Returns only root-level classes (those without parents), with `hasChildren` flag.

### 2. Get Children (Lazy Load)
```
GET /api/ontology/{projectId}/hierarchy/children?classIRI={classIRI}
```
Fetches direct children of a specific class on-demand.

### 3. Get Parents (Lazy Load)
```
GET /api/ontology/{projectId}/hierarchy/parents?classIRI={classIRI}
```
Fetches direct parents of a specific class on-demand.

## How It Handles 30,000+ Classes

### Initial Load
- Fetches ONLY root classes (typically 5-50 classes)
- No edges initially
- Loads in <1 second regardless of ontology size

### Expand Children
- User clicks expand (▼)
- API fetches ONLY direct children of that node
- Typically 1-20 classes per parent
- Adds edges dynamically

### Expand Parents
- User clicks expand up (▲)
- API fetches ONLY direct parents of that node
- Typically 1-5 parents per class
- Updates dialog and graph

### Performance Characteristics
- **Initial Load**: O(root_count) - constant time
- **Expand**: O(children_count) - per parent
- **Memory**: O(visible_nodes) - not O(total_nodes)
- **Network**: Lazy loading - fetch only what's visible

### Example with 30,000 Classes
```
Depth 0 (Root):      10 classes loaded
Depth 1 (Expand):   +50 classes loaded (5 children × 10 parents)
Depth 2 (Expand):  +100 classes loaded
...
Total Visible:      160 classes (not 30,000!)
```

## Frontend Strategy

1. **Start with roots**: Show 5-50 nodes
2. **Lazy expand**: Fetch children on demand
3. **Smart caching**: Keep fetched nodes in memory
4. **Virtual scrolling**: For dialog with many nodes
5. **Incremental rendering**: D3 updates only changed nodes

## Backend Optimization

- Direct OWL API calls (no full graph traversal)
- Indexed lookups by class IRI
- Response includes `hasChildren` to show expand icons
- No unnecessary axiom loading
