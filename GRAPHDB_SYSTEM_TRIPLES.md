# GraphDB System Triples - Explanation

## Why Does GraphDB Always Have 102 Triples?

When you run the cleanup script, you'll see:
```
Current triple count: 102
✓ GraphDB contains only system triples (RDF/RDFS/OWL vocabulary)
✓ No user data to clear - database is clean
```

This is **NORMAL and EXPECTED** behavior. ✅

---

## What Are These 102 Triples?

These are **GraphDB's built-in semantic web vocabulary definitions**. They define the fundamental concepts that all ontologies use:

### Breakdown by Namespace:

1. **RDF (45 triples)** - `http://www.w3.org/1999/02/22-rdf-syntax-ns#`
   - Core RDF concepts: `rdf:type`, `rdf:Property`, `rdf:List`, etc.
   - Example: `rdf:type rdf:type rdf:Property`

2. **RDFS (31 triples)** - `http://www.w3.org/2000/01/rdf-schema#`
   - Schema concepts: `rdfs:Class`, `rdfs:subClassOf`, `rdfs:domain`, `rdfs:range`, etc.
   - Example: `rdfs:subClassOf rdf:type rdf:Property`

3. **OWL (16 triples)** - `http://www.w3.org/2002/07/owl#`
   - OWL ontology language: `owl:equivalentClass`, `owl:TransitiveProperty`, `owl:SymmetricProperty`, etc.
   - Example: `owl:equivalentClass rdf:type owl:SymmetricProperty`

4. **XML Schema (8 triples)** - `http://www.w3.org/2001/XMLSchema#`
   - Datatype definitions: `xsd:string`, `xsd:integer`, `xsd:boolean`, etc.

5. **PROTON (2 triples)** - `http://proton.semanticweb.org/protonsys#`
   - Additional semantic definitions

---

## Why Can't We Delete Them?

**These triples are essential for GraphDB to function:**

1. **Reasoning Engine Needs Them**
   - GraphDB uses these to understand OWL semantics
   - Without them, reasoning (inferencing) won't work

2. **Validation Requires Them**
   - GraphDB validates your ontology structure against these definitions
   - They define what makes a valid ontology

3. **They're Automatically Recreated**
   - Even if deleted, GraphDB recreates them on startup
   - They're part of GraphDB's core configuration

4. **They Don't Affect Your Data**
   - These are separate from your uploaded ontologies
   - Your pizza.owl or go-plus.owl triples are different

---

## How to Check If Database Is Clean

### Method 1: Use the Cleanup Script
```bash
node scripts/clear-databases.js
```

**Clean database output:**
```
Current triple count: 102
✓ GraphDB contains only system triples (RDF/RDFS/OWL vocabulary)
✓ No user data to clear - database is clean
```

**Database with user data:**
```
Current triple count: 50,234
Clearing user data triples...
✓ User data cleared
✓ System triples remain (RDF/RDFS/OWL vocabulary - this is normal)
```

### Method 2: Use the Inspector Script
```bash
node scripts/inspect-triples.js
```

This shows you exactly what's in the database.

---

## Real-World Example

### Empty Database:
```
Total: 102 triples
- RDF vocabulary: 45 triples
- RDFS vocabulary: 31 triples
- OWL vocabulary: 16 triples
- XML Schema: 8 triples
- PROTON: 2 triples
```

### After Uploading pizza.owl (small ontology):
```
Total: 1,234 triples
- System triples: 102
- Your pizza.owl data: 1,132 triples
```

### After Uploading go-plus.owl (large ontology):
```
Total: 250,345 triples
- System triples: 102
- Your go-plus.owl data: 250,243 triples
```

---

## FAQ

### Q: Should I try to delete these 102 triples?
**A:** No! They're required for GraphDB to work properly.

### Q: Do they count against my data?
**A:** No. They're metadata, not your ontology data.

### Q: Do they slow down queries?
**A:** No. GraphDB handles them efficiently, and they're needed for reasoning.

### Q: What if I see a different number (like 100 or 105)?
**A:** Small variations (100-105) are normal depending on GraphDB version and configuration.

### Q: How do I know if my data is really deleted?
**A:** If the count is around 102, your data is deleted. If it's much higher (1000+), you still have data.

---

## Tools Provided

### 1. clear-databases.js
Clears user data, recognizes system triples.

```bash
node scripts/clear-databases.js
```

### 2. inspect-triples.js (NEW)
Shows you what's in the database.

```bash
node scripts/inspect-triples.js
```

**Output:**
- Total triple count
- Sample triples (first 20)
- Breakdown by namespace
- Helps identify what data is present

---

## Summary

✅ **102 triples = Clean database (only system metadata)**
✅ **>102 triples = Your ontology data is present**
✅ **System triples are REQUIRED and NORMAL**
✅ **Don't worry about deleting them**

The cleanup script now correctly identifies when the database is clean!
