# Change Assistant Plugin - Test Examples (Cardiovascular Ontology)

This document provides comprehensive change tracking and management test cases for the cardiovascular measurement ontology.

## Table of Contents
1. [Basic Change Tracking](#1-basic-change-tracking)
2. [Class Evolution](#2-class-evolution)
3. [Property Changes](#3-property-changes)
4. [Individual Updates](#4-individual-updates)
5. [Annotation Changes](#5-annotation-changes)
6. [Refactoring Operations](#6-refactoring-operations)
7. [Version Control Integration](#7-version-control-integration)
8. [Change Impact Analysis](#8-change-impact-analysis)
9. [Collaborative Editing](#9-collaborative-editing)
10. [Testing Checklist](#10-testing-checklist)

---

## 1. Basic Change Tracking

### Change 1.1: Add New Measurement Type

**Change Type**: Addition

**Description**: Add new "RestingHeartRate" class for sleep/rest measurements

**Changes**:
```turtle
# NEW CLASS
:RestingHeartRate rdfs:subClassOf :Cardiovascular ;
    rdfs:label "Resting Heart Rate" ;
    :measurementUnit "/min (beats per minute)" ;
    :measurementEnvironment "Sleep / Rest" ;
    :refLow "50" ;
    :refHigh "80" ;
    :loincCode "40443-4" .
```

**Impact**:
- Adds 1 new class
- Inherits from Cardiovascular (5 existing subclasses → 6)
- No breaking changes
- Compatible with existing reasoners

**Change Record**:
```json
{
  "changeId": "CHG-001",
  "timestamp": "2025-12-30T10:30:00Z",
  "author": "Radha",
  "type": "addition",
  "entity": "RestingHeartRate",
  "entityType": "Class",
  "description": "Added RestingHeartRate class for sleep monitoring",
  "impact": "low",
  "breaking": false
}
```

### Change 1.2: Update Reference Range

**Change Type**: Modification

**Description**: Update blood pressure reference ranges based on new AHA guidelines

**Changes**:
```turtle
# BEFORE
:BloodPressure
    :refLow "90/60" ;
    :refHigh "120/80" .

# AFTER
:BloodPressure
    :refLow "90/60" ;
    :refHigh "120/80" ;  # Keep for general population
    :refHigh_Senior "130/80" .  # New annotation for seniors (65+)
```

**Impact**:
- Modifies 1 class annotation
- Adds age-specific reference range
- Backward compatible (refHigh unchanged)
- May affect risk classification for seniors

**Change Record**:
```json
{
  "changeId": "CHG-002",
  "timestamp": "2025-12-30T11:00:00Z",
  "author": "Gideon",
  "type": "modification",
  "entity": "BloodPressure",
  "entityType": "Class",
  "property": "refHigh",
  "oldValue": "120/80",
  "newValue": "120/80 (general), 130/80 (senior)",
  "description": "Added age-specific BP reference range for seniors",
  "impact": "medium",
  "breaking": false,
  "references": ["AHA_BP_2023_Update"]
}
```

### Change 1.3: Delete Obsolete Property

**Change Type**: Deletion

**Description**: Remove deprecated "exampleOriginalTimestamp" annotation

**Changes**:
```turtle
# REMOVE (used in all measurement individuals)
:exampleOriginalTimestamp a owl:AnnotationProperty .

# All instances updated to use only:
:exampleTimestamp a owl:AnnotationProperty .
```

**Impact**:
- Removes 1 annotation property
- Affects 9+ individuals
- **BREAKING CHANGE**: Queries using this property will fail
- Migration required

**Change Record**:
```json
{
  "changeId": "CHG-003",
  "timestamp": "2025-12-30T12:00:00Z",
  "author": "Radha",
  "type": "deletion",
  "entity": "exampleOriginalTimestamp",
  "entityType": "AnnotationProperty",
  "description": "Removed deprecated timestamp property",
  "impact": "high",
  "breaking": true,
  "affectedEntities": [
    "BP_Reading_Healthy_001",
    "HR_Reading_Healthy_001",
    "HRV_Reading_Healthy_001",
    "... (9 total)"
  ],
  "migrationGuide": "Replace exampleOriginalTimestamp with exampleTimestamp"
}
```

---

## 2. Class Evolution

### Evolution 2.1: Split Class into Subclasses

**Scenario**: Split generic "Pulse" class into "RestingPulse" and "ActivePulse"

**Before**:
```turtle
:Pulse rdfs:subClassOf :Cardiovascular ;
    rdfs:label "Pulse" .
```

**After**:
```turtle
:Pulse rdfs:subClassOf :Cardiovascular ;
    rdfs:label "Pulse" ;
    owl:deprecated true ;
    :deprecationNote "Use RestingPulse or ActivePulse for more specificity" .

:RestingPulse rdfs:subClassOf :Pulse ;
    rdfs:label "Resting Pulse" ;
    :measurementEnvironment "Home / Clinical at rest" ;
    :posture "Seated, Lying" .

:ActivePulse rdfs:subClassOf :Pulse ;
    rdfs:label "Active Pulse" ;
    :measurementEnvironment "During activity" ;
    :posture "Walking, Standing" .
```

**Migration**:
```sparql
# Update existing instances
DELETE { ?individual a :Pulse }
INSERT { 
  ?individual a :RestingPulse 
}
WHERE {
  ?individual a :Pulse ;
              :posture ?posture .
  FILTER(?posture IN ("Seated", "Lying"))
}
```

**Change Record**:
```json
{
  "changeId": "CHG-004",
  "timestamp": "2025-12-30T14:00:00Z",
  "author": "Radha",
  "type": "refactoring",
  "entity": "Pulse",
  "operation": "split",
  "newEntities": ["RestingPulse", "ActivePulse"],
  "description": "Split Pulse into Resting and Active variants",
  "impact": "high",
  "breaking": true,
  "migrationScript": "migrate_pulse_classes.sparql"
}
```

### Evolution 2.2: Merge Redundant Classes

**Scenario**: Merge "HeartRate" and "Pulse" (they measure the same thing)

**Before**:
```turtle
:HeartRate rdfs:subClassOf :Cardiovascular .
:Pulse rdfs:subClassOf :Cardiovascular .
```

**After**:
```turtle
:HeartRate rdfs:subClassOf :Cardiovascular ;
    rdfs:label "Heart Rate" ;
    :alternativeLabel "Pulse" .

:Pulse owl:equivalentClass :HeartRate ;
    owl:deprecated true ;
    :deprecationNote "Merged with HeartRate" .
```

**Change Record**:
```json
{
  "changeId": "CHG-005",
  "timestamp": "2025-12-30T15:00:00Z",
  "author": "Gideon",
  "type": "refactoring",
  "entity": "Pulse",
  "operation": "merge",
  "mergedInto": "HeartRate",
  "description": "Merged Pulse into HeartRate (equivalent concepts)",
  "impact": "medium",
  "breaking": false,
  "justification": "Simplify ontology; Pulse and HeartRate measure the same vital sign"
}
```

### Evolution 2.3: Introduce Intermediate Class

**Scenario**: Add "CardiacMeasurement" between "Cardiovascular" and specific types

**Before**:
```turtle
:Cardiovascular rdfs:subClassOf :Circulatory .
:BloodPressure rdfs:subClassOf :Cardiovascular .
:HeartRate rdfs:subClassOf :Cardiovascular .
:HeartRateVariability rdfs:subClassOf :Cardiovascular .
```

**After**:
```turtle
:Cardiovascular rdfs:subClassOf :Circulatory .

:CardiacMeasurement rdfs:subClassOf :Cardiovascular ;
    rdfs:label "Cardiac Measurement" ;
    :definition "Direct cardiac function measurements" .

:VascularMeasurement rdfs:subClassOf :Cardiovascular ;
    rdfs:label "Vascular Measurement" ;
    :definition "Blood vessel and pressure measurements" .

:HeartRate rdfs:subClassOf :CardiacMeasurement .
:HeartRateVariability rdfs:subClassOf :CardiacMeasurement .
:BloodPressure rdfs:subClassOf :VascularMeasurement .
```

**Change Record**:
```json
{
  "changeId": "CHG-006",
  "timestamp": "2025-12-30T16:00:00Z",
  "author": "Radha",
  "type": "refactoring",
  "entity": "Cardiovascular",
  "operation": "introduce-intermediate",
  "newClasses": ["CardiacMeasurement", "VascularMeasurement"],
  "description": "Added intermediate classes for better organization",
  "impact": "low",
  "breaking": false,
  "justification": "Improve hierarchy clarity; distinguish cardiac vs vascular"
}
```

---

## 3. Property Changes

### Change 3.1: Add Cardinality Restriction

**Description**: Ensure each measurement has exactly one value

**Changes**:
```turtle
# BEFORE
:hasValue a owl:DatatypeProperty ;
    rdfs:domain :Measurement ;
    rdfs:range xsd:double .

# AFTER
:hasValue a owl:DatatypeProperty, owl:FunctionalProperty ;
    rdfs:domain :Measurement ;
    rdfs:range xsd:double .

:Measurement rdfs:subClassOf [
    a owl:Restriction ;
    owl:onProperty :hasValue ;
    owl:cardinality 1
] .
```

**Impact**:
- Adds functional constraint
- May cause inconsistencies if violations exist
- Reasoner will detect multiple values

**Change Record**:
```json
{
  "changeId": "CHG-007",
  "timestamp": "2025-12-30T17:00:00Z",
  "author": "Gideon",
  "type": "modification",
  "entity": "hasValue",
  "entityType": "DatatypeProperty",
  "description": "Made hasValue functional with cardinality 1",
  "impact": "medium",
  "breaking": false,
  "validation": "Check for measurements with multiple values"
}
```

### Change 3.2: Rename Property

**Description**: Rename "hasMeasurement" to "hasVitalSign" for clarity

**Changes**:
```turtle
# OLD
:hasMeasurement a owl:ObjectProperty ;
    rdfs:domain :Patient ;
    rdfs:range :Measurement .

# NEW
:hasVitalSign a owl:ObjectProperty ;
    rdfs:domain :Patient ;
    rdfs:range :Measurement ;
    owl:equivalentProperty :hasMeasurement .

:hasMeasurement owl:deprecated true ;
    :deprecationNote "Use hasVitalSign instead" .
```

**Migration**:
- Update 9+ patient-measurement relationships
- Queries using old property name still work (equivalence)

**Change Record**:
```json
{
  "changeId": "CHG-008",
  "timestamp": "2025-12-30T18:00:00Z",
  "author": "Radha",
  "type": "refactoring",
  "entity": "hasMeasurement",
  "operation": "rename",
  "newName": "hasVitalSign",
  "description": "Renamed property for better semantic clarity",
  "impact": "low",
  "breaking": false,
  "backward-compatible": true
}
```

### Change 3.3: Add Inverse Property

**Description**: Define inverse of "recordedBy"

**Changes**:
```turtle
# EXISTING
:recordedBy a owl:ObjectProperty ;
    rdfs:domain :Measurement ;
    rdfs:range :Device ;
    rdfs:label "recorded by" .

# NEW
:records a owl:ObjectProperty ;
    rdfs:domain :Device ;
    rdfs:range :Measurement ;
    rdfs:label "records" ;
    owl:inverseOf :recordedBy .
```

**Impact**:
- Enables bidirectional queries
- Reasoner will infer inverse relationships automatically

**Change Record**:
```json
{
  "changeId": "CHG-009",
  "timestamp": "2025-12-30T19:00:00Z",
  "author": "Gideon",
  "type": "addition",
  "entity": "records",
  "entityType": "ObjectProperty",
  "description": "Added inverse property for recordedBy",
  "impact": "low",
  "breaking": false,
  "enables": ["Bidirectional device-measurement queries"]
}
```

---

## 4. Individual Updates

### Update 4.1: Correct Measurement Value

**Description**: Fix typo in blood pressure reading

**Changes**:
```turtle
# BEFORE
:BP_Reading_Healthy_001
    :hasValue "115.0"^^xsd:double .  # Systolic only

# AFTER
:BP_Reading_Healthy_001
    :hasValue "115.0"^^xsd:double ;  # Systolic
    :hasDiastolicValue "75.0"^^xsd:double .  # Added diastolic
```

**Change Record**:
```json
{
  "changeId": "CHG-010",
  "timestamp": "2025-12-30T20:00:00Z",
  "author": "Radha",
  "type": "correction",
  "entity": "BP_Reading_Healthy_001",
  "entityType": "Individual",
  "property": "hasDiastolicValue",
  "oldValue": null,
  "newValue": "75.0",
  "description": "Added missing diastolic BP value",
  "impact": "low",
  "reason": "Data completeness"
}
```

### Update 4.2: Update Patient Risk Level

**Description**: Reclassify patient based on new measurements

**Changes**:
```turtle
# BEFORE
:Patient_Healthy_Adult :hasRiskLevel :LowRisk .

# AFTER (after adding new high BP reading)
:Patient_Healthy_Adult :hasRiskLevel :ModerateRisk .
```

**Triggered By**: Addition of new BP reading > 130 mmHg

**Change Record**:
```json
{
  "changeId": "CHG-011",
  "timestamp": "2025-12-30T21:00:00Z",
  "author": "System (SWRL Rule)",
  "type": "inference",
  "entity": "Patient_Healthy_Adult",
  "entityType": "Individual",
  "property": "hasRiskLevel",
  "oldValue": "LowRisk",
  "newValue": "ModerateRisk",
  "description": "Risk level updated based on new BP reading",
  "impact": "medium",
  "triggeredBy": "Addition of BP_Reading_Healthy_002 (135 mmHg)"
}
```

### Update 4.3: Bulk Update Device Platform IDs

**Description**: Update all Apple platform identifiers to iOS 17 format

**Changes**:
```sparql
# Update query
DELETE {
  ?device :platformID_Apple ?oldID .
}
INSERT {
  ?device :platformID_Apple ?newID .
}
WHERE {
  ?device a :Device ;
          :platformID_Apple ?oldID .
  FILTER(CONTAINS(?oldID, "HKQuantityTypeIdentifier"))
  BIND(REPLACE(?oldID, "HKQuantityTypeIdentifier", "HKQuantityType.") AS ?newID)
}
```

**Change Record**:
```json
{
  "changeId": "CHG-012",
  "timestamp": "2025-12-30T22:00:00Z",
  "author": "Gideon",
  "type": "bulk-update",
  "affectedEntities": ["AppleWatch_Series8", "..."],
  "count": 4,
  "description": "Updated Apple platform IDs to iOS 17 format",
  "impact": "medium",
  "breaking": false,
  "scriptUsed": "update_apple_platform_ids.sparql"
}
```

---

## 5. Annotation Changes

### Annotation 5.1: Update LOINC Code

**Description**: Add additional LOINC code for blood pressure panel

**Changes**:
```turtle
# BEFORE
:BloodPressure :loincCode "8480-6; 8462-4" .

# AFTER
:BloodPressure :loincCode "8480-6; 8462-4; 85354-9" .
# 85354-9 = Blood pressure panel with all children optional
```

**Change Record**:
```json
{
  "changeId": "CHG-013",
  "timestamp": "2025-12-30T23:00:00Z",
  "author": "Radha",
  "type": "modification",
  "entity": "BloodPressure",
  "property": "loincCode",
  "oldValue": "8480-6; 8462-4",
  "newValue": "8480-6; 8462-4; 85354-9",
  "description": "Added LOINC code for BP panel",
  "impact": "low",
  "references": ["LOINC_2024_Update"]
}
```

### Annotation 5.2: Add Evidence Grade

**Description**: Add evidence grading to all measurement classes

**Changes**:
```turtle
:BloodPressure :evidenceGrade "V1" .
:HeartRate :evidenceGrade "V1, V2" .
:Pulse :evidenceGrade "V1" .
:WalkingHeartRate :evidenceGrade "V1" .
:HeartRateVariability :evidenceGrade "V2" .
```

**Change Record**:
```json
{
  "changeId": "CHG-014",
  "timestamp": "2025-12-31T00:00:00Z",
  "author": "Gideon",
  "type": "addition",
  "affectedClasses": ["BloodPressure", "HeartRate", "Pulse", "WalkingHeartRate", "HeartRateVariability"],
  "property": "evidenceGrade",
  "description": "Added evidence grading to all measurements",
  "impact": "low",
  "justification": "Track validation status and confidence level"
}
```

### Annotation 5.3: Translate Labels to Multiple Languages

**Description**: Add Spanish labels for internationalization

**Changes**:
```turtle
:BloodPressure
    rdfs:label "Blood Pressure"@en ;
    rdfs:label "Presión Arterial"@es .

:HeartRate
    rdfs:label "Heart Rate"@en ;
    rdfs:label "Frecuencia Cardíaca"@es .

:Patient
    rdfs:label "Patient"@en ;
    rdfs:label "Paciente"@es .
```

**Change Record**:
```json
{
  "changeId": "CHG-015",
  "timestamp": "2025-12-31T01:00:00Z",
  "author": "Radha",
  "type": "addition",
  "property": "rdfs:label",
  "language": "es",
  "count": 10,
  "description": "Added Spanish translations for main classes",
  "impact": "low",
  "breaking": false
}
```

---

## 6. Refactoring Operations

### Refactor 6.1: Extract Common Annotations to Parent

**Description**: Move shared annotations from subclasses to Cardiovascular parent

**Before**:
```turtle
:BloodPressure :measurementUnit "mmHg" .
:HeartRate :measurementUnit "/min" .
:Pulse :measurementUnit "/min" .
```

**After**:
```turtle
:Cardiovascular :measurementUnit "varies by type" .
:BloodPressure :measurementUnit "mmHg" .
:HeartRate :measurementUnit "/min" .
:Pulse :measurementUnit "/min" .
```

**Change Record**:
```json
{
  "changeId": "CHG-016",
  "timestamp": "2025-12-31T02:00:00Z",
  "author": "Gideon",
  "type": "refactoring",
  "operation": "extract-to-parent",
  "entity": "Cardiovascular",
  "description": "Standardized measurement unit annotations",
  "impact": "low",
  "breaking": false
}
```

### Refactor 6.2: Normalize Date Formats

**Description**: Standardize all timestamp formats to ISO 8601

**Changes**:
```sparql
# Convert any non-ISO timestamps
UPDATE {
  ?individual :exampleTimestamp ?newTimestamp .
}
WHERE {
  ?individual :exampleTimestamp ?oldTimestamp .
  FILTER(!isIRI(?oldTimestamp))
  BIND(xsd:dateTime(?oldTimestamp) AS ?newTimestamp)
}
```

**Change Record**:
```json
{
  "changeId": "CHG-017",
  "timestamp": "2025-12-31T03:00:00Z",
  "author": "Radha",
  "type": "refactoring",
  "operation": "normalize-format",
  "property": "exampleTimestamp",
  "description": "Normalized all timestamps to ISO 8601 format",
  "impact": "low",
  "affectedCount": 12
}
```

---

## 7. Version Control Integration

### Version 7.1: Commit Change Set

**Git Commit**:
```
commit abc123def456
Author: Radha <radha@example.com>
Date: Mon Dec 30 10:30:00 2025 +0000

    feat: Add RestingHeartRate class for sleep monitoring
    
    - New subclass of Cardiovascular
    - LOINC code: 40443-4
    - Reference range: 50-80 bpm
    - Supports sleep/rest environment
    
    Related: CHG-001
```

**Changed Files**:
- `untitled-ontology-55.owl` (main ontology)
- `CHANGELOG.md` (change log)
- `README.md` (updated class count)

### Version 7.2: Create Release Tag

**Release**: v2.0.0

**Tag Annotation**:
```
tag v2.0.0
Tagger: Gideon <gideon@example.com>
Date: Mon Dec 30 18:00:00 2025 +0000

Cardiovascular Ontology v2.0.0
===============================

Major Changes:
- Added RestingHeartRate class (CHG-001)
- Split Pulse into RestingPulse and ActivePulse (CHG-004)
- Added age-specific BP reference ranges (CHG-002)

Breaking Changes:
- Removed deprecated exampleOriginalTimestamp property (CHG-003)
  Migration: Use exampleTimestamp instead

Statistics:
- Classes: 10 → 12 (+2)
- Properties: 7 → 8 (+1)
- Individuals: 20 → 22 (+2)
- Annotations: 40 per class (average)

Contributors: Radha, Gideon
```

### Version 7.3: Diff Between Versions

**Command**: `git diff v1.0.0..v2.0.0 --stat`

**Output**:
```
untitled-ontology-55.owl | 127 ++++++++++++++++++++---
CHANGELOG.md             |  45 ++++++++++
README.md                |  12 +--
3 files changed, 165 insertions(+), 19 deletions(-)
```

**Detailed Diff** (sample):
```diff
+    <!-- http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#RestingHeartRate -->
+    <owl:Class rdf:about="http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#RestingHeartRate">
+        <rdfs:subClassOf rdf:resource="http://www.semanticweb.org/radhaa/ontologies/2025/9/untitled-ontology-55#Cardiovascular"/>
+        <rdfs:label>Resting Heart Rate</rdfs:label>
+        <untitled-ontology-55:loincCode>40443-4</untitled-ontology-55:loincCode>
+        <untitled-ontology-55:measurementUnit>/min (beats per minute)</untitled-ontology-55:measurementUnit>
+        <untitled-ontology-55:refLow>50</untitled-ontology-55:refLow>
+        <untitled-ontology-55:refHigh>80</untitled-ontology-55:refHigh>
+    </owl:Class>
```

---

## 8. Change Impact Analysis

### Impact 8.1: Downstream Query Analysis

**Change**: Remove "exampleOriginalTimestamp" property

**Affected SPARQL Queries**:
```sparql
# Query 1 (BROKEN)
SELECT ?measurement ?timestamp WHERE {
  ?measurement :exampleOriginalTimestamp ?timestamp .
}

# Fix:
SELECT ?measurement ?timestamp WHERE {
  ?measurement :exampleTimestamp ?timestamp .
}
```

**Impact Report**:
```json
{
  "changeId": "CHG-003",
  "impactAnalysis": {
    "affectedQueries": 3,
    "affectedSWRLRules": 0,
    "affectedIndividuals": 9,
    "affectedApplications": ["PatientDashboard", "MeasurementExport"],
    "severity": "high",
    "automatedMigration": true
  }
}
```

### Impact 8.2: Reasoner Performance Impact

**Change**: Add cardinality restrictions to hasValue property

**Before**:
- Classification time: 0.8 seconds
- Consistency check: 0.3 seconds

**After**:
- Classification time: 1.2 seconds (+50%)
- Consistency check: 0.8 seconds (+166%)

**Reason**: Additional consistency checks for functional property

**Recommendation**: "Performance impact acceptable; improves data quality"

### Impact 8.3: Application Compatibility

**Change**: Rename "hasMeasurement" to "hasVitalSign"

**Compatible Applications**:
- ✓ Graph View Plugin (uses reasoner, will infer equivalence)
- ✓ SPARQL Query Plugin (supports equivalentProperty)
- ✓ Reasoner Plugin (handles equivalence automatically)

**Incompatible Applications**:
- ✗ Legacy Data Export Tool (hardcoded property name)
- ✗ Third-party Analytics Dashboard (expects hasMeasurement)

**Mitigation**: Keep both properties as equivalent (no breaking change)

---

## 9. Collaborative Editing

### Collaboration 9.1: Concurrent Edit Conflict

**Scenario**: Radha and Gideon edit BloodPressure class simultaneously

**Radha's Change** (10:30 AM):
```turtle
:BloodPressure :refHigh "120/80" .
```

**Gideon's Change** (10:32 AM):
```turtle
:BloodPressure :refHigh "130/80" .  # Based on new guideline
```

**Conflict Detection**:
```json
{
  "conflictId": "CONF-001",
  "timestamp": "2025-12-30T10:35:00Z",
  "entity": "BloodPressure",
  "property": "refHigh",
  "version1": {
    "author": "Radha",
    "value": "120/80",
    "timestamp": "2025-12-30T10:30:00Z"
  },
  "version2": {
    "author": "Gideon",
    "value": "130/80",
    "timestamp": "2025-12-30T10:32:00Z"
  },
  "resolution": "manual-required"
}
```

**Resolution Options**:
1. Accept Radha's change (keep 120/80)
2. Accept Gideon's change (keep 130/80)
3. Merge both (add age-specific annotations)
4. Revert both and discuss

**Chosen Resolution** (by Radha at 10:40 AM):
```turtle
:BloodPressure
    :refHigh "120/80" ;  # General population
    :refHigh_Senior "130/80" .  # Seniors (65+)
```

### Collaboration 9.2: Review and Approve Changes

**Change Proposal**: CHG-004 (Split Pulse class)

**Review Process**:
```json
{
  "changeId": "CHG-004",
  "status": "pending-review",
  "submittedBy": "Radha",
  "submittedDate": "2025-12-30T14:00:00Z",
  "reviewers": ["Gideon", "Clinical Team Lead"],
  "reviews": [
    {
      "reviewer": "Gideon",
      "date": "2025-12-30T15:30:00Z",
      "status": "approved",
      "comment": "Good refactoring. Improves specificity."
    },
    {
      "reviewer": "Clinical Team Lead",
      "date": "2025-12-30T16:00:00Z",
      "status": "approved-with-changes",
      "comment": "Approve, but add migration guide for existing data"
    }
  ],
  "finalStatus": "approved",
  "mergedDate": "2025-12-30T16:30:00Z"
}
```

### Collaboration 9.3: Change Discussion Thread

**Thread**: Discussion on CHG-002 (Senior BP reference)

```
Gideon (10:50 AM): Should we use 130/80 for seniors based on new AHA guideline?

Radha (10:55 AM): Yes, but keep 120/80 as general reference. Add age-specific annotation.

Clinical Lead (11:00 AM): Agreed. Also consider adding pediatric ranges in future.

Radha (11:05 AM): Good idea. Will create follow-up issue for pediatric ranges (Issue #42).

Gideon (11:10 AM): 👍 Approved. Ready to merge.
```

---

## 10. Testing Checklist

### Basic Change Tracking
- [ ] Record addition of new class
- [ ] Record modification of existing class
- [ ] Record deletion of property
- [ ] Track who made each change
- [ ] Timestamp all changes
- [ ] Generate change log automatically

### Class Evolution
- [ ] Split class into subclasses
- [ ] Merge redundant classes
- [ ] Introduce intermediate class
- [ ] Deprecate obsolete class
- [ ] Migration scripts generated

### Property Changes
- [ ] Add cardinality restriction
- [ ] Rename property (with equivalence)
- [ ] Add inverse property
- [ ] Change property domain/range
- [ ] Validate constraints after change

### Individual Updates
- [ ] Correct measurement value
- [ ] Update risk classification (inferred)
- [ ] Bulk update device platform IDs
- [ ] Add new patient individuals
- [ ] Delete obsolete individuals

### Annotation Changes
- [ ] Update LOINC codes
- [ ] Add evidence grades
- [ ] Translate labels to new language
- [ ] Update reference sources
- [ ] Version annotation values

### Refactoring
- [ ] Extract common annotations to parent
- [ ] Normalize date formats
- [ ] Standardize naming conventions
- [ ] Clean up deprecated properties
- [ ] Optimize property hierarchy

### Version Control
- [ ] Commit changes with descriptive messages
- [ ] Create release tags (v1.0, v2.0)
- [ ] Generate diff reports
- [ ] Track version history
- [ ] Rollback to previous version

### Impact Analysis
- [ ] Identify affected SPARQL queries
- [ ] Measure reasoner performance impact
- [ ] Check application compatibility
- [ ] Flag breaking changes
- [ ] Provide migration guides

### Collaborative Editing
- [ ] Detect concurrent edit conflicts
- [ ] Merge changes from multiple authors
- [ ] Review and approve changes
- [ ] Discussion threads per change
- [ ] Notification system for reviewers

### Performance
- [ ] Load change history quickly (< 1 sec)
- [ ] Display diff view fast (< 500ms)
- [ ] Search change log efficiently
- [ ] Handle 1000+ changes
- [ ] Export change report (< 5 sec)

### Error Handling
- [ ] Detect invalid changes
- [ ] Warn on breaking changes
- [ ] Prevent conflicting modifications
- [ ] Validate ontology after each change
- [ ] Provide clear error messages

---

## Appendix A: Change Types

- **addition**: New class, property, or individual
- **modification**: Update existing entity
- **deletion**: Remove entity (usually deprecated first)
- **correction**: Fix errors or typos
- **refactoring**: Restructure without changing meaning
- **inference**: Automatically derived by reasoner
- **bulk-update**: Multiple entities changed at once

---

## Appendix B: Impact Levels

- **low**: Minimal impact, backward compatible, no migration needed
- **medium**: Some impact, may require updates to queries or applications
- **high**: Significant impact, breaking changes, migration required

---

## Appendix C: CHANGELOG.md Format

```markdown
# Changelog

All notable changes to the Cardiovascular Ontology will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2025-12-30

### Added
- RestingHeartRate class for sleep monitoring (CHG-001)
- Age-specific BP reference ranges (CHG-002)
- Spanish translations for main classes (CHG-015)

### Changed
- Split Pulse into RestingPulse and ActivePulse (CHG-004)
- Renamed hasMeasurement to hasVitalSign (CHG-008, backward compatible)

### Removed
- Deprecated exampleOriginalTimestamp property (CHG-003, **BREAKING**)

### Fixed
- Added missing diastolic BP values (CHG-010)

## [1.0.0] - 2025-09-26

### Added
- Initial ontology release
- 10 measurement classes
- 3 patient individuals with sample data
- LOINC and platform ID mappings
```

---

**Test Document Version**: 1.0
**Last Updated**: December 30, 2025
**Compatible With**: Cardiovascular Ontology v1.0+
