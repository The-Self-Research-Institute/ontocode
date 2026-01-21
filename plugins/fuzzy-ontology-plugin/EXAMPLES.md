# Fuzzy Ontology Plugin - Test Examples (Cardiovascular Ontology)

This document provides comprehensive test cases for the Fuzzy Ontology Plugin using the cardiovascular measurement ontology with fuzzy logic extensions.

## Table of Contents
1. [Basic Fuzzy Concepts](#1-basic-fuzzy-concepts)
2. [Cardiovascular Fuzzy Ontology](#2-cardiovascular-fuzzy-ontology)
3. [Fuzzy Membership Functions](#3-fuzzy-membership-functions)
4. [Fuzzy Queries](#4-fuzzy-queries)
5. [Fuzzy Reasoning](#5-fuzzy-reasoning)
6. [T-Norms and T-Conorms](#6-t-norms-and-t-conorms)
7. [Visualization Examples](#7-visualization-examples)
8. [Advanced Scenarios](#8-advanced-scenarios)
9. [Performance Testing](#9-performance-testing)
10. [Testing Checklist](#10-testing-checklist)

---

## 1. Basic Fuzzy Concepts

### Concept 1.1: Fuzzy Blood Pressure Categories

**Crisp Definition** (Traditional):
```turtle
:HighBloodPressure owl:equivalentClass [
    a owl:Restriction ;
    owl:onProperty :hasValue ;
    owl:someValuesFrom [
        a rdfs:Datatype ;
        owl:onDatatype xsd:double ;
        owl:withRestrictions ( [ xsd:minInclusive "140"^^xsd:double ] )
    ]
] .
```

**Fuzzy Definition**:
```turtle
:HighBloodPressure a fuzzy:FuzzyConcept ;
    rdfs:label "High Blood Pressure" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "130"^^xsd:double ;  # Start of transition
        fuzzy:b "140"^^xsd:double ;  # Full membership starts
        fuzzy:c "180"^^xsd:double ;  # Full membership ends
        fuzzy:d "200"^^xsd:double    # End of transition
    ] .
```

**Interpretation**:
- BP < 130: μ = 0.0 (definitely not high)
- BP = 135: μ = 0.5 (borderline high)
- BP = 145: μ = 1.0 (definitely high)
- BP = 190: μ = 1.0 (definitely high)
- BP > 200: μ = 0.0 (hypertensive crisis - different category)

### Concept 1.2: Fuzzy Heart Rate Categories

**Normal Heart Rate** (Fuzzy):
```turtle
:NormalHeartRate a fuzzy:FuzzyConcept ;
    rdfs:label "Normal Heart Rate" ;
    fuzzy:membershipFunction [
        a fuzzy:TriangularFunction ;
        fuzzy:a "50"^^xsd:double ;   # Left boundary
        fuzzy:b "80"^^xsd:double ;   # Peak (optimal)
        fuzzy:c "100"^^xsd:double    # Right boundary
    ] .
```

**Tachycardia** (Fuzzy):
```turtle
:Tachycardia a fuzzy:FuzzyConcept ;
    rdfs:label "Tachycardia" ;
    fuzzy:membershipFunction [
        a fuzzy:SigmoidFunction ;
        fuzzy:center "100"^^xsd:double ;
        fuzzy:slope "0.1"^^xsd:double
    ] .
```

### Concept 1.3: Fuzzy HRV Categories

**Good Autonomic Function** (High HRV):
```turtle
:GoodAutonomicFunction a fuzzy:FuzzyConcept ;
    rdfs:label "Good Autonomic Function" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "40"^^xsd:double ;
        fuzzy:b "50"^^xsd:double ;
        fuzzy:c "100"^^xsd:double ;
        fuzzy:d "120"^^xsd:double
    ] .
```

**Poor Autonomic Function** (Low HRV):
```turtle
:PoorAutonomicFunction a fuzzy:FuzzyConcept ;
    rdfs:label "Poor Autonomic Function" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "0"^^xsd:double ;
        fuzzy:b "5"^^xsd:double ;
        fuzzy:c "20"^^xsd:double ;
        fuzzy:d "30"^^xsd:double
    ] .
```

---

## 2. Cardiovascular Fuzzy Ontology

### Ontology 2.1: Complete Fuzzy Cardiovascular Model

```turtle
@prefix : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#> .
@prefix fuzzy: <http://www.fuzzyontology.org/fuzzy#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

# ========================================
# Fuzzy Concepts (Blood Pressure)
# ========================================

:LowBloodPressure a fuzzy:FuzzyConcept ;
    rdfs:label "Low Blood Pressure" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "0"^^xsd:double ;
        fuzzy:b "60"^^xsd:double ;
        fuzzy:c "80"^^xsd:double ;
        fuzzy:d "95"^^xsd:double
    ] .

:NormalBloodPressure a fuzzy:FuzzyConcept ;
    rdfs:label "Normal Blood Pressure" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "90"^^xsd:double ;
        fuzzy:b "100"^^xsd:double ;
        fuzzy:c "120"^^xsd:double ;
        fuzzy:d "125"^^xsd:double
    ] .

:ElevatedBloodPressure a fuzzy:FuzzyConcept ;
    rdfs:label "Elevated Blood Pressure" ;
    fuzzy:membershipFunction [
        a fuzzy:TriangularFunction ;
        fuzzy:a "120"^^xsd:double ;
        fuzzy:b "130"^^xsd:double ;
        fuzzy:c "140"^^xsd:double
    ] .

:HighBloodPressure a fuzzy:FuzzyConcept ;
    rdfs:label "High Blood Pressure" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "130"^^xsd:double ;
        fuzzy:b "140"^^xsd:double ;
        fuzzy:c "180"^^xsd:double ;
        fuzzy:d "200"^^xsd:double
    ] .

# ========================================
# Fuzzy Concepts (Heart Rate)
# ========================================

:Bradycardia a fuzzy:FuzzyConcept ;
    rdfs:label "Bradycardia" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "20"^^xsd:double ;
        fuzzy:b "30"^^xsd:double ;
        fuzzy:c "55"^^xsd:double ;
        fuzzy:d "65"^^xsd:double
    ] .

:NormalHeartRate a fuzzy:FuzzyConcept ;
    rdfs:label "Normal Heart Rate" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "55"^^xsd:double ;
        fuzzy:b "65"^^xsd:double ;
        fuzzy:c "90"^^xsd:double ;
        fuzzy:d "100"^^xsd:double
    ] .

:Tachycardia a fuzzy:FuzzyConcept ;
    rdfs:label "Tachycardia" ;
    fuzzy:membershipFunction [
        a fuzzy:SigmoidFunction ;
        fuzzy:center "100"^^xsd:double ;
        fuzzy:slope "0.1"^^xsd:double
    ] .

# ========================================
# Fuzzy Concepts (HRV)
# ========================================

:PoorHRV a fuzzy:FuzzyConcept ;
    rdfs:label "Poor HRV" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "0"^^xsd:double ;
        fuzzy:b "5"^^xsd:double ;
        fuzzy:c "20"^^xsd:double ;
        fuzzy:d "30"^^xsd:double
    ] .

:ModerateHRV a fuzzy:FuzzyConcept ;
    rdfs:label "Moderate HRV" ;
    fuzzy:membershipFunction [
        a fuzzy:TriangularFunction ;
        fuzzy:a "25"^^xsd:double ;
        fuzzy:b "40"^^xsd:double ;
        fuzzy:c "55"^^xsd:double
    ] .

:GoodHRV a fuzzy:FuzzyConcept ;
    rdfs:label "Good HRV" ;
    fuzzy:membershipFunction [
        a fuzzy:TrapezoidalFunction ;
        fuzzy:a "45"^^xsd:double ;
        fuzzy:b "55"^^xsd:double ;
        fuzzy:c "100"^^xsd:double ;
        fuzzy:d "120"^^xsd:double
    ] .

# ========================================
# Fuzzy Concepts (Cardiovascular Risk)
# ========================================

:LowCardiovascularRisk a fuzzy:FuzzyConcept ;
    rdfs:label "Low Cardiovascular Risk" ;
    fuzzy:membershipFunction [
        a fuzzy:CustomFunction ;
        fuzzy:definition "function(bp, hr, hrv) { 
            return (bp <= 120 && hr <= 80 && hrv >= 50) ? 1.0 : 
                   (bp <= 130 && hr <= 90 && hrv >= 40) ? 0.7 : 0.2 
        }"
    ] .

:ModerateCardiovascularRisk a fuzzy:FuzzyConcept ;
    rdfs:label "Moderate Cardiovascular Risk" .

:HighCardiovascularRisk a fuzzy:FuzzyConcept ;
    rdfs:label "High Cardiovascular Risk" .

# ========================================
# Fuzzy Individuals (Patients)
# ========================================

:Patient_Alice a fuzzy:FuzzyIndividual, :Patient ;
    rdfs:label "Alice - Healthy" ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :NormalBloodPressure ;
        fuzzy:degree "0.95"^^xsd:double ;
        fuzzy:basedOnValue "115"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :NormalHeartRate ;
        fuzzy:degree "1.0"^^xsd:double ;
        fuzzy:basedOnValue "72"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :GoodHRV ;
        fuzzy:degree "0.9"^^xsd:double ;
        fuzzy:basedOnValue "58"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :LowCardiovascularRisk ;
        fuzzy:degree "0.92"^^xsd:double
    ] .

:Patient_Bob a fuzzy:FuzzyIndividual, :Patient ;
    rdfs:label "Bob - Pre-Hypertensive" ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :NormalBloodPressure ;
        fuzzy:degree "0.2"^^xsd:double ;
        fuzzy:basedOnValue "135"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :ElevatedBloodPressure ;
        fuzzy:degree "0.5"^^xsd:double ;
        fuzzy:basedOnValue "135"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :HighBloodPressure ;
        fuzzy:degree "0.5"^^xsd:double ;
        fuzzy:basedOnValue "135"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :NormalHeartRate ;
        fuzzy:degree "0.8"^^xsd:double ;
        fuzzy:basedOnValue "82"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :ModerateHRV ;
        fuzzy:degree "0.9"^^xsd:double ;
        fuzzy:basedOnValue "38"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :ModerateCardiovascularRisk ;
        fuzzy:degree "0.75"^^xsd:double
    ] .

:Patient_Carol a fuzzy:FuzzyIndividual, :Patient ;
    rdfs:label "Carol - Hypertensive" ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :HighBloodPressure ;
        fuzzy:degree "1.0"^^xsd:double ;
        fuzzy:basedOnValue "155"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :Tachycardia ;
        fuzzy:degree "0.65"^^xsd:double ;
        fuzzy:basedOnValue "95"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :PoorHRV ;
        fuzzy:degree "0.8"^^xsd:double ;
        fuzzy:basedOnValue "22"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :HighCardiovascularRisk ;
        fuzzy:degree "0.95"^^xsd:double
    ] .

:Patient_David a fuzzy:FuzzyIndividual, :Patient ;
    rdfs:label "David - Athlete" ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :NormalBloodPressure ;
        fuzzy:degree "1.0"^^xsd:double ;
        fuzzy:basedOnValue "108"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :Bradycardia ;
        fuzzy:degree "0.7"^^xsd:double ;
        fuzzy:basedOnValue "56"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :GoodHRV ;
        fuzzy:degree "1.0"^^xsd:double ;
        fuzzy:basedOnValue "85"^^xsd:double
    ] ;
    fuzzy:hasFuzzyMembership [
        fuzzy:concept :LowCardiovascularRisk ;
        fuzzy:degree "0.98"^^xsd:double
    ] .
```

---

## 3. Fuzzy Membership Functions

### Function 3.1: Trapezoidal Membership Function

**Definition**:
```javascript
function trapezoid(x, a, b, c, d) {
    if (x <= a) return 0.0;
    if (x >= d) return 0.0;
    if (x >= b && x <= c) return 1.0;
    if (x > a && x < b) return (x - a) / (b - a);
    if (x > c && x < d) return (d - x) / (d - c);
}
```

**Test Cases**:
```javascript
// Normal Blood Pressure: (90, 100, 120, 125)
trapezoid(85, 90, 100, 120, 125)   // = 0.0 (below range)
trapezoid(95, 90, 100, 120, 125)   // = 0.5 (rising)
trapezoid(110, 90, 100, 120, 125)  // = 1.0 (peak)
trapezoid(122, 90, 100, 120, 125)  // = 0.6 (falling)
trapezoid(130, 90, 100, 120, 125)  // = 0.0 (above range)
```

### Function 3.2: Triangular Membership Function

**Definition**:
```javascript
function triangular(x, a, b, c) {
    if (x <= a || x >= c) return 0.0;
    if (x === b) return 1.0;
    if (x > a && x < b) return (x - a) / (b - a);
    if (x > b && x < c) return (c - x) / (c - b);
}
```

**Test Cases**:
```javascript
// Elevated BP: (120, 130, 140)
triangular(115, 120, 130, 140)  // = 0.0
triangular(125, 120, 130, 140)  // = 0.5
triangular(130, 120, 130, 140)  // = 1.0 (peak)
triangular(135, 120, 130, 140)  // = 0.5
triangular(145, 120, 130, 140)  // = 0.0
```

### Function 3.3: Gaussian Membership Function

**Definition**:
```javascript
function gaussian(x, mean, sigma) {
    return Math.exp(-Math.pow(x - mean, 2) / (2 * Math.pow(sigma, 2)));
}
```

**Test Cases**:
```javascript
// Optimal Heart Rate: mean=75, sigma=10
gaussian(75, 75, 10)  // = 1.0 (at mean)
gaussian(85, 75, 10)  // = 0.606 (1 sigma away)
gaussian(95, 75, 10)  // = 0.135 (2 sigma away)
gaussian(65, 75, 10)  // = 0.606
```

### Function 3.4: Sigmoid Membership Function

**Definition**:
```javascript
function sigmoid(x, center, slope) {
    return 1.0 / (1.0 + Math.exp(-slope * (x - center)));
}
```

**Test Cases**:
```javascript
// Tachycardia: center=100, slope=0.1
sigmoid(80, 100, 0.1)   // = 0.119 (low)
sigmoid(100, 100, 0.1)  // = 0.5 (center)
sigmoid(110, 100, 0.1)  // = 0.731
sigmoid(120, 100, 0.1)  // = 0.881 (high)
```

### Function 3.5: Bell-Shaped (Generalized Bell) Function

**Definition**:
```javascript
function bell(x, a, b, c) {
    return 1.0 / (1.0 + Math.pow(Math.abs((x - c) / a), 2 * b));
}
```

**Test Cases**:
```javascript
// HRV optimal range: a=20, b=2, c=70
bell(70, 20, 2, 70)  // = 1.0 (center)
bell(80, 20, 2, 70)  // = 0.8
bell(90, 20, 2, 70)  // = 0.5
bell(50, 20, 2, 70)  // = 0.8
```

---

## 4. Fuzzy Queries

### Query 4.1: Find Patients with High Blood Pressure (Fuzzy)

**Fuzzy Query DSL**:
```
FIND patients 
WHERE memberOf(patient, HighBloodPressure) > 0.7
ORDER BY degree DESC
```

**Expected Results**:
| Patient | Membership Degree | BP Value |
|---------|-------------------|----------|
| Carol | 1.0 | 155 mmHg |
| Bob | 0.5 | 135 mmHg |

### Query 4.2: Find Patients with Good HRV

**Fuzzy Query DSL**:
```
FIND patients
WHERE memberOf(patient, GoodHRV) >= 0.8
```

**Expected Results**:
| Patient | Membership Degree | HRV Value |
|---------|-------------------|-----------|
| David | 1.0 | 85 ms |
| Alice | 0.9 | 58 ms |

### Query 4.3: Top-K Query (Most Normal Heart Rate)

**Fuzzy Query DSL**:
```
FIND TOP 3 patients
WHERE memberOf(patient, NormalHeartRate) > 0
ORDER BY degree DESC
```

**Expected Results**:
| Rank | Patient | Degree | HR Value |
|------|---------|--------|----------|
| 1 | Alice | 1.0 | 72 bpm |
| 2 | Bob | 0.8 | 82 bpm |
| 3 | David | 0.0 | 56 bpm |

### Query 4.4: Complex AND Query

**Fuzzy Query DSL**:
```
FIND patients
WHERE memberOf(patient, NormalBloodPressure) > 0.5
  AND memberOf(patient, NormalHeartRate) > 0.5
```

**T-Norm**: Minimum (default)

**Expected Results**:
| Patient | BP Degree | HR Degree | Combined |
|---------|-----------|-----------|----------|
| Alice | 0.95 | 1.0 | min(0.95, 1.0) = 0.95 |

### Query 4.5: Complex OR Query

**Fuzzy Query DSL**:
```
FIND patients
WHERE memberOf(patient, HighBloodPressure) > 0.3
   OR memberOf(patient, Tachycardia) > 0.3
```

**T-Conorm**: Maximum (default)

**Expected Results**:
| Patient | High BP | Tachycardia | Combined |
|---------|---------|-------------|----------|
| Carol | 1.0 | 0.65 | max(1.0, 0.65) = 1.0 |
| Bob | 0.5 | 0.0 | max(0.5, 0.0) = 0.5 |

### Query 4.6: Negation Query

**Fuzzy Query DSL**:
```
FIND patients
WHERE NOT memberOf(patient, HighCardiovascularRisk) > 0.5
```

**Negation**: 1 - μ

**Expected Results**:
| Patient | High Risk | NOT High Risk |
|---------|-----------|---------------|
| Alice | 0.08 | 0.92 ✓ |
| Bob | 0.25 | 0.75 ✓ |
| David | 0.02 | 0.98 ✓ |
| Carol | 0.95 | 0.05 ✗ |

### Query 4.7: Existential Quantification

**Fuzzy Query DSL**:
```
FIND patients
WHERE EXISTS measurement:BloodPressure
  WITH memberOf(measurement, HighBloodPressure) > 0.8
```

**Expected**: Patients with at least one high BP reading

### Query 4.8: Universal Quantification

**Fuzzy Query DSL**:
```
FIND patients
WHERE FORALL measurement:HeartRate
  WITH memberOf(measurement, NormalHeartRate) >= 0.5
```

**Expected**: Patients where ALL heart rate measurements are normal

---

## 5. Fuzzy Reasoning

### Reasoning 5.1: Fuzzy Rule-Based Classification

**Rule**: If BP is High AND HR is High, THEN Risk is High

**Implementation**:
```javascript
function inferCardiovascularRisk(bpDegree, hrDegree, hrvDegree) {
    // T-Norm: Minimum (AND operation)
    const highRiskDegree = Math.min(bpDegree, hrDegree, 1.0 - hrvDegree);
    
    // T-Conorm: Maximum (OR operation for multiple rules)
    return highRiskDegree;
}
```

**Test Cases**:
```javascript
// Carol: BP=1.0, HR=0.65, HRV=0.2 (poor)
inferCardiovascularRisk(1.0, 0.65, 0.2)  
// = min(1.0, 0.65, 1-0.2) = min(1.0, 0.65, 0.8) = 0.65

// Alice: BP=0.05, HR=0.0, HRV=0.9 (good)
inferCardiovascularRisk(0.05, 0.0, 0.9)
// = min(0.05, 0.0, 1-0.9) = min(0.05, 0.0, 0.1) = 0.0
```

### Reasoning 5.2: Fuzzy Subsumption

**Question**: Is "Elevated Blood Pressure" a subset of "High Blood Pressure"?

**Method**: Check if μ_ElevatedBP(x) ≤ μ_HighBP(x) for all x

**Test Values**:
```javascript
x = 125: μ_Elevated = 0.5, μ_High = 0.0  // NOT subsumed (0.5 > 0.0)
x = 135: μ_Elevated = 0.5, μ_High = 0.5  // OK
x = 145: μ_Elevated = 0.0, μ_High = 1.0  // OK
```

**Conclusion**: ElevatedBP is NOT a fuzzy subset of HighBP

### Reasoning 5.3: Fuzzy Entailment

**Given**: Alice has NormalBloodPressure (μ = 0.95)

**Rule**: NormalBloodPressure → LowCardiovascularRisk

**Infer**: Alice has LowCardiovascularRisk with degree ≥ 0.95

**Verification**: Alice's LowRisk degree = 0.92 (close, accounting for HR and HRV)

---

## 6. T-Norms and T-Conorms

### T-Norm 6.1: Minimum (Gödel)

**Definition**: `T(a, b) = min(a, b)`

**Test Cases**:
```javascript
min(0.8, 0.6) = 0.6
min(1.0, 0.5) = 0.5
min(0.0, 1.0) = 0.0
```

**Use Case**: Conservative AND operation for cardiovascular risk

### T-Norm 6.2: Product (Probabilistic)

**Definition**: `T(a, b) = a × b`

**Test Cases**:
```javascript
product(0.8, 0.6) = 0.48
product(1.0, 0.5) = 0.5
product(0.9, 0.9) = 0.81
```

**Use Case**: Independent risk factors multiplication

### T-Norm 6.3: Łukasiewicz

**Definition**: `T(a, b) = max(0, a + b - 1)`

**Test Cases**:
```javascript
lukasiewicz(0.8, 0.6) = max(0, 0.8 + 0.6 - 1) = 0.4
lukasiewicz(1.0, 0.5) = max(0, 1.0 + 0.5 - 1) = 0.5
lukasiewicz(0.4, 0.4) = max(0, 0.4 + 0.4 - 1) = 0.0
```

**Use Case**: Strict AND operation

### T-Conorm 6.4: Maximum

**Definition**: `S(a, b) = max(a, b)`

**Test Cases**:
```javascript
max(0.8, 0.6) = 0.8
max(1.0, 0.5) = 1.0
max(0.0, 0.3) = 0.3
```

**Use Case**: Optimistic OR operation for risk detection

### T-Conorm 6.5: Probabilistic Sum

**Definition**: `S(a, b) = a + b - (a × b)`

**Test Cases**:
```javascript
probSum(0.8, 0.6) = 0.8 + 0.6 - (0.8 × 0.6) = 0.92
probSum(1.0, 0.5) = 1.0 + 0.5 - (1.0 × 0.5) = 1.0
probSum(0.5, 0.5) = 0.5 + 0.5 - (0.5 × 0.5) = 0.75
```

**Use Case**: Independent risk factors disjunction

---

## 7. Visualization Examples

### Visualization 7.1: Membership Function Plot

**Plot**: Blood Pressure Categories

**X-axis**: Blood Pressure (mmHg) [0-200]
**Y-axis**: Membership Degree [0-1]

**Curves**:
- **Low BP** (blue): Trapezoidal (0, 60, 80, 95)
- **Normal BP** (green): Trapezoidal (90, 100, 120, 125)
- **Elevated BP** (yellow): Triangular (120, 130, 140)
- **High BP** (red): Trapezoidal (130, 140, 180, 200)

**Markers**:
- Alice (115 mmHg): Green peak
- Bob (135 mmHg): Yellow/Red overlap
- Carol (155 mmHg): Red peak

### Visualization 7.2: Fuzzy Membership Matrix

**Heatmap**: Patients × Concepts

|  | Low BP | Normal BP | Elevated BP | High BP | Low HR | Normal HR | High HR | Poor HRV | Good HRV |
|---|--------|-----------|-------------|---------|--------|-----------|---------|----------|----------|
| **Alice** | 0.0 | **0.95** | 0.0 | 0.0 | 0.0 | **1.0** | 0.0 | 0.0 | **0.9** |
| **Bob** | 0.0 | 0.2 | **0.5** | **0.5** | 0.0 | **0.8** | 0.2 | 0.1 | 0.0 |
| **Carol** | 0.0 | 0.0 | 0.0 | **1.0** | 0.0 | 0.2 | **0.65** | **0.8** | 0.0 |
| **David** | 0.0 | **1.0** | 0.0 | 0.0 | **0.7** | 0.3 | 0.0 | 0.0 | **1.0** |

**Color Scale**: 0.0 (white) → 1.0 (dark blue)

### Visualization 7.3: Fuzzy Concept Hierarchy

**Tree View**:
```
Cardiovascular Measurement
├── Blood Pressure (fuzzy)
│   ├── Low BP (μ: trapezoidal)
│   ├── Normal BP (μ: trapezoidal)
│   ├── Elevated BP (μ: triangular)
│   └── High BP (μ: trapezoidal)
├── Heart Rate (fuzzy)
│   ├── Bradycardia (μ: trapezoidal)
│   ├── Normal HR (μ: trapezoidal)
│   └── Tachycardia (μ: sigmoid)
└── HRV (fuzzy)
    ├── Poor HRV (μ: trapezoidal)
    ├── Moderate HRV (μ: triangular)
    └── Good HRV (μ: trapezoidal)
```

### Visualization 7.4: Radar Chart (Patient Profile)

**Carol's Fuzzy Profile**:
```
        High BP (1.0)
              ↑
              |
   Poor HRV   +--- Tachycardia (0.65)
     (0.8) ---+
              |
     High Risk (0.95)
```

**Interpretation**: Strong membership in all negative categories

---

## 8. Advanced Scenarios

### Scenario 8.1: Fuzzy Risk Stratification

**Objective**: Classify 100 patients into risk groups

**Input**: BP, HR, HRV measurements for each patient

**Fuzzy Rule Set**:
1. IF BP is High AND HRV is Poor THEN Risk is High (weight: 0.9)
2. IF BP is Elevated AND HR is High THEN Risk is Moderate (weight: 0.7)
3. IF BP is Normal AND HRV is Good THEN Risk is Low (weight: 0.95)

**Defuzzification**: Weighted average of rule consequents

**Output**:
- 45 patients → Low Risk (μ > 0.7)
- 35 patients → Moderate Risk (μ > 0.6)
- 20 patients → High Risk (μ > 0.8)

### Scenario 8.2: Fuzzy Temporal Pattern Detection

**Objective**: Detect declining HRV trend (fuzzy)

**Data**: 7-day HRV measurements for Bob

**Fuzzy Concepts**:
- DecliningTrend (μ based on slope)
- StableTrend (μ based on variance)
- ImprovingTrend (μ based on positive slope)

**Result**:
```
Bob's HRV Trend:
- DecliningTrend: μ = 0.75 (concerning)
- StableTrend: μ = 0.2
- ImprovingTrend: μ = 0.1

Action: Alert clinician for follow-up
```

### Scenario 8.3: Fuzzy Aggregation Across Devices

**Objective**: Combine BP readings from multiple devices with fuzzy confidence

**Devices**:
- Clinical Monitor: BP = 142 mmHg, confidence = 0.98
- Apple Watch: BP = 138 mmHg, confidence = 0.75
- Home Monitor: BP = 145 mmHg, confidence = 0.85

**Fuzzy Weighted Average**:
```javascript
aggregatedBP = (142 × 0.98 + 138 × 0.75 + 145 × 0.85) / (0.98 + 0.75 + 0.85)
            = (139.16 + 103.5 + 123.25) / 2.58
            = 141.8 mmHg

aggregatedConfidence = (0.98 + 0.75 + 0.85) / 3 = 0.86
```

**Fuzzy Classification**: HighBloodPressure with μ = 0.9

---

## 9. Performance Testing

### Test 9.1: Membership Function Evaluation Speed

**Benchmark**: Evaluate trapezoidal function 10,000 times

**Test Code**:
```javascript
const iterations = 10000;
const startTime = Date.now();

for (let i = 0; i < iterations; i++) {
    trapezoid(135, 130, 140, 180, 200);
}

const endTime = Date.now();
const avgTime = (endTime - startTime) / iterations;
```

**Expected Performance**: < 0.01 ms per evaluation

### Test 9.2: Fuzzy Query Performance

**Query**: Find patients with high cardiovascular risk

**Dataset**: 1000 patients, 5000 measurements

**Expected Time**: < 200 ms

**Optimization**: Index fuzzy membership degrees

### Test 9.3: Fuzzy Reasoning Performance

**Task**: Apply 50 fuzzy rules to 1000 patients

**Expected Time**: < 5 seconds

**Bottleneck**: T-Norm computations (optimize with parallel processing)

### Test 9.4: Visualization Rendering

**Task**: Plot 4 membership functions with 200 points each

**Expected Time**: < 100 ms

**Technology**: Canvas API or SVG

---

## 10. Testing Checklist

### Basic Fuzzy Concepts
- [ ] Define fuzzy blood pressure categories (4 categories)
- [ ] Define fuzzy heart rate categories (3 categories)
- [ ] Define fuzzy HRV categories (3 categories)
- [ ] Create 4 fuzzy patient individuals
- [ ] Assign membership degrees to all concepts

### Membership Functions
- [ ] Implement trapezoidal function (5 test cases)
- [ ] Implement triangular function (5 test cases)
- [ ] Implement Gaussian function (4 test cases)
- [ ] Implement sigmoid function (4 test cases)
- [ ] Implement bell-shaped function (4 test cases)
- [ ] Validate all functions return values in [0, 1]

### Fuzzy Queries
- [ ] Execute FIND query with membership threshold
- [ ] Execute TOP-K query (order by degree)
- [ ] Execute AND query (test min, product, Łukasiewicz)
- [ ] Execute OR query (test max, probabilistic sum)
- [ ] Execute NOT query (test complement)
- [ ] Execute EXISTS query (existential quantification)
- [ ] Execute FORALL query (universal quantification)

### Fuzzy Reasoning
- [ ] Apply fuzzy rule-based classification
- [ ] Test fuzzy subsumption (subset checking)
- [ ] Test fuzzy entailment
- [ ] Verify inference consistency

### T-Norms and T-Conorms
- [ ] Test Minimum T-Norm (3 cases)
- [ ] Test Product T-Norm (3 cases)
- [ ] Test Łukasiewicz T-Norm (3 cases)
- [ ] Test Maximum T-Conorm (3 cases)
- [ ] Test Probabilistic Sum T-Conorm (3 cases)

### Visualization
- [ ] Plot membership function curves
- [ ] Generate membership matrix heatmap
- [ ] Display concept hierarchy tree
- [ ] Create radar chart for patient profiles
- [ ] Export visualizations as PNG/SVG

### Advanced Scenarios
- [ ] Fuzzy risk stratification (100 patients)
- [ ] Temporal pattern detection (7-day trend)
- [ ] Multi-device aggregation with confidence
- [ ] Fuzzy decision making (accept/reject/review)

### Performance
- [ ] Membership function evaluation < 0.01 ms
- [ ] Fuzzy query on 1000 patients < 200 ms
- [ ] Apply 50 rules to 1000 patients < 5 sec
- [ ] Visualization rendering < 100 ms
- [ ] Memory usage reasonable (< 100 MB for 1000 patients)

### Error Handling
- [ ] Handle invalid membership function parameters
- [ ] Detect membership degrees outside [0, 1]
- [ ] Validate T-Norm/T-Conorm axioms
- [ ] Handle empty query results gracefully
- [ ] Provide clear error messages

### Integration
- [ ] Works with graph view (visualize fuzzy concepts)
- [ ] Works with SPARQL plugin (query fuzzy data)
- [ ] Works with reasoner (fuzzy inference)
- [ ] Export fuzzy ontology to OWL 2 + annotations

---

## Appendix A: Fuzzy Logic Fundamentals

### Membership Degree
- Range: [0, 1]
- 0 = definitely not a member
- 1 = definitely a member
- 0.5 = maximum uncertainty

### Fuzzy Set Operations
- **Union (OR)**: μ_A∪B(x) = max(μ_A(x), μ_B(x))
- **Intersection (AND)**: μ_A∩B(x) = min(μ_A(x), μ_B(x))
- **Complement (NOT)**: μ_¬A(x) = 1 - μ_A(x)

### Defuzzification Methods
- **Centroid**: Center of gravity of membership function
- **Bisector**: Divides area under curve in half
- **Mean of Maximum**: Average of points with max membership
- **Smallest/Largest of Maximum**: Min/max of max membership points

---

## Appendix B: Cardiovascular Fuzzy Knowledge Base

### Normal Ranges (Fuzzy)
- **Blood Pressure**: [90-125 mmHg] with peak at [100-120]
- **Heart Rate**: [55-100 bpm] with peak at [65-90]
- **HRV (SDNN)**: [45-120 ms] with peak at [55-100]

### Clinical Significance
- **Fuzzy Overlap**: Represents clinical uncertainty in borderline cases
- **Multiple Memberships**: Patient can belong to multiple categories simultaneously
- **Degree Interpretation**: Higher degree = stronger clinical evidence

---

## Appendix C: Example API Usage

### JavaScript API

```javascript
// Create fuzzy ontology
const fuzzyOntology = new FuzzyOntology();

// Define fuzzy concept
const highBP = fuzzyOntology.createConcept('HighBloodPressure', {
    membershipFunction: new TrapezoidalFunction(130, 140, 180, 200)
});

// Create fuzzy individual
const patient = fuzzyOntology.createIndividual('Patient_Alice');

// Assign membership
patient.setMembership(highBP, 0.75, { value: 145 });

// Query
const results = fuzzyOntology.query()
    .find('patients')
    .where('memberOf', highBP, '>', 0.5)
    .orderBy('degree', 'DESC')
    .execute();

// Reasoning
const riskDegree = fuzzyOntology.applyRule({
    if: { and: ['HighBloodPressure', 'Tachycardia'] },
    then: 'HighCardiovascularRisk',
    tNorm: 'minimum'
});

// Visualization
fuzzyOntology.visualize.membershipFunction(highBP, {
    xRange: [0, 200],
    xLabel: 'Blood Pressure (mmHg)',
    color: '#ff6b6b'
});
```

---

**Test Document Version**: 1.0
**Last Updated**: December 30, 2025
**Compatible With**: Cardiovascular Ontology v1.0
