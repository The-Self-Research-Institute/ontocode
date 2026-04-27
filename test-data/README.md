# Test Data — Quick Start Guide

This directory contains OWL ontology files designed for testing all 7 OntoCode plugins. Each file targets specific test scenarios documented in the `TESTING.md` files of each plugin.

---

## File Overview

| File | Domain | Classes | Individuals | Target Plugins |
|------|--------|---------|-------------|----------------|
| `test-ontology.owl` | Biology / Vehicles | 15+ | 10+ | Graph View, Change Assistant |
| `sample-ontology.owl` | Geometry (Shapes) | 5 | 2 | Graph View (simple test) |
| `test-sparql-ontology.owl` | E-Commerce | 10+ | 15+ | SPARQL Query |
| `test-swrl-ontology.owl` | University | 10+ | 10+ | SWRL Editor |
| `test-fuzzy-ontology.owl` | Healthcare | 9 | 12 | Fuzzy Ontology |
| `test-citation-ontology.owl` | Cardiovascular | 10+ | 7 | Citation |
| `consistent-ontology.owl` | Fruit taxonomy | 7 | 5 | Reasoner (should pass) |
| `inconsistent-ontology.owl` | Animals / Plants | 6+ | 3 | Reasoner (should fail) |

---

## How to Load into OntoCode / GraphDB

### Option A — Via OntoCode Web UI

1. Open the OntoCode editor at your deployment URL (e.g., `https://ontocode.selfresearch.org`)
2. In the editor panel, use **File → Import Ontology** (or the import button in the sidebar)
3. Select the `.owl` file from this directory
4. The ontology will be loaded into the active GraphDB repository

### Option B — Via GraphDB Workbench

1. Open GraphDB Workbench at `http://localhost:7200` (or your GraphDB URL)
2. Select the target repository (e.g., `ontocode`)
3. Go to **Import → Upload RDF files**
4. Upload the `.owl` file and click **Import**
5. Verify with: `SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }`

### Option C — Via SPARQL INSERT (programmatic)

```sparql
LOAD <file:///path/to/test-ontology.owl> INTO GRAPH <http://www.example.org/test>
```

---

## Plugin ↔ Test File Mapping

### 1. Graph View Plugin
- **Primary:** `test-ontology.owl` — 3-level class hierarchy (LivingThing → Animal → Mammal → Dog), disjoint classes, 10+ individuals with relationships
- **Simple test:** `sample-ontology.owl` — 5 classes (Shape → Circle, Rectangle, Triangle) for basic rendering

### 2. SPARQL Query Plugin
- **Primary:** `test-sparql-ontology.owl` — E-commerce domain with Products, Customers, Orders, Suppliers. Rich data properties (price, rating, stock) for testing SELECT, CONSTRUCT, ASK, DESCRIBE queries and aggregation functions

**Sample queries to try:**
```sparql
# All products under $500
SELECT ?name ?price WHERE {
  ?p a <http://www.example.org/ecommerce#Product> ;
     <http://www.example.org/ecommerce#productName> ?name ;
     <http://www.example.org/ecommerce#price> ?price .
  FILTER (?price < 500)
}

# Average rating by category
SELECT ?cat (AVG(?rating) AS ?avgRating) WHERE {
  ?p <http://www.example.org/ecommerce#rating> ?rating ;
     <http://www.example.org/ecommerce#belongsToCategory> ?c .
  ?c <http://www.example.org/ecommerce#categoryName> ?cat .
} GROUP BY ?cat
```

### 3. SWRL Editor Plugin
- **Primary:** `test-swrl-ontology.owl` — University domain with Students, Professors, Courses, Departments. Data properties like GPA, courseLevel for threshold-based SWRL rules

**Sample SWRL rules to try:**
```
Student(?s) ∧ gpa(?s, ?g) ∧ swrlb:greaterThan(?g, 3.5) → HonorsStudent(?s)
Student(?s) ∧ enrolledIn(?s, ?c) ∧ AdvancedCourse(?c) → GraduateStudent(?s)
Professor(?p) ∧ teaches(?p, ?c) ∧ enrolledIn(?s, ?c) → advisedBy(?s, ?p)
```

