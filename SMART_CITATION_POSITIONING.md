# Smart Citation Positioning Implementation

## Overview

This document describes the implementation of smart citation positioning, which ensures that citations from uploaded ontology files stay near their referenced entities even when GraphDB reorganizes the triples during export.

## Problem

When users upload ontology files with citations at specific positions:

1. **Upload**: File contains citations at lines 10-25 near Entity A
2. **Import**: Backend imports to GraphDB (triples stored in database)
3. **GraphDB Reorganization**: GraphDB stores triples in alphabetical/type-based order
4. **Export**: When viewing code, GraphDB exports in its own order
5. **Result**: Citations appear at random positions (e.g., lines 150-165), losing their association with entities

## Solution Architecture

The solution implements a four-tier approach:

### 1. Citation-Entity Metadata Storage (Backend)

**File**: `StorageManager.java`

- **New Method**: `storeCitationEntityMapping(projectId, citationUrn, entityUri)`
  - Stores citation-to-entity relationships as JSON metadata
  - Location: `{project-dir}/citation-metadata.json`
  - Format: `{"urn:citation:xxx": "http://example.org/Entity"}`

- **Example Metadata**:
  ```json
  {
    "urn:citation:smith2020": "http://example.org/ontology#Person",
    "urn:citation:jones2019": "http://example.org/ontology#Organization"
  }
  ```

### 2. Citation Extraction During Upload (Backend)

**File**: `StorageManager.java`, `ProjectLoadController.java`

- **New Method**: `extractCitationMappingsFromFile(filePath, projectId)`
  - Called during file upload, **before GraphDB import**
  - Parses uploaded ontology to find all citations
  - For each citation, searches backwards to find nearest entity
  - Stores extracted mappings in `citation-metadata.json`

- **Extraction Logic**:
  - Scans file line-by-line for citation URNs (`urn:citation:xxx`)
  - For each citation, searches up to 50 lines backwards
  - Finds first entity declaration (not another citation)
  - Supports all formats: Turtle, RDF/XML, N-Triples, OWL/XML, etc.

### 3. Smart Repositioning During Export (Backend)

**File**: `StorageManager.java`

- **Modified Method**: `exportOntology(projectId, format)`
  - After exporting from GraphDB, applies smart repositioning
  - Reads citation-entity mappings
  - Calls `repositionCitations()` to reorder content

- **New Method**: `repositionCitations(content, citationMappings, format)`
  - **Step 1**: Extract all citation blocks from exported content
  - **Step 2**: Find entity declarations in content
  - **Step 3**: Insert citations immediately after their referenced entities
  - **Step 4**: Append any unpositioned citations at the end (with warning comment)

- **Format Support**: Works with all 6 ontology formats:
  - Turtle (.ttl)
  - N-Triples (.nt)
  - RDF/XML (.owl, .rdf)
  - OWL/XML (.owlxml)
  - Manchester Syntax (.omn)
  - Functional Syntax (.ofn)

### 3. Smart Repositioning During Export (Backend)

**File**: `StorageManager.java`

- **Modified Method**: `exportOntology(projectId, format)`
  - After exporting from GraphDB, applies smart repositioning
  - Reads citation-entity mappings
  - Calls `repositionCitations()` to reorder content

- **New Method**: `repositionCitations(content, citationMappings, format)`
  - **Step 1**: Extract all citation blocks from exported content
  - **Step 2**: Find entity declarations in content
  - **Step 3**: Insert citations immediately after their referenced entities
  - **Step 4**: Append any unpositioned citations at the end (with warning comment)

- **Format Support**: Works with all 6 ontology formats:
  - Turtle (.ttl)
  - N-Triples (.nt)
  - RDF/XML (.owl, .rdf)
  - OWL/XML (.owlxml)
  - Manchester Syntax (.omn)
  - Functional Syntax (.ofn)

### 4. Frontend Metadata Transmission

**File**: `Dashboard.tsx`

- **Modified**: `handleInsertCitationAtLocation()`
  - Extracts `referencedEntity` from clicked line (already implemented)
  - Generates `citationUrn` from citation key
  - Sends both to backend when storing cache

