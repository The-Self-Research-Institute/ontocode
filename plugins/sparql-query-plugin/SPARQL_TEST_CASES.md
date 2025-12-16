# SPARQL Query Plugin - Test Cases

## Overview
This document provides comprehensive test cases for the SPARQL Query Plugin using the `test-sparql-ontology.owl` file (E-commerce domain).

## Common Issues & Solutions

### 500 Error Fixes
If you get a 500 error, try these solutions:

1. **Use named PREFIX instead of default `:`**
   ```sparql
   # Instead of:
   PREFIX : <http://www.example.org/products#>
   
   # Use:
   PREFIX prod: <http://www.example.org/products#>
   ```

2. **Use `a` shorthand for `rdf:type`**
   ```sparql
   # Instead of:
   ?product rdf:type prod:Product .
   
   # Use:
   ?product a prod:Product .
   ```

3. **Always include rdfs prefix when using subClassOf**
   ```sparql
   PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
   ```

4. **Ensure all prefixes are declared**
   ```sparql
   PREFIX prod: <http://www.example.org/products#>
   PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
   PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
   PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
   ```

---

## Test Ontology Structure

### Classes
- Product
  - Electronics
    - Laptop (MacBookPro, DellXPS15)
    - Smartphone (iPhone14, GalaxyS23)
  - Clothing (TShirt)
- Customer (Customer001, Customer002, Customer003)
- Order (Order001, Order002, Order003)
- Supplier (AppleInc, DellTech, SamsungElec)
- Category

### Properties
**Object Properties**:
- orderedBy (Order → Customer)
- contains (Order → Product)
- suppliedBy (Product → Supplier)
- belongsToCategory (Product → Category)

**Data Properties**:
- productName, price, stockQuantity, rating (Product)
- customerName, email (Customer)
- orderDate, totalAmount (Order)
- supplierName (Supplier)

---

## Basic SPARQL Query Test Cases

### TC-SPARQL-00: Get All Triples (Simple Query)
**Objective**: Retrieve any 10 triples from the database

**SPARQL Query (simplest possible)**:
```sparql
SELECT ?s ?p ?o WHERE {
  ?s ?p ?o
} LIMIT 10
```

**Expected Result**: 
- 10 random triples from the ontology
- Shows subjects, predicates, and objects

**Note**: This is the simplest SPARQL query - no PREFIX needed, works on any endpoint

---

### TC-SPARQL-01: SELECT All Products
**Objective**: Query all product instances

**SPARQL Query (with PREFIX)**:
```sparql
PREFIX prod: <http://www.example.org/products#>

SELECT ?product ?name ?price
WHERE {
  ?product a prod:Product .
  ?product prod:productName ?name .
  ?product prod:price ?price .
}
```

**SPARQL Query (without PREFIX - using full URIs)**:
```sparql
SELECT ?product ?name ?price
WHERE {
  ?product a <http://www.example.org/products#Product> .
  ?product <http://www.example.org/products#productName> ?name .
  ?product <http://www.example.org/products#price> ?price .
}
```

**Expected Result**: 
- 5 products: MacBookPro, DellXPS15, iPhone14, GalaxyS23, TShirt

**Note**: The `a` keyword is shorthand for `rdf:type` and works without PREFIX declarations

---

### TC-SPARQL-02: Filter by Price
**Objective**: Find products under $1000

**SPARQL Query (with PREFIX)**:
```sparql
PREFIX prod: <http://www.example.org/products#>

SELECT ?product ?name ?price
WHERE {
  ?product prod:productName ?name .
  ?product prod:price ?price .
  FILTER (?price < 1000)
}
ORDER BY ?price
```

**SPARQL Query (without PREFIX - using full URIs)**:
```sparql
SELECT ?product ?name ?price
WHERE {
  ?product <http://www.example.org/products#productName> ?name .
  ?product <http://www.example.org/products#price> ?price .
  FILTER (?price < 1000)
}
ORDER BY ?price
```

**Expected Result**: 
- TShirt ($29.99)
- GalaxyS23 ($899.99)
- iPhone14 ($999.99)

---

### TC-SPARQL-03: Count Products by Category
**Objective**: Count electronics vs other products

**SPARQL Query (with PREFIX)**:
```sparql
PREFIX prod: <http://www.example.org/products#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?type (COUNT(?product) AS ?count)
WHERE {
  ?product a ?type .
  ?type rdfs:subClassOf* prod:Product .
}
GROUP BY ?type
```

**SPARQL Query (without PREFIX - using full URIs)**:
```sparql
SELECT ?type (COUNT(?product) AS ?count)
WHERE {
  ?product a ?type .
  ?type <http://www.w3.org/2000/01/rdf-schema#subClassOf>* <http://www.example.org/products#Product> .
}
GROUP BY ?type
```

