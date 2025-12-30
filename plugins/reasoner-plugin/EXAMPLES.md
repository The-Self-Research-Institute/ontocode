# Reasoner Plugin - Test Examples (Cardiovascular Ontology)

This document provides comprehensive test cases for the Reasoner Plugin using the cardiovascular measurement ontology.

## Table of Contents
1. [Ontology Setup](#1-ontology-setup)
2. [Basic Classification Tests](#2-basic-classification-tests)
3. [Consistency Checking](#3-consistency-checking)
4. [Inference Examples](#4-inference-examples)
5. [Property Hierarchy Reasoning](#5-property-hierarchy-reasoning)
6. [Class Hierarchy Inference](#6-class-hierarchy-inference)
7. [Individual Classification](#7-individual-classification)
8. [Complex Queries](#8-complex-queries)
9. [Performance Testing](#9-performance-testing)
10. [Testing Checklist](#10-testing-checklist)

---

## 1. Ontology Setup

### Load the Cardiovascular Ontology

```turtle
@prefix : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

# Core Classes
:Measurement a owl:Class .
:Circulatory rdfs:subClassOf :Measurement .
:Cardiovascular rdfs:subClassOf :Circulatory .

:BloodPressure rdfs:subClassOf :Cardiovascular .
:HeartRate rdfs:subClassOf :Cardiovascular .
:Pulse rdfs:subClassOf :Cardiovascular .
:WalkingHeartRate rdfs:subClassOf :Cardiovascular .
:HeartRateVariability rdfs:subClassOf :Cardiovascular .

:Patient a owl:Class .
:Device a owl:Class .
:Environment a owl:Class .
:RiskLevel a owl:Class .
```

### Reasoner Configuration

**Reasoner Type**: Pellet / HermiT / ELK
**Reasoning Tasks**:
- Class classification
- Individual classification
- Consistency checking
- Property hierarchy inference

---

## 2. Basic Classification Tests

### Test 2.1: Verify Class Hierarchy

**Objective**: Ensure all cardiovascular measurement classes are properly classified

**Test Query**:
```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?class ?parent WHERE {
  ?class rdfs:subClassOf+ :Measurement .
  ?class rdfs:subClassOf ?parent .
}
ORDER BY ?class
```

**Expected Results**:
- BloodPressure → Cardiovascular → Circulatory → Measurement
- HeartRate → Cardiovascular → Circulatory → Measurement
- Pulse → Cardiovascular → Circulatory → Measurement
- WalkingHeartRate → Cardiovascular → Circulatory → Measurement
- HeartRateVariability → Cardiovascular → Circulatory → Measurement

### Test 2.2: Classify Patient Individuals

**Test Data**:
```turtle
:Patient_Healthy_Adult a :Patient ;
    :hasMeasurement :BP_Reading_Healthy_001 ;
    :hasRiskLevel :LowRisk .

:BP_Reading_Healthy_001 a :BloodPressure ;
    :hasValue "115.0"^^xsd:double ;
    :hasClassification "Normal" .
```

**Reasoning Task**: Classify individuals and verify types

**Expected Result**: 
- Patient_Healthy_Adult is classified as Patient
- BP_Reading_Healthy_001 is classified as BloodPressure, Cardiovascular, Circulatory, Measurement

---

## 3. Consistency Checking

### Test 3.1: Valid Patient with Measurements

**Test Ontology**:
```turtle
:Patient_Valid a :Patient ;
    :hasMeasurement :BP_001, :HR_001 ;
    :hasRiskLevel :ModerateRisk .

:BP_001 a :BloodPressure ;
    :hasValue "135.0"^^xsd:double ;
    :hasClassification "High" ;
    :recordedBy :ClinicalMonitor_001 ;
    :measuredAt :ClinicalEnvironment .

:HR_001 a :HeartRate ;
    :hasValue "80.0"^^xsd:double ;
    :hasClassification "Normal" .
```

**Expected**: Ontology is **CONSISTENT**

### Test 3.2: Inconsistent Measurement Value

**Test Ontology**:
```turtle
:BP_Invalid a :BloodPressure ;
    :hasValue "115.0"^^xsd:double, "120.0"^^xsd:double ;  # Two different values (functional property)
    :hasClassification "Normal" .
```

**Expected**: Ontology is **INCONSISTENT** (if hasValue is defined as functional)

### Test 3.3: Missing Required Properties

**Test Ontology**:
```turtle
:Measurement_Incomplete a :BloodPressure .
# Missing hasValue property
```

**Expected**: **WARNING** if hasValue is defined as required (using restrictions)

---

## 4. Inference Examples

### Test 4.1: Property Chain Inference

**Define Property Chain**:
```turtle
:Patient a owl:Class .
:Device a owl:Class .

:hasMeasurement a owl:ObjectProperty ;
    rdfs:domain :Patient ;
    rdfs:range :Measurement .

:recordedBy a owl:ObjectProperty ;
    rdfs:domain :Measurement ;
    rdfs:range :Device .

# Property chain: Patient uses Device through measurements
:usesDevice a owl:ObjectProperty ;
    owl:propertyChainAxiom ( :hasMeasurement :recordedBy ) .
```

**Test Data**:
```turtle
:Patient_John a :Patient ;
    :hasMeasurement :BP_Reading_001 .

:BP_Reading_001 a :BloodPressure ;
    :recordedBy :AppleWatch_Series8 .
```

**Expected Inference**:
```turtle
:Patient_John :usesDevice :AppleWatch_Series8 .  # INFERRED
```

### Test 4.2: Transitive Property Reasoning

**Define Transitive Property**:
```turtle
:isHigherRiskThan a owl:ObjectProperty, owl:TransitiveProperty .

:HighRisk :isHigherRiskThan :ModerateRisk .
:ModerateRisk :isHigherRiskThan :LowRisk .
```

**Expected Inference**:
```turtle
:HighRisk :isHigherRiskThan :LowRisk .  # INFERRED via transitivity
```

### Test 4.3: Inverse Property Inference

**Define Inverse Properties**:
```turtle
:hasMeasurement a owl:ObjectProperty ;
    owl:inverseOf :measurementOf .

:Patient_Alice a :Patient ;
    :hasMeasurement :BP_Reading_Alice .
```

**Expected Inference**:
```turtle
:BP_Reading_Alice :measurementOf :Patient_Alice .  # INFERRED
```

---

## 5. Property Hierarchy Reasoning

### Test 5.1: Sub-Property Inheritance

**Define Property Hierarchy**:
```turtle
:hasCardiovascularMeasurement rdfs:subPropertyOf :hasMeasurement .
:hasBloodPressureReading rdfs:subPropertyOf :hasCardiovascularMeasurement .

:Patient_Bob :hasBloodPressureReading :BP_Bob_001 .
```

**Expected Inferences**:
```turtle
:Patient_Bob :hasCardiovascularMeasurement :BP_Bob_001 .  # INFERRED
:Patient_Bob :hasMeasurement :BP_Bob_001 .  # INFERRED
```

---

## 6. Class Hierarchy Inference

### Test 6.1: Defined Class with Restrictions

**Define Hypertensive Patient Class**:
```turtle
:HypertensivePatient owl:equivalentClass [
    a owl:Class ;
    owl:intersectionOf (
        :Patient
        [ a owl:Restriction ;
          owl:onProperty :hasMeasurement ;
          owl:someValuesFrom [
              a owl:Class ;
              owl:intersectionOf (
                  :BloodPressure
                  [ a owl:Restriction ;
                    owl:onProperty :hasClassification ;
                    owl:hasValue "High"
                  ]
              )
          ]
        ]
    )
] .
```

**Test Data**:
```turtle
:Patient_Charlie a :Patient ;
    :hasMeasurement :BP_Charlie_001 .

:BP_Charlie_001 a :BloodPressure ;
    :hasValue "155.0"^^xsd:double ;
    :hasClassification "High" .
```

**Expected Inference**:
```turtle
:Patient_Charlie a :HypertensivePatient .  # INFERRED
```

### Test 6.2: Disjoint Classes

**Define Disjoint Classes**:
```turtle
:LowRisk owl:disjointWith :ModerateRisk, :HighRisk .
:ModerateRisk owl:disjointWith :HighRisk .

# Test inconsistency
:Risk_Invalid a :LowRisk, :HighRisk .  # INCONSISTENT
```

**Expected**: Ontology is **INCONSISTENT**

---

## 7. Individual Classification

### Test 7.1: Classify All Patient Individuals

**Test Data** (from ontology):
```turtle
# Patient 1: Healthy Adult
:Patient_Healthy_Adult a :Patient ;
    :hasMeasurement :BP_Reading_Healthy_001, :HR_Reading_Healthy_001, :HRV_Reading_Healthy_001 ;
    :hasRiskLevel :LowRisk .

# Patient 2: Pre-Hypertensive
:Patient_PreHypertensive a :Patient ;
    :hasMeasurement :BP_Reading_PreHT_001, :HR_Reading_PreHT_001, :HRV_Reading_PreHT_001 ;
    :hasRiskLevel :ModerateRisk .

# Patient 3: Hypertensive
:Patient_Hypertensive a :Patient ;
    :hasMeasurement :BP_Reading_HT_001, :HR_Reading_HT_001, :HRV_Reading_HT_001 ;
    :hasRiskLevel :HighRisk .
```

**Reasoning Task**: Classify all individuals

**Expected Results**:
1. **Patient_Healthy_Adult**:
   - Type: Patient
   - Measurements: 3 (BP: Normal, HR: Normal, HRV: High)
   - Risk: LowRisk

2. **Patient_PreHypertensive**:
   - Type: Patient
   - Measurements: 3 (BP: High, HR: Normal, HRV: Moderate)
   - Risk: ModerateRisk

3. **Patient_Hypertensive**:
   - Type: Patient
   - Measurements: 3 (BP: High, HR: High, HRV: Low)
   - Risk: HighRisk

### Test 7.2: Classify Measurement Individuals

**Test Query**:
```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>

SELECT ?measurement ?type ?classification WHERE {
  ?measurement a ?type .
  ?type rdfs:subClassOf+ :Measurement .
  OPTIONAL { ?measurement :hasClassification ?classification }
}
```

**Expected Results**: All 9+ measurement individuals classified correctly

---

## 8. Complex Queries

### Test 8.1: Find Patients with High-Risk Measurements

**DL Query**:
```
Patient and (hasMeasurement some (BloodPressure and (hasClassification value "High")))
```

**Expected Results**:
- Patient_PreHypertensive
- Patient_Hypertensive

### Test 8.2: Find All Wearable Device Measurements

**DL Query**:
```
Measurement and (recordedBy some (Device and (device value "Wearable")))
```

**Expected Results**:
- BP_Reading_Healthy_001 (Apple Watch)
- HR_Reading_Healthy_001 (Apple Watch)
- HRV_Reading_Healthy_001 (Apple Watch)
- HRV_Reading_PreHT_001 (Garmin)
- Walking_HR_001 (Apple Watch)

### Test 8.3: Find Measurements Taken at Night

**DL Query**:
```
Measurement and (timeOfDay value "Night")
```

**Expected Results**:
- HRV_Reading_Healthy_001
- HRV_Reading_PreHT_001
- HRV_Reading_HT_001

---

## 9. Performance Testing

### Test 9.1: Large Dataset Reasoning

**Dataset**:
- 1000 Patient individuals
- 10,000 Measurement individuals (10 per patient)
- 50 Device individuals
- 10 Environment individuals

**Performance Metrics**:
1. **Classification Time**: < 30 seconds
2. **Consistency Check Time**: < 10 seconds
3. **Query Response Time**: < 2 seconds

**Test Procedure**:
1. Load ontology with generated data
2. Start reasoner
3. Measure classification time
4. Run 10 complex queries
5. Measure average query time

### Test 9.2: Incremental Reasoning

**Test Procedure**:
1. Load base ontology (3 patients)
2. Classify (baseline time)
3. Add 1 new patient with 5 measurements
4. Measure incremental classification time
5. Expected: < 1 second (should be faster than full reclassification)

---

## 10. Testing Checklist

### Basic Functionality
- [ ] Load cardiovascular ontology successfully
- [ ] Start reasoner (Pellet/HermiT/ELK)
- [ ] Classify ontology without errors
- [ ] Check consistency (should be consistent)
- [ ] View inferred class hierarchy

### Class Classification
- [ ] Verify Measurement class hierarchy (5 levels)
- [ ] Verify Patient, Device, Environment, RiskLevel classes
- [ ] Check for unsatisfiable classes (should be none)

### Individual Classification
- [ ] Classify 3 patient individuals correctly
- [ ] Classify 9+ measurement individuals correctly
- [ ] Classify 4 device individuals correctly
- [ ] Classify 4 environment individuals correctly
- [ ] Classify 3 risk level individuals correctly

### Property Reasoning
- [ ] Verify object property hierarchy (hasMeasurement, recordedBy, measuredAt, hasRiskLevel)
- [ ] Verify datatype property hierarchy (hasValue, hasClassification, hasReadingCount)
- [ ] Test inverse property inference (if defined)
- [ ] Test transitive property inference (if defined)

### DL Queries
- [ ] Find patients with high blood pressure
- [ ] Find patients with low heart rate variability
- [ ] Find measurements from wearable devices
- [ ] Find measurements taken at home
- [ ] Find patients with multiple risk factors

### Advanced Reasoning
- [ ] Test property chain inference
- [ ] Test defined class classification
- [ ] Test disjoint class checking
- [ ] Test cardinality restrictions (if defined)

### Consistency Checking
- [ ] Valid ontology is consistent
- [ ] Invalid ontology with conflicts is inconsistent
- [ ] Disjoint class violations detected
- [ ] Domain/range violations detected

### Performance
- [ ] Classification completes in reasonable time (< 5 sec for small ontology)
- [ ] Query response time acceptable (< 1 sec)
- [ ] Memory usage reasonable (< 500 MB)

### Error Handling
- [ ] Handle missing individuals gracefully
- [ ] Handle malformed DL queries
- [ ] Display clear error messages
- [ ] Recover from reasoner crashes

### Integration
- [ ] Works with graph view plugin (visualize inferences)
- [ ] Works with SPARQL query plugin (query inferred triples)
- [ ] Works with SWRL editor plugin (rule-based reasoning)

---

## Appendix A: Common DL Query Patterns

### Pattern 1: Find Individuals by Class and Property Value
```
Patient and (hasRiskLevel value HighRisk)
```

### Pattern 2: Find Individuals with Specific Property
```
Patient and (hasMeasurement some BloodPressure)
```

### Pattern 3: Find Individuals with Property Restrictions
```
Measurement and (hasValue some double[>= 100, <= 120])
```

### Pattern 4: Complex Boolean Queries
```
Patient and (
  (hasMeasurement some (BloodPressure and (hasClassification value "High"))) or
  (hasMeasurement some (HeartRate and (hasClassification value "High")))
)
```

---

## Appendix B: Reasoner Comparison

| Feature | Pellet | HermiT | ELK |
|---------|--------|--------|-----|
| OWL 2 DL Support | Full | Full | Profile (EL++) |
| Consistency Checking | ✓ | ✓ | ✓ |
| Classification | ✓ | ✓ | ✓ (fast) |
| Complex Queries | ✓ | ✓ | Limited |
| Performance | Good | Good | Excellent |
| Memory Usage | Moderate | Moderate | Low |
| **Recommended For** | General use | Complex reasoning | Large ontologies |

---

## Appendix C: Expected Inference Statistics

After running the reasoner on the cardiovascular ontology:

**Class Hierarchy**:
- Total classes: 10
- Root classes: 4 (Measurement, Patient, Device, Environment, RiskLevel)
- Maximum depth: 4 (Measurement → Circulatory → Cardiovascular → BloodPressure)

**Individuals**:
- Total individuals: 20+
- Patients: 3
- Measurements: 9
- Devices: 4
- Environments: 4
- Risk Levels: 3

**Inferred Axioms** (approximate):
- SubClassOf: 15
- Type assertions: 50+
- Property assertions: 30+

**Reasoning Time** (estimated):
- Classification: 0.5-2 seconds
- Consistency check: 0.2-1 second
- Query execution: 0.1-0.5 seconds per query

---

**Test Document Version**: 1.0
**Last Updated**: December 30, 2025
**Compatible With**: Cardiovascular Ontology v1.0