### 4. Fuzzy Ontology Plugin
- **Primary:** `test-fuzzy-ontology.owl` — Healthcare with 10 patients having varying health metrics (glucose, blood pressure, BMI). Fuzzy target classes: Diabetic, Hypertensive, Obese, HighRisk

**Fuzzy membership function suggestions:**
| Class | Property | Function | Parameters |
|-------|----------|----------|------------|
| Diabetic | glucoseLevel | Trapezoidal | (100, 126, 200, 300) |
| Hypertensive | bloodPressureSystolic | Trapezoidal | (120, 140, 180, 200) |
| Obese | bmi | Trapezoidal | (25, 30, 40, 50) |

**Patient spectrum:**
- Patient004 (David) & Patient007 (Grace): Healthy — low membership in all fuzzy classes
- Patient005 (Eve) & Patient009 (Ivy): Borderline — partial membership (~0.3–0.6)
- Patient001 (Alice) & Patient003 (Carol): High risk — high membership in multiple classes

### 5. Reasoner Plugin
- **Consistent:** `consistent-ontology.owl` — Fruit taxonomy with disjoint classes but NO contradictions. All 4 reasoner engines (HermiT, Pellet, ELK, FaCT++) should report **consistent**
- **Inconsistent:** `inconsistent-ontology.owl` — 3 deliberate contradictions:
  1. `Creature1` typed as both `Animal` AND `Plant` (disjoint classes)
  2. `DogCatHybrid` is subclass of both `Dog` and `Cat` (disjoint → unsatisfiable)
  3. Dog/Cat disjoint axiom
  
  All reasoners should report **inconsistent**

### 6. Change Assistant Plugin
- **Use any file** — load `test-ontology.owl`, then make edits (add/rename/delete classes, modify properties) and verify the change assistant tracks all modifications

### 7. Citation Plugin
- **Primary:** `test-citation-ontology.owl` — Cardiovascular domain with classes and individuals annotated with `referenceSource` pointing to BibTeX citation keys

**BibTeX entries to import:**
```bibtex
@article{whelton2018guidelines,
  author  = {Whelton, Paul K and others},
  title   = {2017 ACC/AHA Guideline for Prevention and Management of High Blood Pressure},
  journal = {Journal of the American College of Cardiology},
  year    = {2018},
  volume  = {71},
  number  = {19},
  doi     = {10.1016/j.jacc.2017.11.006}
}

@article{williams2018esc,
  author  = {Williams, Bryan and others},
  title   = {2018 ESC/ESH Guidelines for the Management of Arterial Hypertension},
  journal = {European Heart Journal},
  year    = {2018},
  volume  = {39},
  number  = {33}
}

@article{fox2007resting,
  author  = {Fox, Keith and others},
  title   = {Resting Heart Rate in Cardiovascular Disease},
  journal = {Journal of the American College of Cardiology},
  year    = {2007},
  volume  = {50},
  number  = {9}
}

@article{kligfield2007ecg,
  author  = {Kligfield, Paul and others},
  title   = {Recommendations for the Standardization of ECG},
  journal = {Circulation},
  year    = {2007},
  volume  = {115},
  number  = {10}
}

@article{january2014aha,
  author  = {January, Craig T and others},
  title   = {2014 AHA/ACC Guideline for Management of Atrial Fibrillation},
  journal = {Journal of the American College of Cardiology},
  year    = {2014},
  volume  = {64},
  number  = {21}
}
```

---

## Tips

- **Clear the repository** between test runs to avoid leftover triples:
  ```sparql
  CLEAR ALL
  ```
- **Verify load count** after importing:
  ```sparql
  SELECT (COUNT(*) AS ?triples) WHERE { ?s ?p ?o }
  ```
- **Namespace prefixes** — all test ontologies use `http://www.example.org/<domain>#` as the base namespace
- **Each OWL file is self-contained** — no external imports required