**Expected Result**: 
- Laptop: 2
- Smartphone: 2
- Clothing: 1

---

### TC-SPARQL-04: JOIN - Orders with Customer Names
**Objective**: Get order details with customer information

**SPARQL Query (with PREFIX)**:
```sparql
PREFIX prod: <http://www.example.org/products#>

SELECT ?order ?customer ?customerName ?totalAmount
WHERE {
  ?order prod:orderedBy ?customer .
  ?customer prod:customerName ?customerName .
  ?order prod:totalAmount ?totalAmount .
}
ORDER BY DESC(?totalAmount)
```

**SPARQL Query (without PREFIX - using full URIs)**:
```sparql
SELECT ?order ?customer ?customerName ?totalAmount
WHERE {
  ?order <http://www.example.org/products#orderedBy> ?customer .
  ?customer <http://www.example.org/products#customerName> ?customerName .
  ?order <http://www.example.org/products#totalAmount> ?totalAmount .
}
ORDER BY DESC(?totalAmount)
```

**Expected Result**: 
- Order001, Alice Johnson, $2499.99
- Order003, Carol White, $1929.98
- Order002, Bob Smith, $999.99

---

### TC-SPARQL-05: Property Path - Supplier to Products
**Objective**: Find all products from a specific supplier

**SPARQL Query (with PREFIX)**:
```sparql
PREFIX prod: <http://www.example.org/products#>

SELECT ?product ?name ?supplier
WHERE {
  ?product prod:suppliedBy ?supplier .
  ?product prod:productName ?name .
  ?supplier prod:supplierName "Apple Inc." .
}
```

**SPARQL Query (without PREFIX - using full URIs)**:
```sparql
SELECT ?product ?name ?supplier
WHERE {
  ?product <http://www.example.org/products#suppliedBy> ?supplier .
  ?product <http://www.example.org/products#productName> ?name .
  ?supplier <http://www.example.org/products#supplierName> "Apple Inc." .
}
```

**Expected Result**: 
- MacBookPro, iPhone14 (both supplied by Apple Inc.)

---

