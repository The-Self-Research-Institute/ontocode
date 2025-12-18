# SWRL Editor Plugin - Test Cases

## Overview
This document provides comprehensive test cases for the SWRL (Semantic Web Rule Language) Editor Plugin using the `test-swrl-ontology.owl` file.

## Test Ontology Structure

### Classes
- Person (root)
  - Student
    - UndergraduateStudent
    - GraduateStudent
    - HonorsStudent
  - Professor
- Course
  - AdvancedCourse
- Department

### Object Properties
- enrolledIn (Student → Course)
- teaches (Professor → Course)
- advisedBy (Student → Professor)
- worksIn (Person → Department)
- hasMentor (Student → Professor)

### Data Properties
- age (Person → integer)
- gpa (Student → float)
- credits (Course → integer)
- name (Person → string)
- courseLevel (Course → integer)

### Individuals
- JohnDoe (UndergraduateStudent, age=20, gpa=3.8)
- JaneSmith (GraduateStudent, age=24, gpa=3.9)
- DrJohnson (Professor, age=45)
- CS101 (Course, credits=3, level=100)
- CS501 (Course, credits=4, level=500)
- ComputerScience (Department)

---

## Test Cases

### TC-SWRL-01: Basic Class Inference Rule
**Objective**: Test simple class membership inference

**SWRL Rule**:
```
Student(?s) ∧ gpa(?s, ?g) ∧ swrlb:greaterThan(?g, 3.75) → HonorsStudent(?s)
```

**Expected Result**: 
- JohnDoe should be inferred as HonorsStudent (gpa=3.8)
- JaneSmith should be inferred as HonorsStudent (gpa=3.9)

**Test Steps**:
1. Load test-swrl-ontology.owl
2. Create the rule in SWRL Editor
3. Execute reasoner
4. Query for HonorsStudent instances
5. Verify both students are returned

---

### TC-SWRL-02: Advanced Course Classification
**Objective**: Test property-based classification

**SWRL Rule**:
```
Course(?c) ∧ courseLevel(?c, ?l) ∧ swrlb:greaterThanOrEqual(?l, 500) → AdvancedCourse(?c)
```

**Expected Result**: 
- CS501 should be classified as AdvancedCourse
- CS101 should NOT be classified as AdvancedCourse

**Test Steps**:
1. Create the rule
2. Run reasoner
3. Query AdvancedCourse class
4. Verify only CS501 is returned

---

### TC-SWRL-03: Relationship Inference (Mentor Assignment)
**Objective**: Test object property inference

**SWRL Rule**:
```
Student(?s) ∧ advisedBy(?s, ?p) ∧ Professor(?p) → hasMentor(?s, ?p)
```

**Expected Result**: 
- JohnDoe hasMentor DrJohnson
- JaneSmith hasMentor DrJohnson

**Test Steps**:
1. Create the rule
2. Execute reasoner
3. Query hasMentor property
4. Verify both students have DrJohnson as mentor

---

### TC-SWRL-04: Complex Rule with Multiple Conditions
**Objective**: Test multi-condition SWRL rule

**SWRL Rule**:
```
Student(?s) ∧ enrolledIn(?s, ?c) ∧ AdvancedCourse(?c) ∧ gpa(?s, ?g) ∧ swrlb:greaterThan(?g, 3.5) → GraduateStudent(?s)
```

**Expected Result**: 
- Students enrolled in advanced courses with GPA > 3.5 are classified as GraduateStudent

**Test Steps**:
1. Ensure CS501 is marked as AdvancedCourse (from TC-SWRL-02)
2. Create the complex rule
3. Run reasoner
4. Verify correct classifications

---

### TC-SWRL-05: Built-in Function Test (Age Calculation)
**Objective**: Test SWRL built-in functions

**SWRL Rule**:
```
Person(?p) ∧ age(?p, ?a) ∧ swrlb:lessThan(?a, 25) → Student(?p)
```

**Expected Result**: 
- JohnDoe (age=20) and JaneSmith (age=24) remain students
- DrJohnson (age=45) is NOT inferred as student

**Test Steps**:
1. Create rule with lessThan built-in
2. Execute reasoner
3. Verify age-based classification

---

### TC-SWRL-06: Property Chain Reasoning
**Objective**: Test transitive reasoning through properties

