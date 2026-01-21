# SPARQL Query Plugin - Test Examples (Cardiovascular Ontology)

This document provides comprehensive SPARQL query examples for the cardiovascular measurement ontology.

## Table of Contents
1. [Basic Queries](#1-basic-queries)
2. [Patient Queries](#2-patient-queries)
3. [Measurement Queries](#3-measurement-queries)
4. [Risk Analysis Queries](#4-risk-analysis-queries)
5. [Device and Environment Queries](#5-device-and-environment-queries)
6. [Aggregation Queries](#6-aggregation-queries)
7. [Advanced Queries](#7-advanced-queries)
8. [Federated Queries](#8-federated-queries)
9. [Performance Testing](#9-performance-testing)
10. [Testing Checklist](#10-testing-checklist)

---

## 1. Basic Queries

### Query 1.1: List All Classes

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT DISTINCT ?class ?label WHERE {
  ?class a owl:Class .
  OPTIONAL { ?class rdfs:label ?label }
}
ORDER BY ?label
```

**Expected Results**: 10+ classes (Measurement, Circulatory, Cardiovascular, BloodPressure, HeartRate, etc.)

### Query 1.2: Count All Individuals

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>

SELECT (COUNT(DISTINCT ?individual) AS ?count) WHERE {
  ?individual a owl:NamedIndividual .
}
```

**Expected Result**: ~20+ individuals

### Query 1.3: List All Properties

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT DISTINCT ?property ?type ?label WHERE {
  {
    ?property a owl:ObjectProperty .
    BIND("Object Property" AS ?type)
  } UNION {
    ?property a owl:DatatypeProperty .
    BIND("Datatype Property" AS ?type)
  } UNION {
    ?property a owl:AnnotationProperty .
    BIND("Annotation Property" AS ?type)
  }
  OPTIONAL { ?property rdfs:label ?label }
}
ORDER BY ?type ?property
```

**Expected Results**: hasMeasurement, recordedBy, measuredAt, hasRiskLevel, hasValue, hasClassification, etc.

---

## 2. Patient Queries

### Query 2.1: List All Patients

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?patient ?label ?risk WHERE {
  ?patient a :Patient .
  OPTIONAL { ?patient rdfs:label ?label }
  OPTIONAL { ?patient :hasRiskLevel ?risk }
}
ORDER BY ?label
```

**Expected Results**:
| patient | label | risk |
|---------|-------|------|
| :Patient_Healthy_Adult | "Patient 1: Healthy Adult" | :LowRisk |
| :Patient_PreHypertensive | "Patient 2: Pre-Hypertensive" | :ModerateRisk |
| :Patient_Hypertensive | "Patient 3: Hypertensive" | :HighRisk |

### Query 2.2: Get Patient Details with Measurements

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?patient ?label ?measurement ?mType ?value ?classification WHERE {
  ?patient a :Patient ;
           rdfs:label ?label ;
           :hasMeasurement ?measurement .
  ?measurement a ?mType ;
               :hasValue ?value ;
               :hasClassification ?classification .
  FILTER(?mType != owl:NamedIndividual)
}
ORDER BY ?patient ?mType
```

**Expected Results**: 9+ rows showing all patient-measurement relationships

### Query 2.3: Find High-Risk Patients

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?patient ?label WHERE {
  ?patient a :Patient ;
           rdfs:label ?label ;
           :hasRiskLevel :HighRisk .
}
```

**Expected Result**: Patient_Hypertensive ("Patient 3: Hypertensive")

### Query 2.4: Find Patients with Multiple High Measurements

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?patient ?label (COUNT(?measurement) AS ?highCount) WHERE {
  ?patient a :Patient ;
           rdfs:label ?label ;
           :hasMeasurement ?measurement .
  ?measurement :hasClassification ?class .
  FILTER(CONTAINS(?class, "High"))
}
GROUP BY ?patient ?label
HAVING (COUNT(?measurement) >= 2)
ORDER BY DESC(?highCount)
```

**Expected Results**: Patients with 2+ high measurements

---

## 3. Measurement Queries

### Query 3.1: List All Blood Pressure Readings

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

SELECT ?reading ?label ?value ?classification ?timestamp ?posture WHERE {
  ?reading a :BloodPressure ;
           rdfs:label ?label ;
           :hasValue ?value ;
           :hasClassification ?classification ;
           :exampleTimestamp ?timestamp ;
           :posture ?posture .
}
ORDER BY ?timestamp
```

**Expected Results**: 3 BP readings (115/75, 135/85, 155/95 mmHg)

### Query 3.2: Find Measurements Above Threshold

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

SELECT ?measurement ?type ?value ?classification WHERE {
  ?measurement a ?type ;
               :hasValue ?value ;
               :hasClassification ?classification .
  FILTER(?type IN (:BloodPressure, :HeartRate, :Pulse))
  FILTER(?value > 120)
}
ORDER BY DESC(?value)
```

**Expected Results**: BP readings > 120 mmHg and HR > 120 bpm

### Query 3.3: Get Heart Rate Variability Trends

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?reading ?label ?value ?classification ?timestamp WHERE {
  ?reading a :HeartRateVariability ;
           rdfs:label ?label ;
           :hasValue ?value ;
           :hasClassification ?classification ;
           :exampleTimestamp ?timestamp .
}
ORDER BY ?timestamp
```

**Expected Results**: 3 HRV readings (55ms, 35ms, 22ms SDNN)

### Query 3.4: Find Measurements by Classification

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?measurement ?type ?value ?classification WHERE {
  ?measurement a ?type ;
               :hasValue ?value ;
               :hasClassification ?classification .
  FILTER(REGEX(?classification, "Normal", "i"))
  FILTER(?type != owl:NamedIndividual)
}
ORDER BY ?type
```

**Expected Results**: All measurements classified as "Normal"

---

## 4. Risk Analysis Queries

### Query 4.1: Patient Risk Distribution

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?riskLevel (COUNT(?patient) AS ?patientCount) WHERE {
  ?patient a :Patient ;
           :hasRiskLevel ?riskLevel .
}
GROUP BY ?riskLevel
ORDER BY ?riskLevel
```

**Expected Results**:
| riskLevel | patientCount |
|-----------|--------------|
| :LowRisk | 1 |
| :ModerateRisk | 1 |
| :HighRisk | 1 |

### Query 4.2: Correlation Between BP and Risk Level

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?patient ?label ?bpValue ?risk WHERE {
  ?patient a :Patient ;
           rdfs:label ?label ;
           :hasMeasurement ?bp ;
           :hasRiskLevel ?risk .
  ?bp a :BloodPressure ;
      :hasValue ?bpValue .
}
ORDER BY DESC(?bpValue)
```

**Expected Results**: Shows correlation between higher BP and higher risk

### Query 4.3: Identify Patients Needing Attention

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?patient ?label ?bpValue ?hrValue ?hrvValue WHERE {
  ?patient a :Patient ;
           rdfs:label ?label ;
           :hasMeasurement ?bp, ?hr, ?hrv .
  
  ?bp a :BloodPressure ;
      :hasValue ?bpValue .
  
  ?hr a :HeartRate ;
      :hasValue ?hrValue .
  
  ?hrv a :HeartRateVariability ;
       :hasValue ?hrvValue .
  
  FILTER(?bpValue > 140 || ?hrValue > 90 || ?hrvValue < 25)
}
ORDER BY DESC(?bpValue)
```

**Expected Results**: Patients with concerning values

---

## 5. Device and Environment Queries

### Query 5.1: List All Devices and Their Measurements

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?device ?label (COUNT(?measurement) AS ?measurementCount) WHERE {
  ?device a :Device ;
          rdfs:label ?label .
  ?measurement :recordedBy ?device .
}
GROUP BY ?device ?label
ORDER BY DESC(?measurementCount)
```

**Expected Results**: Counts per device (AppleWatch, Garmin, ClinicalMonitor, ClinicalECG)

### Query 5.2: Find Wearable Device Measurements

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?measurement ?mType ?value ?device WHERE {
  ?measurement a ?mType ;
               :hasValue ?value ;
               :recordedBy ?device .
  ?device :device ?deviceType .
  FILTER(CONTAINS(?deviceType, "Wearable"))
  FILTER(?mType != owl:NamedIndividual)
}
ORDER BY ?device ?mType
```

**Expected Results**: All wearable measurements (Apple Watch, Garmin)

### Query 5.3: Measurements by Environment

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?environment ?envLabel (COUNT(?measurement) AS ?count) WHERE {
  ?environment a :Environment ;
               rdfs:label ?envLabel .
  ?measurement :measuredAt ?environment .
}
GROUP BY ?environment ?envLabel
ORDER BY DESC(?count)
```

**Expected Results**: Counts per environment (Home, Clinical, Fitness, Outdoor)

### Query 5.4: Device Platform Mapping

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?device ?label ?appleID ?garminID WHERE {
  ?device a :Device ;
          rdfs:label ?label .
  OPTIONAL { ?device :platformID_Apple ?appleID }
  OPTIONAL { ?device :platformID_Garmin ?garminID }
}
ORDER BY ?label
```

**Expected Results**: Platform identifiers for each device

---

## 6. Aggregation Queries

### Query 6.1: Average Blood Pressure by Risk Level

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>

SELECT ?riskLevel (AVG(?bpValue) AS ?avgBP) (COUNT(?patient) AS ?patientCount) WHERE {
  ?patient a :Patient ;
           :hasRiskLevel ?riskLevel ;
           :hasMeasurement ?bp .
  ?bp a :BloodPressure ;
      :hasValue ?bpValue .
}
GROUP BY ?riskLevel
ORDER BY ?avgBP
```

**Expected Results**:
| riskLevel | avgBP | patientCount |
|-----------|-------|--------------|
| :LowRisk | 115.0 | 1 |
| :ModerateRisk | 135.0 | 1 |
| :HighRisk | 155.0 | 1 |

### Query 6.2: Heart Rate Statistics

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>

SELECT 
  (COUNT(?hr) AS ?count)
  (MIN(?value) AS ?min)
  (MAX(?value) AS ?max)
  (AVG(?value) AS ?avg)
WHERE {
  ?hr a :HeartRate ;
      :hasValue ?value .
}
```

**Expected Results**: Min=70, Max=95, Avg=81.67, Count=3

### Query 6.3: Measurement Count by Type

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?type (COUNT(?measurement) AS ?count) WHERE {
  ?measurement a ?type .
  ?type rdfs:subClassOf+ :Measurement .
}
GROUP BY ?type
ORDER BY DESC(?count)
```

**Expected Results**: Counts for BloodPressure, HeartRate, HRV, Pulse, WalkingHeartRate

### Query 6.4: Daily Measurement Trends

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

SELECT 
  (xsd:date(?timestamp) AS ?date)
  ?timeOfDay
  (COUNT(?measurement) AS ?count)
WHERE {
  ?measurement :exampleTimestamp ?timestamp ;
               :timeOfDay ?timeOfDay .
}
GROUP BY (xsd:date(?timestamp)) ?timeOfDay
ORDER BY ?date ?timeOfDay
```

**Expected Results**: Measurements grouped by date and time of day

---

## 7. Advanced Queries

### Query 7.1: Construct Patient Summary Graph

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

CONSTRUCT {
  ?patient rdfs:label ?label ;
           :hasRiskLevel ?risk ;
           :summary ?summaryText .
}
WHERE {
  ?patient a :Patient ;
           rdfs:label ?label ;
           :hasRiskLevel ?risk .
  
  BIND(CONCAT("Patient: ", STR(?label), " | Risk: ", STRAFTER(STR(?risk), "#")) AS ?summaryText)
}
```

**Expected Result**: RDF graph with patient summaries

### Query 7.2: Recursive Class Hierarchy

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?class ?label ?parent ?depth WHERE {
  ?class rdfs:subClassOf+ :Measurement .
  ?class rdfs:subClassOf ?parent .
  OPTIONAL { ?class rdfs:label ?label }
  
  # Calculate depth (approximate)
  {
    SELECT ?class (COUNT(?intermediate) AS ?depth) WHERE {
      ?class rdfs:subClassOf+ ?intermediate .
      ?intermediate rdfs:subClassOf* :Measurement .
    }
    GROUP BY ?class
  }
}
ORDER BY ?depth ?class
```

**Expected Results**: Full class hierarchy with depth levels

### Query 7.3: Complex Filter with OPTIONAL

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?patient ?label ?bp ?hr ?hrv WHERE {
  ?patient a :Patient ;
           rdfs:label ?label .
  
  OPTIONAL {
    ?patient :hasMeasurement ?bpMeasure .
    ?bpMeasure a :BloodPressure ;
               :hasValue ?bp .
  }
  
  OPTIONAL {
    ?patient :hasMeasurement ?hrMeasure .
    ?hrMeasure a :HeartRate ;
               :hasValue ?hr .
  }
  
  OPTIONAL {
    ?patient :hasMeasurement ?hrvMeasure .
    ?hrvMeasure a :HeartRateVariability ;
                :hasValue ?hrv .
  }
}
ORDER BY ?label
```

**Expected Results**: Complete patient vital signs matrix

### Query 7.4: Subquery with Aggregation

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?patient ?label ?measurementCount ?avgValue WHERE {
  ?patient a :Patient ;
           rdfs:label ?label .
  
  {
    SELECT ?patient (COUNT(?measurement) AS ?measurementCount) (AVG(?value) AS ?avgValue) WHERE {
      ?patient :hasMeasurement ?measurement .
      ?measurement :hasValue ?value .
    }
    GROUP BY ?patient
  }
}
ORDER BY DESC(?measurementCount)
```

**Expected Results**: Patient statistics with counts and averages

---

## 8. Federated Queries

### Query 8.1: Cross-Ontology Patient Lookup (Example)

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>

SELECT ?patient ?label ?externalProfile WHERE {
  ?patient a :Patient ;
           rdfs:label ?label .
  
  # Federated query to external SPARQL endpoint (example)
  SERVICE <http://example.org/sparql> {
    ?externalProfile foaf:name ?label ;
                     foaf:age ?age .
  }
}
```

**Note**: Replace with actual external endpoint

### Query 8.2: LOINC Code Integration

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX loinc: <http://loinc.org/rdf#>

SELECT ?measurement ?loincCode ?loincName WHERE {
  ?measurement a ?type ;
               :loincCode ?loincCode .
  
  # Optional: Federated query to LOINC database
  # SERVICE <http://loinc.org/sparql> {
  #   ?loincTerm loinc:code ?loincCode ;
  #              loinc:shortName ?loincName .
  # }
}
```

---

## 9. Performance Testing

### Test 9.1: Simple Query Benchmark

**Query**: List all patients
**Expected Time**: < 100ms
**Result Count**: 3

### Test 9.2: Complex Join Benchmark

**Query**: Patient-measurement-device-environment join
**Expected Time**: < 500ms
**Result Count**: 9+

### Test 9.3: Aggregation Benchmark

**Query**: Average values grouped by risk level
**Expected Time**: < 300ms
**Result Count**: 3

### Test 9.4: Large Result Set

**Query**: All annotation properties and values
**Expected Time**: < 1 second
**Result Count**: 200+

---

## 10. Testing Checklist

### Basic Queries
- [ ] List all classes successfully
- [ ] Count all individuals correctly
- [ ] List all properties (object, datatype, annotation)
- [ ] Query executes without errors

### Patient Queries
- [ ] List all patients (3 patients)
- [ ] Get patient details with measurements
- [ ] Find high-risk patients (1 patient)
- [ ] Find patients with multiple conditions

### Measurement Queries
- [ ] List blood pressure readings (3 readings)
- [ ] Find measurements above threshold
- [ ] Get HRV trends (3 readings)
- [ ] Filter by classification

### Risk Analysis
- [ ] Calculate risk distribution
- [ ] Correlate BP with risk level
- [ ] Identify high-risk patients
- [ ] Alert on concerning values

### Device Queries
- [ ] List devices and counts
- [ ] Filter wearable measurements
- [ ] Group by environment
- [ ] Show platform mappings

### Aggregation
- [ ] Calculate average BP by risk
- [ ] Compute HR statistics (min/max/avg)
- [ ] Count measurements by type
- [ ] Trend analysis by time

### Advanced Features
- [ ] CONSTRUCT queries work
- [ ] OPTIONAL clauses handled
- [ ] Subqueries execute correctly
- [ ] FILTER expressions work
- [ ] REGEX matching works
- [ ] Date/time functions work

### Performance
- [ ] Simple queries < 100ms
- [ ] Complex queries < 500ms
- [ ] Aggregations < 300ms
- [ ] Large results < 1 second

### Error Handling
- [ ] Invalid syntax detected
- [ ] Missing prefixes reported
- [ ] Timeout handling works
- [ ] Clear error messages

### Integration
- [ ] Export results to CSV/JSON
- [ ] Visualize results in graph view
- [ ] Save queries for reuse
- [ ] Share queries with team

---

## Appendix A: SPARQL Prefix Definitions

```sparql
PREFIX : <http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
PREFIX loinc: <http://loinc.org/rdf#>
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
```

---

## Appendix B: Query Optimization Tips

1. **Use LIMIT**: Limit results for faster responses
   ```sparql
   SELECT * WHERE { ... } LIMIT 100
   ```

2. **Filter Early**: Apply filters before joins
   ```sparql
   FILTER(?value > 100)
   ```

3. **Avoid DISTINCT**: Unless necessary (expensive operation)

4. **Use Specific Types**: Instead of generic patterns
   ```sparql
   ?x a :BloodPressure  # Better than ?x a ?type
   ```

5. **Index-Friendly**: Order filters by selectivity

---

**Test Document Version**: 1.0
**Last Updated**: December 30, 2025
**Compatible With**: Cardiovascular Ontology v1.0