### TC-SPARQL-06: OPTIONAL - Products with/without Stock
**Objective**: Test OPTIONAL clause

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?product ?name ?stock
WHERE {
  ?product <http://www.example.org/products#productName> ?name .
  OPTIONAL { ?product <http://www.example.org/products#stockQuantity> ?stock }
}
```

**Expected Result**: 
- All products with stock quantities displayed

---

### TC-SPARQL-07: FILTER with String Operations
**Objective**: Search products by name pattern

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?product ?name
WHERE {
  ?product <http://www.example.org/products#productName> ?name .
  FILTER (CONTAINS(LCASE(?name), "pro"))
}
```

**Expected Result**: 
- MacBook Pro 16"
- iPhone 14 Pro

---

### TC-SPARQL-08: Aggregation - Average Price
**Objective**: Calculate average price by product type

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?type (AVG(?price) AS ?avgPrice)
WHERE {
  ?product a ?type .
  ?product <http://www.example.org/products#price> ?price .
}
GROUP BY ?type
HAVING (COUNT(?product) > 1)
```

**Expected Result**: 
- Laptop avg: $2199.99
- Smartphone avg: $949.99

---

### TC-SPARQL-09: UNION - Multiple Product Types
**Objective**: Query electronics OR clothing

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT DISTINCT ?product ?name
WHERE {
  ?product <http://www.example.org/products#productName> ?name .
  { ?product a <http://www.example.org/products#Electronics> }
  UNION
  { ?product a <http://www.example.org/products#Clothing> }
}
```

**Expected Result**: 
- All electronics and clothing items

---

### TC-SPARQL-10: Subquery - High-Value Orders
**Objective**: Find customers with orders over $1500

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?customer ?customerName
WHERE {
  ?customer <http://www.example.org/products#customerName> ?customerName .
  ?order <http://www.example.org/products#orderedBy> ?customer .
  ?order <http://www.example.org/products#totalAmount> ?amount .
  FILTER (?amount > 1500)
}
```

**Expected Result**: 
- Alice Johnson (Order001: $2499.99)
- Carol White (Order003: $1929.98)

---

## Advanced SPARQL Test Cases

### TC-SPARQL-11: CONSTRUCT - Create New Triples
**Objective**: Generate inferred triples

**SPARQL Query (simple - no PREFIX)**:
```sparql
CONSTRUCT {
  ?customer <http://www.example.org/products#purchasedProduct> ?product
}
WHERE {
  ?order <http://www.example.org/products#orderedBy> ?customer .
  ?order <http://www.example.org/products#contains> ?product .
}
```

**Expected Result**: 
- New triples linking customers directly to products

---

### TC-SPARQL-12: ASK - Boolean Query
**Objective**: Check if condition exists

**SPARQL Query (simple - no PREFIX)**:
```sparql
ASK {
  ?product <http://www.example.org/products#price> ?price .
  FILTER (?price > 2000)
}
```

**Expected Result**: 
- TRUE (MacBookPro costs $2499.99)

---

### TC-SPARQL-13: DESCRIBE - Get All Properties
**Objective**: Retrieve all information about an entity

**SPARQL Query (simple - no PREFIX)**:
```sparql
DESCRIBE <http://www.example.org/products#MacBookPro>
```

**Expected Result**: 
- All triples where MacBookPro is subject or object

---

### TC-SPARQL-14: Property Paths - Transitive Closure
**Objective**: Find all subclasses

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?subclass
WHERE {
  ?subclass <http://www.w3.org/2000/01/rdf-schema#subClassOf>+ <http://www.example.org/products#Product> .
}
```

**Expected Result**: 
- Electronics, Laptop, Smartphone, Clothing

---

### TC-SPARQL-15: BIND - Variable Assignment
**Objective**: Calculate discounted price

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?product ?name ?price ?discountedPrice
WHERE {
  ?product <http://www.example.org/products#productName> ?name .
  ?product <http://www.example.org/products#price> ?price .
  BIND(?price * 0.9 AS ?discountedPrice)
}
```

**Expected Result**: 
- All products with 10% discount applied

---

### TC-SPARQL-16: EXISTS - Products with Reviews
**Objective**: Filter products that have ratings

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?product ?name ?rating
WHERE {
  ?product <http://www.example.org/products#productName> ?name .
  FILTER EXISTS { ?product <http://www.example.org/products#rating> ?rating }
}
```

**Expected Result**: 
- All 5 products (all have ratings)

---

### TC-SPARQL-17: NOT EXISTS - Find Gaps
**Objective**: Find customers without orders

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?customer ?name
WHERE {
  ?customer a <http://www.example.org/products#Customer> .
  ?customer <http://www.example.org/products#customerName> ?name .
  FILTER NOT EXISTS { ?order <http://www.example.org/products#orderedBy> ?customer }
}
```

**Expected Result**: 
- Empty set (all customers have orders in test data)

---

### TC-SPARQL-18: VALUES - IN Clause Alternative
**Objective**: Query specific products

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?product ?price
WHERE {
  VALUES ?product { <http://www.example.org/products#MacBookPro> <http://www.example.org/products#iPhone14> }
  ?product <http://www.example.org/products#price> ?price .
}
```

**Expected Result**: 
- MacBookPro: $2499.99
- iPhone14: $999.99

---

### TC-SPARQL-19: SERVICE - Federated Query
**Objective**: Query external SPARQL endpoint (if available)

**SPARQL Query**:
```sparql
PREFIX : <http://www.example.org/products#>

SELECT ?product ?name
WHERE {
  ?product :productName ?name .
  SERVICE <http://external-endpoint.example.org/sparql> {
    ?product :externalProperty ?value .
  }
}
```

**Expected Result**: 
- Integration with external data sources

---

### TC-SPARQL-20: MINUS - Exclusion
**Objective**: Find laptops not from Apple

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?laptop ?name
WHERE {
  ?laptop a <http://www.example.org/products#Laptop> .
  ?laptop <http://www.example.org/products#productName> ?name .
  MINUS {
    ?laptop <http://www.example.org/products#suppliedBy> <http://www.example.org/products#AppleInc> .
  }
}
```

**Expected Result**: 
- DellXPS15

---

## SPARQL Update Test Cases (INSERT/DELETE)

### TC-SPARQL-21: INSERT DATA
**Objective**: Add new product

**SPARQL Update (simple - no PREFIX)**:
```sparql
INSERT DATA {
  <http://www.example.org/products#LenovoThinkPad> a <http://www.example.org/products#Laptop> .
  <http://www.example.org/products#LenovoThinkPad> <http://www.example.org/products#productName> "Lenovo ThinkPad X1" .
  <http://www.example.org/products#LenovoThinkPad> <http://www.example.org/products#price> 1599.99 .
  <http://www.example.org/products#LenovoThinkPad> <http://www.example.org/products#stockQuantity> 30 .
}
```

**Expected Result**: 
- New product added successfully

---

### TC-SPARQL-22: DELETE WHERE
**Objective**: Remove out-of-stock products

**SPARQL Update (simple - no PREFIX)**:
```sparql
DELETE WHERE {
  ?product <http://www.example.org/products#stockQuantity> ?qty .
  FILTER (?qty = 0)
}
```

**Expected Result**: 
- Products with zero stock removed

---

### TC-SPARQL-23: DELETE/INSERT - Update Price
**Objective**: Apply price increase

**SPARQL Update (simple - no PREFIX)**:
```sparql
DELETE { ?product <http://www.example.org/products#price> ?oldPrice }
INSERT { ?product <http://www.example.org/products#price> ?newPrice }
WHERE {
  ?product <http://www.example.org/products#price> ?oldPrice .
  BIND(?oldPrice * 1.05 AS ?newPrice)
}
```

**Expected Result**: 
- All prices increased by 5%

---

## Performance Test Cases

### PERF-SPARQL-01: Large Result Set
**Objective**: Query with 10,000+ results

**SPARQL Query**:
```sparql
PREFIX : <http://www.example.org/products#>

SELECT ?s ?p ?o
WHERE {
  ?s ?p ?o .
}
LIMIT 10000
```

**Expected Result**: 
- Query completes in < 5 seconds

---

### PERF-SPARQL-02: Complex JOIN
**Objective**: Multi-way join performance

**SPARQL Query (simple - no PREFIX)**:
```sparql
SELECT ?order ?customer ?product ?supplier
WHERE {
  ?order <http://www.example.org/products#orderedBy> ?customer .
  ?order <http://www.example.org/products#contains> ?product .
  ?product <http://www.example.org/products#suppliedBy> ?supplier .
  ?customer <http://www.example.org/products#customerName> ?name .
  ?supplier <http://www.example.org/products#supplierName> ?sname .
}
```

**Expected Result**: 
- Query optimized and returns results quickly

---

## SPARQL Plugin UI Test Cases

### UI-SPARQL-01: Query Editor
**Test**: Type and execute query
1. Open SPARQL plugin
2. Type query in editor
3. Click "Execute Query"
4. View results in table

**Expected**: Query executes, results displayed

---

### UI-SPARQL-02: Syntax Highlighting
**Test**: Verify syntax coloring
1. Type SPARQL keywords (SELECT, WHERE, FILTER)
2. Observe color highlighting

**Expected**: Keywords, prefixes, variables colored differently

---

### UI-SPARQL-03: Auto-complete
**Test**: Entity suggestion
1. Type PREFIX declaration
2. Type "?product rdf:type :"
3. Observe class suggestions

**Expected**: Auto-complete suggests Product, Electronics, etc.

---

### UI-SPARQL-04: Result Export
**Test**: Export query results
1. Execute query with results
2. Click "Export" button
3. Choose CSV format

**Expected**: CSV file downloaded with results

---

### UI-SPARQL-05: Query History
**Test**: View previous queries
1. Execute multiple queries
2. Click "History" tab
3. Select previous query

**Expected**: Query loaded into editor

---

### UI-SPARQL-06: Query Templates
**Test**: Use predefined templates
1. Click "Templates" button
2. Select "Select All Triples"
3. Template loads in editor

**Expected**: Common query patterns available

---

## Error Handling Test Cases

### ERR-SPARQL-01: Syntax Error
**Test**: Invalid SPARQL syntax
```sparql
SELECT ?x WHERE { ?x rdf:type
```
**Expected**: Clear syntax error message

---

### ERR-SPARQL-02: Undefined Prefix
**Test**: Missing prefix declaration
```sparql
SELECT ?x WHERE { ?x rdf:type :Product }
```
**Expected**: Error about undefined prefix

---

### ERR-SPARQL-03: Type Mismatch
**Test**: Filter on wrong data type
```sparql
FILTER (?price < "invalid")
```
**Expected**: Type error reported

---

## Integration Test Cases

### INT-SPARQL-01: GraphDB Integration
**Test**: Execute on GraphDB backend
1. Connect to GraphDB
2. Execute SPARQL query
3. Verify results match

**Expected**: Seamless integration

---

### INT-SPARQL-02: Multiple Endpoints
**Test**: Switch between endpoints
1. Query endpoint A
2. Switch to endpoint B
3. Execute same query

**Expected**: Results differ appropriately

---

## Test Execution Checklist

- [ ] Load test-sparql-ontology.owl
- [ ] Execute TC-SPARQL-01 through TC-SPARQL-20
- [ ] Test UPDATE queries (21-23)
- [ ] Run performance tests
- [ ] Verify UI functionality
- [ ] Test error handling
- [ ] Export/import results
- [ ] Check query history

---

## Known Limitations

1. Very large result sets (>100k) may cause browser slowdown
2. Complex federated queries may timeout
3. UPDATE queries require write permissions
4. Some SPARQL 1.1 features may not be supported

---

## References

- **SPARQL 1.1 Specification**: https://www.w3.org/TR/sparql11-query/
- **SPARQL 1.1 Update**: https://www.w3.org/TR/sparql11-update/
- **GraphDB SPARQL**: https://graphdb.ontotext.com/documentation/
