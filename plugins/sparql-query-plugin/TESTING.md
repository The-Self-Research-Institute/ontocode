# SPARQL Query Plugin - Testing Document

**Plugin:** @ontocode/sparql-query-plugin v1.0.0  
**Category:** Query  
**Last Updated:** April 2026

---

## Table of Contents
1. [Overview](#overview)
2. [Test Environment Setup](#test-environment-setup)
3. [Query Editor Tests](#query-editor-tests)
4. [Query Execution Tests](#query-execution-tests)
5. [Query Management Tests](#query-management-tests)
6. [Results Display Tests](#results-display-tests)
7. [Export Tests](#export-tests)
8. [Sample Query Tests](#sample-query-tests)
9. [Error Handling Tests](#error-handling-tests)
10. [Performance Tests](#performance-tests)

---

## Overview

The SPARQL Query Plugin provides a full-featured SPARQL query editor with syntax highlighting, query management (save/load/delete), live execution, results display (table/JSON), CSV export, and prefix management.

### Components Under Test
- `SparqlQueryEditor.tsx` — Complete query interface

### API Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sparql/{projectId}/queries` | List saved queries |
| POST | `/api/sparql/{projectId}/queries` | Create/save query |
| PUT | `/api/sparql/{projectId}/queries/{id}` | Update query |
| DELETE | `/api/sparql/{projectId}/queries/{id}` | Delete query |
| POST | `/api/sparql/{projectId}/execute` | Execute query |

### SPARQL Keywords Supported
`SELECT`, `CONSTRUCT`, `DESCRIBE`, `ASK`, `WHERE`, `FROM`, `PREFIX`, `BASE`, `OPTIONAL`, `UNION`, `FILTER`, `GRAPH`, `ORDER BY`, `LIMIT`, `OFFSET`, `DISTINCT`, `GROUP BY`, `BIND`, `AS`, `SERVICE`, `MINUS`, `EXISTS`, `INSERT`, `DELETE`

---

## Test Environment Setup

### Prerequisites
- OntoCode platform running
- GraphDB with loaded test ontology (`test-sparql-ontology.owl`)
- E-commerce domain ontology loaded

### Test Ontology: E-Commerce Domain
**Classes:** Product → (Electronics → Laptop, Smartphone), Clothing; Customer; Order; Supplier; Category  
**Individuals:** MacBookPro, DellXPS15, iPhone14, GalaxyS23, TShirt, Customer001-003, Order001-003, AppleInc, DellTech, SamsungElec  

**Object Properties:** orderedBy, contains, suppliedBy, belongsToCategory  
**Data Properties:** productName, price, stockQuantity, rating, customerName, email, orderDate, totalAmount, supplierName

### Common PREFIX Block
```sparql
PREFIX prod: <http://www.example.org/products#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
```

---

## Query Editor Tests

### TC-SQ-001: Editor Loads Correctly
**Objective:** Verify SPARQL editor initializes with default state  
**Steps:**
1. Open SPARQL Query Plugin
2. Verify editor area is visible

**Expected Results:**
- Text editor area empty or with default placeholder
- "Run" button visible and enabled
- Query management buttons (Save, Load, Delete) visible
- No errors in console

---

### TC-SQ-002: Syntax Highlighting
**Objective:** Verify SPARQL keywords are highlighted  
**Steps:**
1. Type: `SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10`
2. Observe syntax coloring

**Expected Results:**
- Keywords (SELECT, WHERE, LIMIT) highlighted in distinct color
- Variables (?s, ?p, ?o) highlighted differently
- Numbers (10) in different color
- Strings in quotes highlighted
- Comments (# text) greyed out

---

### TC-SQ-003: Prefix Management
**Objective:** Verify automatic PREFIX detection and management  
**Steps:**
1. Load an ontology
2. Open PREFIX helper/management
3. Verify common prefixes available

**Expected Results:**
- Standard prefixes available: rdf, rdfs, owl, xsd
- Ontology-specific prefix detected from loaded ontology
- Click to insert prefix into query
- No duplicate PREFIX declarations

---

### TC-SQ-004: Multi-Line Query Editing
**Objective:** Verify multi-line query input works  
**Steps:**
1. Enter a complex multi-line query with indentation
2. Edit middle lines
3. Add/remove lines

**Expected Results:**
- Line numbers displayed
- Indentation preserved
- Enter creates new line
- Tab indents
- Selection and cut/copy/paste work

---

## Query Execution Tests

### TC-SQ-005: Simple SELECT Query
**Objective:** Verify basic triple pattern query  
**Query:**
```sparql
SELECT ?s ?p ?o WHERE {
  ?s ?p ?o
} LIMIT 10
```
**Expected Results:**
- 10 results returned
- Three columns: s, p, o
- Execution time displayed
- No errors

---

### TC-SQ-006: SELECT with PREFIX
**Objective:** Verify query execution with PREFIX declarations  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
SELECT ?product ?name ?price
WHERE {
  ?product a prod:Product .
  ?product prod:productName ?name .
  ?product prod:price ?price .
}
```
**Expected Results:**
- Product instances listed with names and prices
- MacBookPro, DellXPS15, iPhone14, GalaxyS23, TShirt expected
- Correct price values displayed

---

### TC-SQ-007: OPTIONAL Pattern
**Objective:** Verify OPTIONAL clause includes partial matches  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
SELECT ?product ?name ?rating
WHERE {
  ?product a prod:Product .
  ?product prod:productName ?name .
  OPTIONAL { ?product prod:rating ?rating }
}
```
**Expected Results:**
- All products returned even without ratings
- Products with ratings show values
- Products without ratings show empty/null for rating column

---

### TC-SQ-008: FILTER Query
**Objective:** Verify FILTER conditions work  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
SELECT ?product ?name ?price
WHERE {
  ?product a prod:Product .
  ?product prod:productName ?name .
  ?product prod:price ?price .
  FILTER (?price > 500)
}
```
**Expected Results:**
- Only products with price > 500 returned
- Budget products excluded
- Correct comparison semantics

---

### TC-SQ-009: ORDER BY and LIMIT
**Objective:** Verify sorting and result limiting  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
SELECT ?name ?price
WHERE {
  ?product a prod:Product .
  ?product prod:productName ?name .
  ?product prod:price ?price .
}
ORDER BY DESC(?price)
LIMIT 3
```
**Expected Results:**
- Maximum 3 results
- Ordered by price descending (highest first)
- Correct sort order

---

### TC-SQ-010: ASK Query
**Objective:** Verify boolean ASK query  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
ASK WHERE {
  ?product a prod:Laptop .
  ?product prod:price ?price .
  FILTER (?price > 1000)
}
```
**Expected Results:**
- Returns `true` or `false`
- Boolean result clearly displayed
- No tabular format needed

---

### TC-SQ-011: CONSTRUCT Query
**Objective:** Verify CONSTRUCT builds new triples  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
CONSTRUCT {
  ?product prod:isExpensive true .
}
WHERE {
  ?product a prod:Product .
  ?product prod:price ?price .
  FILTER (?price > 1000)
}
```
**Expected Results:**
- RDF triples returned (not tabular)
- New triples constructed from pattern
- Results in Turtle/RDF format

---

### TC-SQ-012: DESCRIBE Query
**Objective:** Verify DESCRIBE returns entity information  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
DESCRIBE prod:MacBookPro
```
**Expected Results:**
- All triples about MacBookPro returned
- Both incoming and outgoing relationships
- RDF format output

---

### TC-SQ-013: UNION Query
**Objective:** Verify UNION combines result sets  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
SELECT ?name ?type
WHERE {
  { ?x a prod:Laptop . ?x prod:productName ?name . BIND("Laptop" AS ?type) }
  UNION
  { ?x a prod:Smartphone . ?x prod:productName ?name . BIND("Smartphone" AS ?type) }
}
```
**Expected Results:**
- Results from both patterns combined
- Laptops and smartphones in same result set
- Type column distinguishes them

---

### TC-SQ-014: GROUP BY with Aggregation
**Objective:** Verify aggregation functions  
**Query:**
```sparql
PREFIX prod: <http://www.example.org/products#>
SELECT ?type (COUNT(?product) AS ?count) (AVG(?price) AS ?avgPrice)
WHERE {
  ?product a ?type .
  ?product prod:price ?price .
  FILTER (?type != owl:NamedIndividual)
}
GROUP BY ?type
```
**Expected Results:**
- Grouped by product type
- COUNT shows number per type
- AVG shows average price per type

---

### TC-SQ-015: SubClass Hierarchy Query
**Objective:** Verify class hierarchy traversal  
**Query:**
```sparql
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?class ?superClass
WHERE {
  ?class rdfs:subClassOf ?superClass .
  FILTER (!isBlank(?class) && !isBlank(?superClass))
}
```
**Expected Results:**
- SubClassOf relationships listed
- Blank nodes filtered out
- Hierarchy structure visible

---

### TC-SQ-016: Execution Time Display
**Objective:** Verify query execution time is shown  
**Steps:**
1. Run any query
2. Check execution time display

**Expected Results:**
- Time shown in milliseconds
- Appears after query completes
- Accurate measurement

---

## Query Management Tests

### TC-SQ-017: Save Query
**Objective:** Verify saving a query for later use  
**Steps:**
1. Write a query in the editor
2. Enter query name: "All Products"
3. Click Save

**Expected Results:**
- Query saved successfully
- Confirmation message shown
- Query appears in saved queries list
- Query ID assigned

---

### TC-SQ-018: Load Saved Query
**Objective:** Verify loading a previously saved query  
**Steps:**
1. Open saved queries list
2. Click on "All Products" query
3. Query loads in editor

**Expected Results:**
- Editor populated with saved query text
- Query name displayed
- Ready to execute without modification

---

### TC-SQ-019: Update Saved Query
**Objective:** Verify updating an existing saved query  
**Steps:**
1. Load saved query
2. Modify the query text
3. Click Save/Update

**Expected Results:**
- Updated query overwrites previous version
- Same query ID retained
- Updated timestamp

---

### TC-SQ-020: Delete Saved Query
**Objective:** Verify deleting a saved query  
**Steps:**
1. Select a saved query
2. Click Delete
3. Confirm deletion

**Expected Results:**
- Query removed from list
- Confirmation prompt before deletion
- Cannot be undone
- Editor cleared or unchanged

---

### TC-SQ-021: Load Sample Queries
**Objective:** Verify pre-built sample queries load correctly  
**Steps:**
1. Open sample queries dropdown
2. Select each: "List All Classes", "List All Properties", "List All Individuals", "Count Triples", "SubClass Hierarchy"

**Expected Results:**
- Each sample query loads in editor
- Syntax is correct and executable
- Appropriate prefixes included
- Each produces results on test ontology

---

## Results Display Tests

### TC-SQ-022: Table View
**Objective:** Verify results displayed in table format  
**Steps:**
1. Execute a SELECT query with multiple columns
2. View results in Table mode

**Expected Results:**
- Column headers match query variables
- Rows contain result bindings
- URIs displayed (optionally as links)
- Literals show value and datatype
- Table scrollable for many results

---

### TC-SQ-023: JSON View
**Objective:** Verify results displayed in JSON format  
**Steps:**
1. Execute a query
2. Switch to JSON view

**Expected Results:**
- Well-formatted JSON with `head.vars` and `results.bindings`
- Syntax highlighted
- Collapsible sections
- Copy-to-clipboard available

---

### TC-SQ-024: Empty Results
**Objective:** Verify display when query returns no results  
**Query:**
```sparql
SELECT ?x WHERE { ?x a <http://nonexistent/Class> }
```
**Expected Results:**
- "No results found" message
- Table headers shown but no rows
- No error thrown

---

### TC-SQ-025: Large Result Sets
**Objective:** Verify handling of 1000+ results  
**Steps:**
1. Execute `SELECT ?s ?p ?o WHERE { ?s ?p ?o }` without LIMIT

**Expected Results:**
- Results load progressively or paginated
- Table remains scrollable and responsive
- Row count displayed
- No browser freeze

---

## Export Tests

### TC-SQ-026: CSV Export
**Objective:** Verify exporting results as CSV  
**Steps:**
1. Execute a query with results
2. Click "Export CSV"

**Expected Results:**
- CSV file downloaded
- Headers match query variables
- Values properly escaped (commas, quotes)
- UTF-8 encoding
- File named with timestamp

---

### TC-SQ-027: CSV Export — Special Characters
**Objective:** Verify CSV handles special characters  
**Steps:**
1. Run query returning values with commas, quotes, newlines
2. Export as CSV

**Expected Results:**
- Values with commas enclosed in quotes
- Embedded quotes escaped (doubled)
- Newlines within values handled
- File opens correctly in Excel/Sheets

---

## Error Handling Tests

### TC-SQ-028: Syntax Error in Query
**Objective:** Verify graceful handling of malformed SPARQL  
**Query:** `SELEC ?s WHERE { ?s ?p ?o }`  
**Expected Results:**
- Error message: syntax error indication
- Line/position of error if available
- Editor remains functional
- No crash

---

### TC-SQ-029: Unknown PREFIX
**Objective:** Verify error for undeclared prefix  
**Query:** `SELECT ?x WHERE { ?x unknown:prop "value" }`  
**Expected Results:**
- Error: "Undefined prefix: unknown"
- Suggestion to add PREFIX declaration

---

### TC-SQ-030: Server Error (500)
**Objective:** Verify handling of backend errors  
**Steps:**
1. Execute a query that causes backend error
2. (Common: using default `:` prefix instead of named prefix)

**Expected Results:**
- Error message displayed (not raw 500 HTML)
- Troubleshooting hints:
  - Use named PREFIX instead of default `:`
  - Use `a` shorthand for `rdf:type`
  - Check all prefixes declared
- Retry option

---

### TC-SQ-031: Timeout on Complex Query
**Objective:** Verify timeout handling for expensive queries  
**Steps:**
1. Execute a very complex join/Cartesian product query
2. Wait for timeout

**Expected Results:**
- Timeout error after reasonable delay
- Suggestion to simplify query or add LIMIT
- Cancel button available during execution

---

### TC-SQ-032: Connection Error
**Objective:** Verify handling when SPARQL endpoint is unreachable  
**Steps:**
1. Disconnect GraphDB
2. Attempt query execution

**Expected Results:**
- "Connection failed" error message
- Retry option
- Editor state preserved

---

## Performance Tests

### TC-SQ-033: Query Execution Speed
**Metrics by ontology size:**
| Ontology Size | Simple SELECT | Complex JOIN | Expected Time |
|---------------|---------------|--------------|---------------|
| < 100 triples | 10 results | 3-way join | < 500ms |
| 1,000 triples | All | 2-way join | < 2s |
| 10,000+ triples | LIMIT 100 | OPTIONAL | < 5s |

---

### TC-SQ-034: Editor Responsiveness
**Objective:** Verify editor remains responsive during query execution  
**Steps:**
1. Execute a long-running query
2. Try typing in the editor while query runs
3. Try switching tabs

**Expected Results:**
- Editor remains responsive
- Typing not blocked
- Loading indicator visible
- Cancel available

---

## Test Summary Matrix

| Test ID | Category | Priority | Status |
|---------|----------|----------|--------|
| TC-SQ-001 | Editor | P0 | ☐ |
| TC-SQ-002 | Editor | P1 | ☐ |
| TC-SQ-003 | Editor | P1 | ☐ |
| TC-SQ-004 | Editor | P1 | ☐ |
| TC-SQ-005 | Execution | P0 | ☐ |
| TC-SQ-006 | Execution | P0 | ☐ |
| TC-SQ-007 | Execution | P1 | ☐ |
| TC-SQ-008 | Execution | P0 | ☐ |
| TC-SQ-009 | Execution | P1 | ☐ |
| TC-SQ-010 | Execution | P1 | ☐ |
| TC-SQ-011 | Execution | P2 | ☐ |
| TC-SQ-012 | Execution | P2 | ☐ |
| TC-SQ-013 | Execution | P1 | ☐ |
| TC-SQ-014 | Execution | P1 | ☐ |
| TC-SQ-015 | Execution | P1 | ☐ |
| TC-SQ-016 | Execution | P1 | ☐ |
| TC-SQ-017 | Management | P0 | ☐ |
| TC-SQ-018 | Management | P0 | ☐ |
| TC-SQ-019 | Management | P1 | ☐ |
| TC-SQ-020 | Management | P0 | ☐ |
| TC-SQ-021 | Samples | P1 | ☐ |
| TC-SQ-022 | Results | P0 | ☐ |
| TC-SQ-023 | Results | P1 | ☐ |
| TC-SQ-024 | Results | P1 | ☐ |
| TC-SQ-025 | Results | P1 | ☐ |
| TC-SQ-026 | Export | P0 | ☐ |
| TC-SQ-027 | Export | P2 | ☐ |
| TC-SQ-028 | Error Handling | P0 | ☐ |
| TC-SQ-029 | Error Handling | P1 | ☐ |
| TC-SQ-030 | Error Handling | P0 | ☐ |
| TC-SQ-031 | Error Handling | P1 | ☐ |
| TC-SQ-032 | Error Handling | P1 | ☐ |
| TC-SQ-033 | Performance | P1 | ☐ |
| TC-SQ-034 | Performance | P1 | ☐ |
