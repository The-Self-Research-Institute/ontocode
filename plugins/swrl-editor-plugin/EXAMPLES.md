# SWRL Editor Plugin - Test Examples (Cardiovascular Ontology)

This document provides comprehensive SWRL (Semantic Web Rule Language) rule examples for the cardiovascular measurement ontology.

## Table of Contents
1. [Basic SWRL Rules](#1-basic-swrl-rules)
2. [Risk Assessment Rules](#2-risk-assessment-rules)
3. [Measurement Classification Rules](#3-measurement-classification-rules)
4. [Alert and Notification Rules](#4-alert-and-notification-rules)
5. [Temporal Rules](#5-temporal-rules)
6. [Device Validation Rules](#6-device-validation-rules)
7. [Complex Multi-Condition Rules](#7-complex-multi-condition-rules)
8. [Built-in Functions](#8-built-in-functions)
9. [Rule Testing](#9-rule-testing)
10. [Testing Checklist](#10-testing-checklist)

---

## 1. Basic SWRL Rules

### Rule 1.1: Infer High Blood Pressure Classification

```swrl
BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧ swrlb:greaterThan(?value, 140)
→ hasClassification(?bp, "High")
```

**Description**: Automatically classify blood pressure readings above 140 as "High"

**Test Data**:
```turtle
:BP_Test_001 a :BloodPressure ;
    :hasValue "155.0"^^xsd:double .
```

**Expected Result**: `hasClassification(?bp, "High")` asserted

### Rule 1.2: Infer Normal Blood Pressure

```swrl
BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧ 
swrlb:greaterThanOrEqual(?value, 90) ∧ swrlb:lessThanOrEqual(?value, 120)
→ hasClassification(?bp, "Normal")
```

**Description**: Classify BP between 90-120 as "Normal"

### Rule 1.3: Infer Low Blood Pressure

```swrl
BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧ swrlb:lessThan(?value, 90)
→ hasClassification(?bp, "Low")
```

**Description**: Classify BP below 90 as "Low"

---

## 2. Risk Assessment Rules

### Rule 2.1: Assign High Risk for Hypertensive Patients

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ 
hasValue(?bp, ?value) ∧ swrlb:greaterThan(?value, 140)
→ hasRiskLevel(?p, HighRisk)
```

**Description**: Patients with BP > 140 are assigned high risk

**Test Data**:
```turtle
:Patient_Test a :Patient ;
    :hasMeasurement :BP_Test_155 .

:BP_Test_155 a :BloodPressure ;
    :hasValue "155.0"^^xsd:double .
```

**Expected Result**: `:Patient_Test :hasRiskLevel :HighRisk`

### Rule 2.2: Moderate Risk for Pre-Hypertensive

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ 
hasValue(?bp, ?value) ∧ swrlb:greaterThan(?value, 120) ∧ 
swrlb:lessThanOrEqual(?value, 140)
→ hasRiskLevel(?p, ModerateRisk)
```

**Description**: Patients with BP 120-140 are moderate risk

### Rule 2.3: Low Risk for Normal Readings

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ 
hasValue(?bp, ?value) ∧ swrlb:lessThanOrEqual(?value, 120) ∧
hasMeasurement(?p, ?hr) ∧ HeartRate(?hr) ∧ hasValue(?hr, ?hrValue) ∧
swrlb:lessThanOrEqual(?hrValue, 100)
→ hasRiskLevel(?p, LowRisk)
```

**Description**: Patients with normal BP and HR are low risk

### Rule 2.4: Multiple Risk Factor Detection

```swrl
Patient(?p) ∧ 
hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ hasValue(?bp, ?bpVal) ∧ swrlb:greaterThan(?bpVal, 140) ∧
hasMeasurement(?p, ?hr) ∧ HeartRate(?hr) ∧ hasValue(?hr, ?hrVal) ∧ swrlb:greaterThan(?hrVal, 90) ∧
hasMeasurement(?p, ?hrv) ∧ HeartRateVariability(?hrv) ∧ hasValue(?hrv, ?hrvVal) ∧ swrlb:lessThan(?hrvVal, 25)
→ requiresImmediateAttention(?p, true)
```

**Description**: Flag patients with multiple concerning measurements

---

## 3. Measurement Classification Rules

### Rule 3.1: Heart Rate Classification - Bradycardia

```swrl
HeartRate(?hr) ∧ hasValue(?hr, ?value) ∧ swrlb:lessThan(?value, 60)
→ hasClassification(?hr, "Bradycardia")
```

**Description**: HR below 60 bpm is bradycardia

### Rule 3.2: Heart Rate Classification - Tachycardia

```swrl
HeartRate(?hr) ∧ hasValue(?hr, ?value) ∧ swrlb:greaterThan(?value, 100)
→ hasClassification(?hr, "Tachycardia")
```

**Description**: HR above 100 bpm is tachycardia

### Rule 3.3: HRV Classification - Good Autonomic Function

```swrl
HeartRateVariability(?hrv) ∧ hasValue(?hrv, ?value) ∧ swrlb:greaterThan(?value, 50)
→ hasClassification(?hrv, "High (Good Autonomic Function)")
```

**Description**: HRV > 50ms indicates good autonomic function

### Rule 3.4: HRV Classification - Poor Autonomic Function

```swrl
HeartRateVariability(?hrv) ∧ hasValue(?hrv, ?value) ∧ swrlb:lessThan(?value, 25)
→ hasClassification(?hrv, "Low (Poor Autonomic Function)")
```

**Description**: HRV < 25ms indicates poor autonomic function

### Rule 3.5: Walking Heart Rate Classification

```swrl
WalkingHeartRate(?whr) ∧ hasValue(?whr, ?value) ∧ 
swrlb:greaterThan(?value, 70) ∧ swrlb:lessThan(?value, 120)
→ hasClassification(?whr, "Normal Walking HR")
```

**Description**: Walking HR between 70-120 is normal

---

## 4. Alert and Notification Rules

### Rule 4.1: Critical Blood Pressure Alert

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ 
hasValue(?bp, ?value) ∧ swrlb:greaterThan(?value, 180)
→ requiresEmergencyAlert(?p, true), criticalMeasurement(?bp, true)
```

**Description**: Trigger emergency alert for BP > 180

### Rule 4.2: Low Heart Rate Alert

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?hr) ∧ HeartRate(?hr) ∧ 
hasValue(?hr, ?value) ∧ swrlb:lessThan(?value, 40)
→ requiresEmergencyAlert(?p, true), criticalMeasurement(?hr, true)
```

**Description**: Alert for dangerously low heart rate

### Rule 4.3: Declining HRV Trend Alert

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?hrv1) ∧ hasMeasurement(?p, ?hrv2) ∧
HeartRateVariability(?hrv1) ∧ HeartRateVariability(?hrv2) ∧
hasValue(?hrv1, ?val1) ∧ hasValue(?hrv2, ?val2) ∧
swrlb:subtract(?diff, ?val1, ?val2) ∧ swrlb:greaterThan(?diff, 15)
→ decliningHRVTrend(?p, true)
```

**Description**: Alert when HRV drops by > 15ms between readings

### Rule 4.4: Routine Check-up Recommendation

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ 
hasValue(?bp, ?value) ∧ swrlb:greaterThan(?value, 130) ∧ 
swrlb:lessThanOrEqual(?value, 140)
→ recommendsCheckup(?p, true), checkupUrgency(?p, "Medium")
```

**Description**: Recommend check-up for borderline high BP

---

## 5. Temporal Rules

### Rule 5.1: Recent Measurement Indicator

```swrl
Measurement(?m) ∧ exampleTimestamp(?m, ?timestamp) ∧ 
swrlb:yearMonthDay(?timestamp, ?year, ?month, ?day) ∧
swrlb:yearMonthDay(?now, ?nowYear, ?nowMonth, ?nowDay) ∧
swrlb:equal(?year, ?nowYear) ∧ swrlb:equal(?month, ?nowMonth)
→ isRecentMeasurement(?m, true)
```

**Description**: Flag measurements from current month

### Rule 5.2: Nighttime Measurement Classification

```swrl
Measurement(?m) ∧ timeOfDay(?m, ?time) ∧ swrlb:equal(?time, "Night")
→ isNighttimeMeasurement(?m, true)
```

**Description**: Classify measurements taken at night

### Rule 5.3: Morning BP Surge Detection

```swrl
BloodPressure(?bp1) ∧ BloodPressure(?bp2) ∧
timeOfDay(?bp1, "Evening") ∧ timeOfDay(?bp2, "Morning") ∧
hasValue(?bp1, ?val1) ∧ hasValue(?bp2, ?val2) ∧
swrlb:subtract(?surge, ?val2, ?val1) ∧ swrlb:greaterThan(?surge, 20)
→ hasMorningSurge(?bp2, true)
```

**Description**: Detect morning BP surge > 20 mmHg

---

## 6. Device Validation Rules

### Rule 6.1: Validate Clinical Measurement Source

```swrl
Measurement(?m) ∧ recordedBy(?m, ?device) ∧ Device(?device) ∧
device(?device, ?type) ∧ swrlb:contains(?type, "Clinical")
→ isClinicallyValidated(?m, true)
```

**Description**: Mark measurements from clinical devices as validated

### Rule 6.2: Wearable Device Measurement Flag

```swrl
Measurement(?m) ∧ recordedBy(?m, ?device) ∧ Device(?device) ∧
device(?device, ?type) ∧ swrlb:contains(?type, "Wearable")
→ isWearableSource(?m, true)
```

**Description**: Flag measurements from wearable devices

### Rule 6.3: Home Environment Context

```swrl
Measurement(?m) ∧ measuredAt(?m, ?env) ∧ Environment(?env) ∧
measurementEnvironment(?env, ?type) ∧ swrlb:equal(?type, "Home")
→ isHomeMeasurement(?m, true)
```

**Description**: Tag home environment measurements

### Rule 6.4: Multi-Device Correlation

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?m1) ∧ hasMeasurement(?p, ?m2) ∧
recordedBy(?m1, ?dev1) ∧ recordedBy(?m2, ?dev2) ∧
swrlb:notEqual(?dev1, ?dev2)
→ hasMultiDeviceData(?p, true)
```

**Description**: Flag patients with measurements from multiple devices

---

## 7. Complex Multi-Condition Rules

### Rule 7.1: Comprehensive Cardiovascular Risk

```swrl
Patient(?p) ∧ 
hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ hasValue(?bp, ?bpVal) ∧
hasMeasurement(?p, ?hr) ∧ HeartRate(?hr) ∧ hasValue(?hr, ?hrVal) ∧
hasMeasurement(?p, ?hrv) ∧ HeartRateVariability(?hrv) ∧ hasValue(?hrv, ?hrvVal) ∧
swrlb:greaterThan(?bpVal, 140) ∧ swrlb:greaterThan(?hrVal, 90) ∧ swrlb:lessThan(?hrvVal, 30)
→ hasRiskLevel(?p, HighRisk), cardiovascularRiskScore(?p, 3)
```

**Description**: Calculate comprehensive CV risk based on multiple factors

### Rule 7.2: Athlete Profile Detection

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?pulse) ∧ Pulse(?pulse) ∧ hasValue(?pulse, ?pulseVal) ∧
hasMeasurement(?p, ?hrv) ∧ HeartRateVariability(?hrv) ∧ hasValue(?hrv, ?hrvVal) ∧
swrlb:lessThan(?pulseVal, 60) ∧ swrlb:greaterThan(?hrvVal, 60)
→ athleticBradycardia(?pulse, true), athleteProfile(?p, true)
```

**Description**: Identify athletic individuals with low resting HR and high HRV

### Rule 7.3: Posture-Based Classification

```swrl
BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧ posture(?bp, ?posture) ∧
swrlb:equal(?posture, "Standing") ∧ swrlb:greaterThan(?value, 150)
→ possibleOrthostaticHypertension(?bp, true)
```

**Description**: Detect potential orthostatic hypertension

### Rule 7.4: Reading Count Validation

```swrl
BloodPressure(?bp) ∧ hasReadingCount(?bp, ?count) ∧ swrlb:lessThan(?count, 12)
→ insufficientReadings(?bp, true), requiresMoreData(?bp, true)
```

**Description**: Flag measurements with insufficient reading counts

---

## 8. Built-in Functions

### Rule 8.1: Mathematical Operations

```swrl
Patient(?p) ∧ hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ hasValue(?bp, ?systolic) ∧
swrlb:multiply(?riskScore, ?systolic, 0.1) ∧ swrlb:round(?score, ?riskScore)
→ calculatedRiskScore(?p, ?score)
```

**Description**: Calculate risk score using mathematical operations

### Rule 8.2: String Operations

```swrl
Measurement(?m) ∧ hasClassification(?m, ?class) ∧ 
swrlb:contains(?class, "High") ∧ swrlb:upperCase(?upperClass, ?class)
→ alertLevel(?m, ?upperClass)
```

**Description**: String manipulation for alert levels

### Rule 8.3: Date/Time Comparison

```swrl
Measurement(?m1) ∧ Measurement(?m2) ∧ 
exampleTimestamp(?m1, ?t1) ∧ exampleTimestamp(?m2, ?t2) ∧
swrlb:greaterThan(?t1, ?t2)
→ isMoreRecent(?m1, ?m2)
```

**Description**: Compare timestamps for temporal ordering

### Rule 8.4: Conditional Logic

```swrl
Patient(?p) ∧ sex(?p, ?sex) ∧ hasMeasurement(?p, ?bp) ∧ BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧
swrlb:equal(?sex, "M") ∧ swrlb:greaterThan(?value, 140) ∧ swrlb:booleanNot(?isFemale, true)
→ maleHypertension(?p, true)
```

**Description**: Sex-specific classification rules

---

## 9. Rule Testing

### Test 9.1: Rule Execution Order

**Test Rules**:
1. Classification rule (runs first)
2. Risk assessment rule (depends on classification)
3. Alert rule (depends on risk level)

**Test Data**:
```turtle
:Patient_Test a :Patient ;
    :hasMeasurement :BP_Test .

:BP_Test a :BloodPressure ;
    :hasValue "155.0"^^xsd:double .
```

**Expected Execution**:
1. `hasClassification(:BP_Test, "High")` asserted
2. `hasRiskLevel(:Patient_Test, :HighRisk)` asserted
3. `requiresImmediateAttention(:Patient_Test, true)` asserted

### Test 9.2: Rule Conflict Resolution

**Conflicting Rules**:
```swrl
# Rule A: Low risk for BP < 120
BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧ swrlb:lessThan(?value, 120)
→ hasRiskLevel(?p, LowRisk)

# Rule B: Moderate risk for BP 110-130
BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧ swrlb:greaterThan(?value, 110) ∧ swrlb:lessThan(?value, 130)
→ hasRiskLevel(?p, ModerateRisk)
```

**Test Value**: BP = 115
**Expected**: Both rules fire (ontology may be inconsistent or use default conflict resolution)

### Test 9.3: Rule with No Matches

**Rule**:
```swrl
BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧ swrlb:greaterThan(?value, 300)
→ impossibleValue(?bp, true)
```

**Test Data**: Normal BP values (90-160)
**Expected**: Rule does not fire

### Test 9.4: Rule Chain Execution

**Rule Chain**:
```swrl
# Step 1: Classify measurement
BloodPressure(?bp) ∧ hasValue(?bp, ?value) ∧ swrlb:greaterThan(?value, 140)
→ hasClassification(?bp, "High")

# Step 2: Assign risk based on classification
Patient(?p) ∧ hasMeasurement(?p, ?bp) ∧ hasClassification(?bp, "High")
→ hasRiskLevel(?p, HighRisk)

# Step 3: Trigger alert based on risk
Patient(?p) ∧ hasRiskLevel(?p, HighRisk)
→ requiresAlert(?p, true)
```

**Test Data**: Patient with BP > 140
**Expected**: All 3 rules execute in sequence

---

## 10. Testing Checklist

### Basic Rule Syntax
- [ ] Rule parses without errors
- [ ] All atoms are valid
- [ ] Built-in functions work correctly
- [ ] Variables are properly bound

### Classification Rules
- [ ] Blood pressure classification (Low, Normal, High)
- [ ] Heart rate classification (Bradycardia, Normal, Tachycardia)
- [ ] HRV classification (Low, Moderate, High)
- [ ] Walking HR classification
- [ ] Pulse classification

### Risk Assessment Rules
- [ ] High risk assignment (BP > 140)
- [ ] Moderate risk assignment (BP 120-140)
- [ ] Low risk assignment (BP < 120)
- [ ] Multiple risk factor detection
- [ ] Comprehensive risk scoring

### Alert Rules
- [ ] Critical BP alert (BP > 180)
- [ ] Low HR alert (HR < 40)
- [ ] Declining HRV trend alert
- [ ] Routine check-up recommendation
- [ ] Emergency alerts trigger correctly

### Temporal Rules
- [ ] Recent measurement detection
- [ ] Nighttime measurement classification
- [ ] Morning surge detection
- [ ] Timestamp comparison works

### Device Validation
- [ ] Clinical device validation
- [ ] Wearable device flagging
- [ ] Home environment tagging
- [ ] Multi-device correlation

### Complex Rules
- [ ] Multi-condition CV risk calculation
- [ ] Athlete profile detection
- [ ] Posture-based classification
- [ ] Reading count validation

### Built-in Functions
- [ ] Math operations (multiply, divide, add, subtract)
- [ ] String operations (contains, upperCase, lowerCase)
- [ ] Date/time functions
- [ ] Boolean operations
- [ ] Comparison operators (>, <, =, ≥, ≤)

### Rule Execution
- [ ] Rules fire in correct order
- [ ] Rule chains execute properly
- [ ] Conflicts handled appropriately
- [ ] Performance acceptable (< 1 second per rule)

### Error Handling
- [ ] Invalid rules detected
- [ ] Unbound variables caught
- [ ] Type mismatches reported
- [ ] Clear error messages

### Integration
- [ ] Works with reasoner plugin
- [ ] Integrates with SPARQL queries
- [ ] Visualizable in graph view
- [ ] Exportable rule set

---

## Appendix A: SWRL Syntax Reference

### Atoms
- **Class atom**: `Class(?x)`
- **Property atom**: `property(?x, ?y)`
- **Data atom**: `property(?x, "value")`
- **Built-in atom**: `swrlb:function(?result, ?arg1, ?arg2)`

### Operators
- **Conjunction (AND)**: `∧`
- **Implication**: `→`

### Built-in Functions (swrlb namespace)

**Math Functions**:
- `swrlb:add(?result, ?x, ?y)`
- `swrlb:subtract(?result, ?x, ?y)`
- `swrlb:multiply(?result, ?x, ?y)`
- `swrlb:divide(?result, ?x, ?y)`
- `swrlb:mod(?result, ?x, ?y)`
- `swrlb:pow(?result, ?base, ?exp)`
- `swrlb:abs(?result, ?x)`
- `swrlb:ceiling(?result, ?x)`
- `swrlb:floor(?result, ?x)`
- `swrlb:round(?result, ?x)`

**Comparison Functions**:
- `swrlb:equal(?x, ?y)`
- `swrlb:notEqual(?x, ?y)`
- `swrlb:lessThan(?x, ?y)`
- `swrlb:lessThanOrEqual(?x, ?y)`
- `swrlb:greaterThan(?x, ?y)`
- `swrlb:greaterThanOrEqual(?x, ?y)`

**String Functions**:
- `swrlb:contains(?string, ?substring)`
- `swrlb:stringLength(?length, ?string)`
- `swrlb:upperCase(?upper, ?string)`
- `swrlb:lowerCase(?lower, ?string)`
- `swrlb:substring(?result, ?string, ?start, ?end)`
- `swrlb:concat(?result, ?string1, ?string2)`

**Boolean Functions**:
- `swrlb:booleanNot(?result, ?bool)`

**Date/Time Functions**:
- `swrlb:yearMonthDay(?date, ?year, ?month, ?day)`
- `swrlb:date(?date, ?year, ?month, ?day)`
- `swrlb:time(?time, ?hour, ?minute, ?second)`

---

## Appendix B: Rule Performance Optimization

1. **Minimize atoms**: Use fewer atoms for faster execution
2. **Filter early**: Apply numeric filters before joins
3. **Avoid negation**: Negation-as-failure is expensive
4. **Use built-ins efficiently**: Cache results when possible
5. **Limit rule chains**: Long chains slow execution

---

## Appendix C: Common Rule Patterns

### Pattern 1: Threshold-Based Classification
```swrl
Measurement(?m) ∧ hasValue(?m, ?v) ∧ swrlb:greaterThan(?v, threshold)
→ classification(?m, "High")
```

### Pattern 2: Multi-Property Aggregation
```swrl
Entity(?e) ∧ prop1(?e, ?v1) ∧ prop2(?e, ?v2) ∧ swrlb:add(?sum, ?v1, ?v2)
→ totalValue(?e, ?sum)
```

### Pattern 3: Conditional Alert
```swrl
Entity(?e) ∧ property(?e, ?v) ∧ swrlb:greaterThan(?v, critical) ∧ status(?e, "Active")
→ requiresAlert(?e, true)
```

### Pattern 4: Property Chain
```swrl
A(?a) ∧ prop1(?a, ?b) ∧ B(?b) ∧ prop2(?b, ?c) ∧ C(?c)
→ derivedRelation(?a, ?c)
```

---

**Test Document Version**: 1.0
**Last Updated**: December 30, 2025
**Compatible With**: Cardiovascular Ontology v1.0