- **API Call Changes**:
  ```typescript
  // Before
  await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
    content: modifiedContent,
    format: codeViewFormat
  });

  // After
  await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
    content: modifiedContent,
    format: codeViewFormat,
    citationUrn: citationUrn,           // NEW
    referencedEntity: referencedEntity  // NEW
  });
  ```

### 4. Frontend Metadata Transmission

**File**: `Dashboard.tsx`

- **Modified**: `handleInsertCitationAtLocation()`
  - Extracts `referencedEntity` from clicked line (already implemented)
  - Generates `citationUrn` from citation key
  - Sends both to backend when storing cache

- **API Call Changes**:
  ```typescript
  // Before
  await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
    content: modifiedContent,
    format: codeViewFormat
  });

  // After
  await apiClient.post(`/api/ontology/${projectId}/code-view-cache`, {
    content: modifiedContent,
    format: codeViewFormat,
    citationUrn: citationUrn,           // NEW
    referencedEntity: referencedEntity  // NEW
  });
  ```

### 5. Backend Endpoint Update

**File**: `ProjectLoadController.java`

- **Modified Endpoint**: `POST /api/ontology/{projectId}/code-view-cache`
  - Now accepts optional `citationUrn` and `referencedEntity` fields
  - Stores citation-entity mapping if both provided
  - Backward compatible (fields are optional)

- **Request Body**:
  ```json
  {
    "content": "... ontology content ...",
    "format": "turtle",
    "citationUrn": "urn:citation:smith2020",        // Optional
    "referencedEntity": "http://example.org/Person" // Optional
  }
  ```

## How It Works

### Scenario 1: File Upload with Existing Citations

1. User uploads `ontology.ttl` containing citations at lines 10-25 near entities
2. Backend receives file in `ProjectLoadController.upload()`
3. File is sanitized and saved to disk
4. **NEW**: `extractCitationMappingsFromFile()` is called:
   - Parses the file line-by-line
   - Finds citation URN: `urn:citation:smith2020` at line 15
   - Searches backwards, finds entity: `http://example.org/Person` at line 10
   - Stores mapping: `{"urn:citation:smith2020": "http://example.org/Person"}`
5. File is imported to GraphDB (which reorganizes triples)
6. When user views ontology:
   - Backend exports from GraphDB (citations scattered randomly)
   - **Smart repositioning applied**:
     - Reads `citation-metadata.json`
     - Extracts citation block for `urn:citation:smith2020`
     - Finds entity `http://example.org/Person` in exported content
     - Inserts citation immediately after entity
7. **Result**: Citation appears near entity despite GraphDB reorganization

### Scenario 2: Manual Citation Insertion

1. User clicks line containing `<http://example.org/Person>` in Turtle format
2. Frontend extracts entity: `http://example.org/Person`
3. User selects citation from Zotero
4. Frontend generates citation URN: `urn:citation:smith2020`
5. Frontend inserts citation block after entity declaration
6. Frontend stores cache with metadata:
   - Content: Modified ontology with citation
   - Citation URN: `urn:citation:smith2020`
   - Referenced Entity: `http://example.org/Person`
7. Backend stores both cache and metadata
8. **Result**: Citation appears near entity in all subsequent views

### Scenario 2: Manual Citation Insertion

1. User clicks line containing `<http://example.org/Person>` in Turtle format
2. Frontend extracts entity: `http://example.org/Person`
3. User selects citation from Zotero
4. Frontend generates citation URN: `urn:citation:smith2020`
5. Frontend inserts citation block after entity declaration
6. Frontend stores cache with metadata:
   - Content: Modified ontology with citation
   - Citation URN: `urn:citation:smith2020`
   - Referenced Entity: `http://example.org/Person`
7. Backend stores both cache and metadata
8. **Result**: Citation appears near entity in all subsequent views

### Scenario 3: Format Switch (Force Refresh)

1. User switches format or clicks "Refresh" button
2. Backend clears code-view cache
3. Backend exports fresh content from GraphDB
4. GraphDB returns content in alphabetical order (citations separated from entities)
5. **Smart Repositioning Applied**:
   - Backend reads citation-metadata.json
   - Finds `"urn:citation:smith2020": "http://example.org/Person"`
   - Extracts citation block from GraphDB export
   - Finds `http://example.org/Person` declaration
   - Inserts citation immediately after entity
