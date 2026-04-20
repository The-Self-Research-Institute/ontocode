# Fuzzy Ontology Plugin - Testing Document

**Plugin:** fuzzy-ontology-plugin v1.0.0  
**Categories:** Ontology, Reasoning, Visualization  
**Last Updated:** April 2026

---

## Table of Contents
1. [Overview](#overview)
2. [Test Environment Setup](#test-environment-setup)
3. [Membership Function Tests](#membership-function-tests)
4. [Fuzzy Reasoning Tests](#fuzzy-reasoning-tests)
5. [Fuzzy Query DSL Tests](#fuzzy-query-dsl-tests)
6. [Visualization Tests](#visualization-tests)
7. [Integration Tests](#integration-tests)
8. [Edge Case Tests](#edge-case-tests)

---

## Overview

The Fuzzy Ontology Plugin provides advanced fuzzy ontology support with membership degrees, fuzzy reasoning (T-norms, T-conorms), interactive visualization, and a custom query DSL — capabilities beyond standard Protégé.

### Key Components Under Test
- `FuzzyEditor.tsx` — Main editor with tabs for memberships, rules, queries
- `FuzzyEditorEnhanced.tsx` — Advanced features
- `MembershipFunctionCanvas.tsx` — Visual membership function rendering

### Key Services Under Test
- `FuzzyLogic.ts` — Core fuzzy engine (T-norms, T-conorms, negation)
- `FuzzyOntology.ts` — Fuzzy concept management
- `FuzzyReasoner.ts` — Inference engine (subsumption, equivalence)
- Fuzzy Query DSL — SQL-like fuzzy queries
- Visualization modules — Heatmaps, hierarchy trees, radar charts

### API Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/sparql/query/{projectId}` | Execute fuzzy queries |

---

## Test Environment Setup

### Prerequisites
- OntoCode platform running
- GraphDB with ontology containing individuals with data properties
- Sample fuzzy membership definitions

### Test Domain: Healthcare Ontology
Classes: `Patient`, `Diabetic`, `Hypertensive`, `HighRisk`, `Obese`  
Data Properties: `bloodPressure`, `glucoseLevel`, `bmi`, `age`  
Individuals: Patient001–Patient010 with various health metrics

---

## Membership Function Tests

### TC-FZ-001: Triangular Membership Function
**Objective:** Verify triangular membership function computes correct degrees  
**Steps:**
1. Open Fuzzy Editor
2. Create a fuzzy class "Moderate" with triangular function (a=20, b=50, c=80)
3. Test with values: 20, 35, 50, 65, 80, 90

**Expected Results:**
| Input | Membership Degree |
|-------|-------------------|
| 20 | 0.0 |
| 35 | 0.5 |
| 50 | 1.0 |
| 65 | 0.5 |
| 80 | 0.0 |
| 90 | 0.0 |

---

### TC-FZ-002: Trapezoidal Membership Function
**Objective:** Verify trapezoidal function with flat top  
**Steps:**
1. Create fuzzy class "Normal" with trapezoidal function (a=60, b=80, c=120, d=140)
2. Test with values: 50, 70, 100, 130, 150

**Expected Results:**
| Input | Membership Degree |
|-------|-------------------|
| 50 | 0.0 |
| 70 | 0.5 |
| 100 | 1.0 |
| 130 | 0.5 |
| 150 | 0.0 |

---

### TC-FZ-003: Gaussian Membership Function
**Objective:** Verify Gaussian bell curve membership  
**Steps:**
1. Create fuzzy class with Gaussian function (mean=100, sigma=20)
2. Test with values: 60, 80, 100, 120, 140

**Expected Results:**
- Value 100: degree = 1.0 (peak)
- Values at ±1σ (80, 120): degree ≈ 0.607
- Values at ±2σ (60, 140): degree ≈ 0.135
- Symmetric distribution

---

### TC-FZ-004: Sigmoid Membership Function
**Objective:** Verify sigmoid (S-shaped) transition  
**Steps:**
1. Create fuzzy class "High" with sigmoid function (center=120, slope=0.1)
2. Test with values: 80, 100, 120, 140, 160

**Expected Results:**
- Values well below center: degree ≈ 0.0
- Value at center (120): degree = 0.5
- Values well above center: degree ≈ 1.0
- Smooth S-shaped transition

---

### TC-FZ-005: Bell-Shaped Membership Function
**Objective:** Verify generalized bell function  
**Steps:**
1. Create fuzzy class with bell function (a=20, b=4, c=100)
2. Verify bell shape centered at c=100

**Expected Results:**
- Peak at c=100 with degree = 1.0
- Width controlled by parameter a
- Slope controlled by parameter b
- Symmetric around center

---

### TC-FZ-006: Add Membership to Entity
**Objective:** Verify assigning membership degree to an individual  
**Steps:**
1. Select Membership tab in Fuzzy Editor
2. Enter entity: `Patient001`
3. Select fuzzy class: `Diabetic`
4. Set degree: `0.85`
5. Save

**Expected Results:**
- Membership saved successfully
- Entity listed in membership table
- Degree displayed correctly
- Can edit/delete membership

---

## Fuzzy Reasoning Tests

### TC-FZ-007: T-Norm — Product (Fuzzy AND)
**Objective:** Verify Product T-norm computation  
**Test Data:** Patient with Diabetic(0.8) AND Hypertensive(0.6)  
**Expected Result:** 0.8 × 0.6 = **0.48**

---

### TC-FZ-008: T-Norm — Gödel/Min (Fuzzy AND)
**Objective:** Verify Gödel (minimum) T-norm  
**Test Data:** Patient with Diabetic(0.8) AND Hypertensive(0.6)  
**Expected Result:** min(0.8, 0.6) = **0.6**

---

### TC-FZ-009: T-Norm — Łukasiewicz (Fuzzy AND)
**Objective:** Verify Łukasiewicz T-norm  
**Test Data:** Patient with Diabetic(0.8) AND Hypertensive(0.6)  
**Expected Result:** max(0.8 + 0.6 - 1, 0) = **0.4**

---

### TC-FZ-010: T-CoNorm — Probabilistic (Fuzzy OR)
**Objective:** Verify probabilistic T-conorm  
**Test Data:** Patient with Diabetic(0.8) OR Hypertensive(0.6)  
**Expected Result:** 0.8 + 0.6 - (0.8 × 0.6) = **0.92**

---

### TC-FZ-011: T-CoNorm — Gödel/Max (Fuzzy OR)
**Objective:** Verify Gödel (maximum) T-conorm  
**Test Data:** Patient with Diabetic(0.8) OR Hypertensive(0.6)  
**Expected Result:** max(0.8, 0.6) = **0.8**

---

### TC-FZ-012: T-CoNorm — Łukasiewicz (Fuzzy OR)
**Objective:** Verify Łukasiewicz T-conorm  
**Test Data:** Patient with Diabetic(0.8) OR Hypertensive(0.6)  
**Expected Result:** min(0.8 + 0.6, 1) = **1.0**

---

### TC-FZ-013: Fuzzy Negation — Standard
**Objective:** Verify standard fuzzy negation (1 - x)  
**Test Data:** Diabetic(0.8)  
**Expected Result:** NOT Diabetic = 1 - 0.8 = **0.2**

---

### TC-FZ-014: Fuzzy Negation — Sugeno
**Objective:** Verify Sugeno negation  
**Test Data:** Degree 0.8, parameter λ=2  
**Expected Result:** (1 - 0.8) / (1 + 2 × 0.8) = 0.2 / 2.6 ≈ **0.077**

---

### TC-FZ-015: Subsumption Checking
**Objective:** Verify fuzzy subsumption with degrees  
**Steps:**
1. Define fuzzy concepts: Diabetic ⊑_d Chronic (with degree d)
2. Call `checkSubsumption("Diabetic", "Chronic")`
3. Verify subsumption degree returned

**Expected Results:**
- Subsumption relation detected
- Degree value between 0 and 1
- Reasoning trace available

---

### TC-FZ-016: Equivalence Checking
**Objective:** Verify fuzzy concept equivalence  
**Steps:**
1. Define two concepts with overlapping membership
2. Call `checkEquivalence("ConceptA", "ConceptB")`

**Expected Results:**
- Returns equivalence degree
- Degree = 1.0 for truly equivalent concepts
- Degree < 1.0 for partially overlapping concepts
- Reasoning trace shows steps

---

### TC-FZ-017: Alpha-Cut Reasoning
**Objective:** Verify alpha-cut based filtering  
**Steps:**
1. Set alpha threshold to 0.7
2. Query fuzzy class "Diabetic"
3. Only individuals with membership ≥ 0.7 returned

**Expected Results:**
- Individuals below alpha threshold excluded
- Those at or above threshold included
- Different alpha values produce different result sets

---

### TC-FZ-018: Fuzzy Rule Inference
**Objective:** Verify fuzzy rule creates new memberships  
**Rule:** IF Diabetic(x) ≥ 0.7 AND BMI(x) > 30 THEN HighRisk(x) = min(Diabetic(x), Obese(x))  
**Steps:**
1. Create rule in Fuzzy Editor rules tab
2. Define condition and action
3. Execute rule

**Expected Results:**
- Matching individuals get HighRisk membership
- Degree computed from rule formula
- New memberships visible in membership table

---

## Fuzzy Query DSL Tests

### TC-FZ-019: Basic Membership Query
**Query:**
```sql
FIND individuals WHERE memberOf(Diabetic) >= 0.8
```
**Expected Results:**
- Only individuals with Diabetic membership ≥ 0.8 returned
- Results show entity IRI and membership degree

---

### TC-FZ-020: Compound Condition Query (AND)
**Query:**
```sql
FIND individuals WHERE memberOf(Diabetic AND Hypertensive) > 0.6
```
**Expected Results:**
- Fuzzy AND (T-norm) applied between memberships
- Only combined degree > 0.6 returned
- T-norm type respects current configuration

---

### TC-FZ-021: Top-N Query with Ordering
**Query:**
```sql
SELECT TOP 10 FROM Patient ORDER BY memberOf(HighRisk) DESC
```
**Expected Results:**
- Returns maximum 10 patients
- Ordered by HighRisk membership degree descending
- Highest risk patients listed first

---

### TC-FZ-022: Existential Query
**Query:**
```sql
FIND individuals WHERE exists(hasDiagnosis, DiabetesMellitus) > 0.7
```
**Expected Results:**
- Checks existence of relationship with fuzzy degree
- Returns individuals matching threshold
- Correct handling of object property relationships

---

### TC-FZ-023: Query Error Handling
**Objective:** Verify graceful handling of malformed queries  
**Test Queries:**
```sql
FIND WHERE memberOf()
FIND individuals WHERE memberOf(NonExistentClass) > 0.5
SELECT TOP -5 FROM Patient
```
**Expected Results:**
- Syntax error message for malformed queries
- "Class not found" for unknown classes
- Validation error for invalid parameters
- No crashes or unhandled exceptions

---

### TC-FZ-024: Query output formats
**Objective:** Verify query results in all supported formats  
**Steps:**
1. Run a valid fuzzy query
2. Switch output format: Table → JSON → CSV → HTML

**Expected Results:**
- **Table:** Sortable columns with entity, class, degree
- **JSON:** Well-formed JSON with all result fields
- **CSV:** Downloadable CSV with headers
- **HTML:** Rendered HTML table

---

## Visualization Tests

### TC-FZ-025: Membership Function Canvas
**Objective:** Verify visual rendering of membership functions  
**Steps:**
1. Open MembershipFunctionCanvas
2. Plot triangular, trapezoidal, Gaussian functions
3. Overlay multiple functions

**Expected Results:**
- X-axis: input domain range
- Y-axis: membership degree (0 to 1)
- Lines/curves match mathematical definitions
- Multiple functions distinguishable by color
- Interactive tooltips showing exact values

---

### TC-FZ-026: Heatmap Visualization
**Objective:** Verify membership heatmap display  
**Steps:**
1. Select Heatmap view
2. Rows: individuals, Columns: fuzzy classes
3. Cell color intensity = membership degree

**Expected Results:**
- Color gradient from white (0.0) to dark (1.0)
- Hover shows exact degree value
- Rows/columns sortable
- Legend displayed

---

### TC-FZ-027: Hierarchy Tree Visualization
**Objective:** Verify fuzzy class hierarchy tree  
**Steps:**
1. Select Hierarchy Tree view
2. Verify parent-child relationships displayed
3. Verify subsumption degrees on edges

**Expected Results:**
- Tree structure matches fuzzy concept hierarchy
- Edge labels show subsumption degrees
- Expandable/collapsible nodes
- Node color encodes membership strength

---

### TC-FZ-028: Radar Chart Visualization
**Objective:** Verify radar chart for individual's membership profile  
**Steps:**
1. Select an individual
2. Open Radar Chart view
3. Each axis = fuzzy class membership

**Expected Results:**
- Axes labeled with fuzzy class names
- Polygon shape shows membership profile
- Values range 0.0 to 1.0
- Multiple individuals can overlay for comparison

---

## Integration Tests

### TC-FZ-029: Fuzzy ↔ Ontology Editor Sync
**Objective:** Verify fuzzy memberships persist across sessions  
**Steps:**
1. Create fuzzy memberships for several individuals
2. Save and close the editor
3. Reopen the ontology
4. Open Fuzzy Editor

**Expected Results:**
- All memberships restored
- Degrees unchanged
- Rules preserved

---

### TC-FZ-030: Fuzzy ↔ Reasoner Integration
**Objective:** Verify standard reasoner can coexist with fuzzy reasoning  
**Steps:**
1. Run fuzzy reasoning
2. Run standard OWL reasoner (HermiT)
3. Verify no conflicts

**Expected Results:**
- Fuzzy results preserved after standard reasoning
- Standard inferences don't override fuzzy membership
- Both result sets accessible

---

## Edge Case Tests

### TC-FZ-031: Boundary Membership Values
**Test Data:** Degrees of exactly 0.0, 0.5, 1.0  
**Expected:** All stored and displayed correctly without rounding errors

### TC-FZ-032: Empty Fuzzy Class
**Test:** Query a fuzzy class with no members  
**Expected:** Empty result set, no errors

### TC-FZ-033: Conflicting Memberships
**Test:** Assign same individual to contradictory classes (Hot=0.9, Cold=0.8)  
**Expected:** Both memberships stored; flagged as potential inconsistency

### TC-FZ-034: Very Small Degrees
**Test:** Membership degree of 0.001  
**Expected:** Stored accurately, displayed in scientific notation if needed

---

## Test Summary Matrix

| Test ID | Category | Priority | Status |
|---------|----------|----------|--------|
| TC-FZ-001 | Membership Functions | P0 | ☐ |
| TC-FZ-002 | Membership Functions | P0 | ☐ |
| TC-FZ-003 | Membership Functions | P1 | ☐ |
| TC-FZ-004 | Membership Functions | P1 | ☐ |
| TC-FZ-005 | Membership Functions | P2 | ☐ |
| TC-FZ-006 | Membership CRUD | P0 | ☐ |
| TC-FZ-007 | T-Norm | P0 | ☐ |
| TC-FZ-008 | T-Norm | P0 | ☐ |
| TC-FZ-009 | T-Norm | P1 | ☐ |
| TC-FZ-010 | T-CoNorm | P0 | ☐ |
| TC-FZ-011 | T-CoNorm | P0 | ☐ |
| TC-FZ-012 | T-CoNorm | P1 | ☐ |
| TC-FZ-013 | Negation | P1 | ☐ |
| TC-FZ-014 | Negation | P2 | ☐ |
| TC-FZ-015 | Reasoning | P0 | ☐ |
| TC-FZ-016 | Reasoning | P1 | ☐ |
| TC-FZ-017 | Reasoning | P1 | ☐ |
| TC-FZ-018 | Reasoning | P0 | ☐ |
| TC-FZ-019 | Query DSL | P0 | ☐ |
| TC-FZ-020 | Query DSL | P0 | ☐ |
| TC-FZ-021 | Query DSL | P1 | ☐ |
| TC-FZ-022 | Query DSL | P1 | ☐ |
| TC-FZ-023 | Query Errors | P0 | ☐ |
| TC-FZ-024 | Query Output | P1 | ☐ |
| TC-FZ-025 | Visualization | P1 | ☐ |
| TC-FZ-026 | Visualization | P1 | ☐ |
| TC-FZ-027 | Visualization | P2 | ☐ |
| TC-FZ-028 | Visualization | P2 | ☐ |
| TC-FZ-029 | Integration | P0 | ☐ |
| TC-FZ-030 | Integration | P1 | ☐ |
| TC-FZ-031 | Edge Cases | P1 | ☐ |
| TC-FZ-032 | Edge Cases | P2 | ☐ |
| TC-FZ-033 | Edge Cases | P2 | ☐ |
| TC-FZ-034 | Edge Cases | P2 | ☐ |