**SWRL Rule**:
```
Student(?s) ∧ enrolledIn(?s, ?c) ∧ teaches(?p, ?c) → advisedBy(?s, ?p)
```

**Expected Result**: 
- Students enrolled in courses should be advised by professors who teach those courses

**Test Steps**:
1. Create property chain rule
2. Run reasoner
3. Verify derived advisedBy relationships

---

### TC-SWRL-07: Data Property Manipulation
**Objective**: Test data property value inference

**SWRL Rule**:
```
Course(?c) ∧ credits(?c, ?cr) ∧ swrlb:multiply(?newCr, ?cr, 2) → credits(?c, ?newCr)
```

**Note**: This rule attempts to modify data properties (may not be supported by all reasoners)

**Test Steps**:
1. Create data manipulation rule
2. Check if reasoner supports data property inference
3. Document behavior

---

### TC-SWRL-08: Same Individual Test
**Objective**: Test sameAs reasoning

**SWRL Rule**:
```
Person(?p1) ∧ Person(?p2) ∧ name(?p1, ?n) ∧ name(?p2, ?n) ∧ age(?p1, ?a) ∧ age(?p2, ?a) → sameAs(?p1, ?p2)
```

**Expected Result**: 
- Individuals with same name and age should be inferred as sameAs

**Test Steps**:
1. Create sameAs rule
2. Add test individuals with duplicate names/ages
3. Run reasoner
4. Check for sameAs inferences

---

### TC-SWRL-09: Negative Property Test (DifferentFrom)
**Objective**: Test inequality constraints

**SWRL Rule**:
```
Student(?s) ∧ Professor(?p) ∧ differentFrom(?s, ?p) → advisedBy(?s, ?p)
```

**Expected Result**: 
- Rule should not create invalid relationships

**Test Steps**:
1. Create rule with differentFrom
2. Execute reasoner
3. Verify no circular or invalid relationships

---

### TC-SWRL-10: Rule with Universal Quantification
**Objective**: Test rules affecting all instances

**SWRL Rule**:
```
Student(?s) ∧ gpa(?s, ?g) ∧ swrlb:equal(?g, 4.0) → HonorsStudent(?s)
```

**Expected Result**: 
- Only students with exact GPA of 4.0 are honored

**Test Steps**:
1. Add student with GPA 4.0
2. Create equality rule
3. Run reasoner
4. Verify only 4.0 GPA students are HonorsStudent

---

## SWRL Built-in Functions Test Matrix

### Mathematical Built-ins
| Function | Test Rule | Expected Behavior |
|----------|-----------|-------------------|
| `swrlb:add` | `gpa(?s, ?g) ∧ swrlb:add(?total, ?g, 1.0)` | Adds values |
| `swrlb:subtract` | `age(?p, ?a) ∧ swrlb:subtract(?years, ?a, 18)` | Subtracts values |
| `swrlb:multiply` | `credits(?c, ?cr) ∧ swrlb:multiply(?total, ?cr, 2)` | Multiplies values |
| `swrlb:divide` | `gpa(?s, ?g) ∧ swrlb:divide(?half, ?g, 2)` | Divides values |

### Comparison Built-ins
| Function | Test Rule | Expected Behavior |
|----------|-----------|-------------------|
| `swrlb:equal` | `age(?p, ?a) ∧ swrlb:equal(?a, 20)` | Equality check |
| `swrlb:notEqual` | `gpa(?s, ?g) ∧ swrlb:notEqual(?g, 0.0)` | Inequality check |
| `swrlb:lessThan` | `age(?p, ?a) ∧ swrlb:lessThan(?a, 25)` | Less than check |
| `swrlb:greaterThan` | `gpa(?s, ?g) ∧ swrlb:greaterThan(?g, 3.5)` | Greater than check |

### String Built-ins
| Function | Test Rule | Expected Behavior |
|----------|-----------|-------------------|
| `swrlb:contains` | `name(?p, ?n) ∧ swrlb:contains(?n, "John")` | String contains |
| `swrlb:stringConcat` | `name(?p, ?n) ∧ swrlb:stringConcat(?full, ?n, " PhD")` | Concatenation |

---

## SWRL Editor UI Test Cases