6. **Result**: Citation appears near entity despite GraphDB reorganization

## Technical Details

### Citation Extraction During Upload

**Algorithm**:
1. Read uploaded file line-by-line
2. For each line, check if it contains a citation URN (`urn:citation:xxx`)
3. If found, search backwards up to 50 lines to find nearest entity
4. Extract entity URI using format-specific patterns
5. Store mapping: `{citationUrn: entityUri}`

**Entity Detection Patterns**:
- **Turtle/N-Triples**: `<http://example.org/Entity>` or `<urn:entity:xxx>`
- **RDF/XML**: `rdf:about="http://example.org/Entity"`
- **OWL/XML**: `IRI="http://example.org/Entity"`
- **Prefixed Names**: `ex:Entity` (excludes common RDF/OWL predicates like `rdf:type`)

**Example - Turtle Format**:
```turtle
<http://example.org/Person> rdf:type owl:Class ;
    rdfs:label "Person" ;
    rdfs:comment "Represents a person" .

<urn:citation:smith2020> rdf:type owl:NamedIndividual ;
    dc:title "Research on Persons" ;
    bibo:doi "10.1234/example" .
```

- Citation at line 5: `urn:citation:smith2020`
- Search backwards, find entity at line 1: `http://example.org/Person`
- Store: `{"urn:citation:smith2020": "http://example.org/Person"}`

### Citation Block Extraction Patterns

**Turtle Format**:
```turtle
<urn:citation:xxx> rdf:type owl:NamedIndividual ;
    dc:title "Research Title" ;
    dc:creator "Author Name" ;
    bibo:doi "10.1234/example" .
```
- Extracted by finding `<urn:citation:xxx>` and reading until line ending with `.`

**RDF/XML Format**:
```xml
<rdf:Description rdf:about="urn:citation:xxx">
    <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#NamedIndividual"/>
    <dc:title>Research Title</dc:title>
    <bibo:doi>10.1234/example</bibo:doi>
</rdf:Description>
```
- Extracted by finding `rdf:about="urn:citation:xxx"` and reading until `</rdf:Description>`

### Entity Detection Patterns

The system detects entities using comprehensive pattern matching:

1. **Full URI in angle brackets**: `<http://example.org/Person>`
2. **RDF/XML attributes**: `rdf:about="http://example.org/Person"`
3. **Prefixed names**: `ex:Person`, `owl:Person`
4. **Local names**: `Person` (when entity ends with this)

### Metadata Storage Format

**Location**: `{project-dir}/citation-metadata.json`

**Structure**:
```json
{
  "urn:citation:smith2020": "http://example.org/ontology#Person",
  "urn:citation:jones2019": "http://example.org/ontology#Organization",
  "urn:citation:doe2021": "ex:ResearchProject"
}
```

- **Key**: Citation URN (unique identifier)
- **Value**: Entity URI or prefixed name
- **Lifecycle**: 
  - Created when first citation inserted
  - Updated when new citations added
  - Preserved across format switches
  - Cleared when `clearCodeViewCache()` is called

## Benefits

1. **Preserves Semantic Relationships**: Citations stay near their referenced entities
2. **Works Across Formats**: Applies to all 6 ontology formats
3. **Automatic**: No user action required after initial insertion
4. **Persistent**: Survives format switches and GraphDB exports
5. **Backward Compatible**: Old code continues to work (metadata is optional)

## API Changes Summary

### New Backend Methods

**StorageManager.java**:
- `extractCitationMappingsFromFile(filePath, projectId)` - Parse uploaded file for citations
- `extractCitationEntityMappings(content, format)` - Extract mappings from content
- `findNearestEntityBeforeLine(lines, citationLine, format)` - Find entity before citation
- `extractEntityFromLine(line, format)` - Parse entity from line
- `detectFormatFromPath(filePath)` - Detect format from file extension
- `storeCitationEntityMapping(projectId, citationUrn, entityUri)` - Store metadata
- `getCitationEntityMappings(projectId)` - Retrieve metadata
- `clearCitationEntityMappings(projectId)` - Clear metadata
- `repositionCitations(content, citationMappings, format)` - Reorder content
- `extractCitationUrn(line, format)` - Parse citation URN from line
- `extractCitationBlock(lines, startIndex, format)` - Extract full citation block
- `lineContainsEntity(line, entityUri, format)` - Detect entity in line
- `findEntityEndLine(lines, startIndex, format)` - Find end of entity declaration

**ProjectLoadController.java**:
- Added call to `extractCitationMappingsFromFile()` in `upload()` method after file sanitization

### Modified Backend Endpoints

**POST /api/ontology/{projectId}/code-view-cache**:
- Now accepts optional `citationUrn` and `referencedEntity`
- Stores citation-entity mapping if both provided
- Returns same response as before

### Frontend Changes

**Dashboard.tsx**:
- Modified `handleInsertCitationAtLocation()` to send citation metadata
- Added `citationUrn` and `referencedEntity` to cache storage calls
- Cross-format sync also includes metadata

## Testing Recommendations

### Test Case 1: Upload File with Citations
1. Create ontology file with citations near entities (Turtle format):
   ```turtle
   <http://example.org/Person> rdf:type owl:Class .
   
   <urn:citation:test123> rdf:type owl:NamedIndividual ;
       dc:title "Test Citation" .
   ```
2. Upload the file
3. View in Code View → citation should appear near `Person` class
4. Switch to RDF/XML format → citation still near `Person`
5. Click "Force Refresh" → citation still positioned correctly

### Test Case 2: Citation Positioning After Format Switch
1. Open ontology in Turtle format
2. Insert citation near an entity
3. Switch to RDF/XML format
4. Verify citation appears near same entity
5. Click "Force Refresh" button
6. Verify citation still near entity (not moved by GraphDB export)

### Test Case 2: Citation Positioning After Format Switch
1. Open ontology in Turtle format
2. Insert citation near an entity
3. Switch to RDF/XML format
4. Verify citation appears near same entity
5. Click "Force Refresh" button
6. Verify citation still near entity (not moved by GraphDB export)

### Test Case 3: Multiple Citations Per Entity
1. Insert multiple citations for the same entity
2. All citations should appear grouped near that entity
3. Format switch should preserve grouping

### Test Case 3: Multiple Citations Per Entity
1. Insert multiple citations for the same entity
2. All citations should appear grouped near that entity
3. Format switch should preserve grouping

### Test Case 4: Citation Without Entity (Edge Case)
1. Insert citation on blank line (no entity detected)
2. Citation stored with empty `referencedEntity`
3. After format switch, citation appended at end with comment:
   ```turtle
   # Citation appended (entity not found)
   <urn:citation:xxx> ...
   ```

### Test Case 5: Uploaded File with Multiple Citations
1. Create ontology with 3 entities and 3 citations (each near different entity)
2. Upload the file
3. Verify all citations positioned near their respective entities
4. Switch formats multiple times
5. Verify all citations remain with their entities

### Test Case 6: Backward Compatibility
1. Old projects without citation-metadata.json
2. System should work normally
3. New citations will create metadata file
4. Old citations remain in GraphDB export order

## Future Enhancements

1. ~~**Automatic Metadata Extraction**: Parse uploaded ontology files to extract existing citation-entity relationships~~ ✅ **IMPLEMENTED**
2. **SPARQL Integration**: Store citation-entity links directly in GraphDB as RDF triples using `prov:wasDerivedFrom`
3. **Bulk Repositioning**: UI button to reposition all citations in existing projects
4. **Citation Grouping**: Support multiple citation styles (inline vs. bibliography section)
5. **Visual Indicators**: Highlight citations in code view to show their entity associations

## Related Files

- `ontology-editor/src/main/java/self/research/ontology/owlEditor/service/StorageManager.java`
- `ontology-editor/src/main/java/self/research/ontology/owlEditor/controller/ProjectLoadController.java`
- `ontology-vscode-extension/webview-src/components/Dashboard.tsx`
- `{project-dir}/citation-metadata.json` (generated at runtime)

## Dependencies

- **Jackson**: For JSON serialization of citation metadata
  - `com.fasterxml.jackson.databind.ObjectMapper`
  - `com.fasterxml.jackson.core.type.TypeReference`

Both dependencies already included in the project.