### UI-01: Rule Creation
**Test**: Create new SWRL rule through UI
1. Click "Add Rule" button
2. Enter rule name: "HighGPAStudent"
3. Enter rule body
4. Verify syntax highlighting
5. Save rule

**Expected**: Rule saved and appears in rule list

---

### UI-02: Rule Validation
**Test**: Validate SWRL syntax
1. Enter invalid SWRL syntax
2. Click validate button
3. Observe error messages

**Expected**: Clear error messages with line numbers

---

### UI-03: Auto-completion
**Test**: Test entity auto-completion
1. Type `Student(?` in rule editor
2. Observe auto-complete suggestions
3. Select from dropdown

**Expected**: Valid variables and entities suggested

---

### UI-04: Rule Execution
**Test**: Execute single rule
1. Select rule from list
2. Click "Execute Rule" button
3. View execution results

**Expected**: Inferences displayed, reasoner output shown

---

### UI-05: Bulk Rule Management
**Test**: Enable/disable multiple rules
1. Select multiple rules using checkboxes
2. Click "Enable Selected" or "Disable Selected"
3. Execute reasoner

**Expected**: Only enabled rules are executed

---

### UI-06: Rule Export/Import
**Test**: Export and import rules
1. Create multiple SWRL rules
2. Export to SWRL file
3. Clear all rules
4. Import from file

**Expected**: All rules restored with correct syntax

---

## Performance Test Cases

### PERF-01: Large Dataset Performance
**Test**: Test with 1000+ individuals
1. Load ontology with 1000 students
2. Create rule affecting all students
3. Measure execution time

**Expected**: Completes within reasonable time (< 30 seconds)

---

### PERF-02: Complex Rule Execution
**Test**: Execute 50+ complex rules
1. Create 50 rules with multiple conditions
2. Execute all rules simultaneously
3. Monitor memory usage

**Expected**: No memory leaks, stable performance

---

## Integration Test Cases

### INT-01: GraphDB Integration
**Test**: Verify SWRL execution on GraphDB backend
1. Save rules to GraphDB
2. Execute reasoner on server
3. Retrieve inferred triples

**Expected**: Correct integration with GraphDB reasoner

---

### INT-02: Reasoner Compatibility
**Test**: Test with different reasoners
- Pellet
- HermiT  
- ELK
- GraphDB

**Expected**: Rules execute correctly on all reasoners

---

## Error Handling Test Cases

### ERR-01: Invalid Syntax
**Test**: Enter malformed SWRL rule
```
Student?s) ∧ gpa(?s, ?g → HonorsStudent(?s)
```
**Expected**: Clear syntax error message

---

### ERR-02: Undefined Entity
**Test**: Reference non-existent class
```
Student(?s) ∧ NonExistentClass(?x) → HonorsStudent(?s)
```
**Expected**: Warning about undefined entities

---

### ERR-03: Type Mismatch
**Test**: Use incompatible data types
```
Student(?s) ∧ age(?s, "twenty") → HonorsStudent(?s)
```
**Expected**: Type error reported

---

## Regression Test Suite

Run all test cases after each plugin update to ensure:
1. ✅ Rule creation and editing works
2. ✅ Syntax validation is accurate
3. ✅ Rule execution produces correct inferences
4. ✅ UI responds correctly to user actions
5. ✅ Integration with backend is stable
6. ✅ Performance remains acceptable

---

## Test Execution Checklist

- [ ] Load test-swrl-ontology.owl
- [ ] Execute TC-SWRL-01 through TC-SWRL-10
- [ ] Test all built-in functions
- [ ] Verify UI functionality (UI-01 through UI-06)
- [ ] Run performance tests
- [ ] Test error handling
- [ ] Verify export/import functionality
- [ ] Document any issues or unexpected behavior

---

## Known Limitations

1. Some reasoners don't support all SWRL built-ins
2. Data property inference may be limited
3. Performance degrades with very complex rules
4. Circular rule dependencies may cause infinite loops

---

## Support & References

- **SWRL Specification**: https://www.w3.org/Submission/SWRL/
- **SWRL Built-ins**: https://www.w3.org/Submission/SWRL/#swrlb
- **Protégé SWRL Tab**: https://protegewiki.stanford.edu/wiki/SWRLTab
