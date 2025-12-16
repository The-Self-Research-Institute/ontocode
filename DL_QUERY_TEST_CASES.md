# DL Query Tab - Test Cases

## Overview
This document provides comprehensive test cases for the DL Query Tab using the `test-dl-query-ontology.owl` file (Medical domain). DL (Description Logic) queries allow you to find individuals, classes, or properties that match specific logical expressions.

## Test Ontology Structure

### Classes Hierarchy
```
Disease
├── InfectiousDisease (Influenza)
├── ChronicDisease (Diabetes)
│   └── CardiacDisease (HeartDisease)
└── RespiratoryDisease (Asthma)

Symptom
├── Fever
├── Cough
└── ChestPain

Treatment
├── Medication (Antibiotics, Inhaler)
└── Surgery (BypassSurgery)

Patient (Patient001, Patient002, Patient003)

Doctor
└── Cardiologist (DrSmith)
    DrWilson
```

### Properties
**Object Properties**:
- hasSymptom (Disease → Symptom)
- treatedBy (Disease → Treatment)
- diagnosedWith (Patient → Disease)
- treatedByDoctor (Patient → Doctor)
- specializes (Doctor → Disease)

**Data Properties**:
- diseaseName, severity (Disease)
- patientName, age (Patient)
- doctorName, yearsExperience (Doctor)

---

## Basic DL Query Test Cases

### TC-DL-01: Simple Class Query
**Objective**: Find all instances of a class

**DL Query**:
```
Disease
```

**Expected Result**: 
- Influenza, HeartDisease, Asthma, Diabetes

**Explanation**: Returns all individuals that are instances of the Disease class (including subclasses).

---

### TC-DL-02: Subclass Query
**Objective**: Find instances of a specific subclass

**DL Query**:
```
InfectiousDisease
```

**Expected Result**: 
- Influenza

**Explanation**: Returns only infectious disease instances.

---

### TC-DL-03: Property Restriction (existential)
**Objective**: Find diseases that have at least one symptom

**DL Query**:
```
Disease and hasSymptom some Symptom
```

**Expected Result**: 
- Influenza, HeartDisease, Asthma

**Explanation**: `some` means "at least one" - finds diseases with any symptom.

---

### TC-DL-04: Property Restriction (universal)
**Objective**: Find diseases where all symptoms are fever

**DL Query**:
```
Disease and hasSymptom only Fever
```

**Expected Result**: 
- Diseases that only have fever symptoms (if any)

**Explanation**: `only` means "if there is a symptom, it must be fever".

---

### TC-DL-05: Specific Value Restriction
**Objective**: Find diseases treated by surgery

**DL Query**:
```
Disease and treatedBy value BypassSurgery
```

**Expected Result**: 
- HeartDisease

**Explanation**: `value` restricts to a specific individual.

---

### TC-DL-06: Negation (NOT)
**Objective**: Find diseases that are NOT infectious

**DL Query**:
```
Disease and not InfectiousDisease
```

**Expected Result**: 
- HeartDisease, Asthma, Diabetes

**Explanation**: Finds all diseases except infectious ones.

---

### TC-DL-07: Intersection (AND)
**Objective**: Find chronic diseases with symptoms

**DL Query**:
```
ChronicDisease and hasSymptom some Symptom
```

**Expected Result**: 
- HeartDisease (chronic + has symptom)

**Explanation**: Combines two conditions with AND.

---

### TC-DL-08: Union (OR)
**Objective**: Find diseases that are either infectious or respiratory

**DL Query**:
```
InfectiousDisease or RespiratoryDisease
```

**Expected Result**: 
- Influenza (infectious), Asthma (respiratory)

**Explanation**: Matches either condition.

---

### TC-DL-09: Data Property Restriction
**Objective**: Find severe diseases

**DL Query**:
```
Disease and severity value "severe"
```

**Expected Result**: 
- HeartDisease

**Explanation**: Filters by data property value.

---

### TC-DL-10: Cardinality Restriction (min)
**Objective**: Find diseases with at least 2 symptoms

**DL Query**:
```
Disease and hasSymptom min 2 Symptom
```

**Expected Result**: 
- Influenza (has fever + cough)

**Explanation**: `min` specifies minimum number of relationships.

---

## Intermediate DL Query Test Cases

### TC-DL-11: Cardinality Restriction (max)
**Objective**: Find diseases with at most 1 symptom

**DL Query**:
```
Disease and hasSymptom max 1 Symptom
```

**Expected Result**: 
- HeartDisease, Asthma

**Explanation**: `max` limits maximum number of relationships.

---

### TC-DL-12: Exact Cardinality
**Objective**: Find diseases with exactly 2 symptoms

**DL Query**:
```
Disease and hasSymptom exactly 2 Symptom
```

**Expected Result**: 
- Influenza

**Explanation**: `exactly` requires precise count.

---

### TC-DL-13: Complex Negation
**Objective**: Find patients not diagnosed with chronic diseases

**DL Query**:
```
Patient and not (diagnosedWith some ChronicDisease)
```

**Expected Result**: 
- Patient002 (has Influenza, which is infectious not chronic)

**Explanation**: Negates the entire existential restriction.

---

### TC-DL-14: Nested Restrictions
**Objective**: Find patients treated by cardiologists

**DL Query**:
```
Patient and treatedByDoctor some Cardiologist
```

**Expected Result**: 
- Patient001 (treated by DrSmith, who is a cardiologist)

**Explanation**: Property restriction with specific class.

---

### TC-DL-15: Multiple Conditions
**Objective**: Find chronic diseases treated by surgery

**DL Query**:
```
ChronicDisease and treatedBy some Surgery
```

**Expected Result**: 
- HeartDisease

**Explanation**: Combines class membership with property restriction.

---

### TC-DL-16: Self Restriction
**Objective**: Find doctors who specialize in diseases (self-loop)

**DL Query**:
```
Doctor and specializes some Disease
```

**Expected Result**: 
- DrSmith (specializes in HeartDisease)

**Explanation**: Finds doctors with specialization relationships.

---

### TC-DL-17: Property Chain
**Objective**: Find patients with symptoms (indirect via disease)

**DL Query**:
```
Patient and diagnosedWith some (Disease and hasSymptom some Symptom)
```

**Expected Result**: 
- Patient001, Patient002, Patient003

**Explanation**: Chains properties: patient → disease → symptom.

---

### TC-DL-18: Disjoint Classes
**Objective**: Find entities that are symptoms but not fever

**DL Query**:
```
Symptom and not Fever
```

**Expected Result**: 
- CoughSymptom, ChestPainSymptom

**Explanation**: Set difference between classes.

---

### TC-DL-19: Qualified Cardinality
**Objective**: Find diseases with at least 1 cough symptom

**DL Query**:
```
Disease and hasSymptom min 1 Cough
```

**Expected Result**: 
- Influenza, Asthma

**Explanation**: Qualified cardinality restricts by specific class.

---

### TC-DL-20: Empty Query (Top)
**Objective**: Find all individuals in ontology

**DL Query**:
```
owl:Thing
```

**Expected Result**: 
- All individuals from all classes

**Explanation**: `owl:Thing` is the top class containing everything.

---

## Advanced DL Query Test Cases

### TC-DL-21: Complex Boolean Expression
**Objective**: Diseases that are (chronic OR infectious) AND have symptoms

**DL Query**:
```
(ChronicDisease or InfectiousDisease) and hasSymptom some Symptom
```

**Expected Result**: 
- HeartDisease (chronic with symptom), Influenza (infectious with symptom)

**Explanation**: Combines union, intersection, and restriction.

---

### TC-DL-22: Value Restriction with Negation
**Objective**: Diseases not treated by medication

**DL Query**:
```
Disease and not (treatedBy some Medication)
```

**Expected Result**: 
- HeartDisease (treated by surgery)

**Explanation**: Negates existential over specific class.

---

### TC-DL-23: Multiple Property Restrictions
**Objective**: Diseases with both fever and cough

**DL Query**:
```
Disease and hasSymptom some Fever and hasSymptom some Cough
```

**Expected Result**: 
- Influenza

**Explanation**: Requires multiple property values.

---

### TC-DL-24: Data Property Comparison
**Objective**: Patients older than 40

**DL Query**:
```
Patient and age some integer[>= 40]
```

**Expected Result**: 
- Patient001 (age 45)

**Explanation**: Uses data range facets for comparison.

---

### TC-DL-25: Has Value (Individuals)
**Objective**: Patients diagnosed with heart disease specifically

**DL Query**:
```
Patient and diagnosedWith value HeartDisease
```

**Expected Result**: 
- Patient001

**Explanation**: Finds patients with specific disease instance.

---

### TC-DL-26: Inverse Properties (if defined)
**Objective**: Symptoms that are symptoms of some disease

**DL Query**:
```
Symptom and inverse(hasSymptom) some Disease
```

**Expected Result**: 
- FeverSymptom, CoughSymptom, ChestPainSymptom

**Explanation**: Uses inverse property to reverse relationship direction.

---

### TC-DL-27: Universal Restriction with Negation
**Objective**: Diseases that ONLY have non-fever symptoms

**DL Query**:
```
Disease and hasSymptom only (Symptom and not Fever)
```

**Expected Result**: 
- Asthma (only has cough), HeartDisease (only has chest pain)

**Explanation**: Universal quantification with negation.

---

### TC-DL-28: Complex Cardinality
**Objective**: Patients diagnosed with exactly 1 disease

**DL Query**:
```
Patient and diagnosedWith exactly 1 Disease
```

**Expected Result**: 
- All patients (Patient001, Patient002, Patient003)

**Explanation**: Each patient has exactly one diagnosis in test data.

---

### TC-DL-29: Enumeration (OneOf)
**Objective**: Specific set of diseases

**DL Query**:
```
{Influenza, Asthma}
```

**Expected Result**: 
- Influenza, Asthma

**Explanation**: Explicitly enumerates individuals.

---

### TC-DL-30: Has Self
**Objective**: Properties that relate to themselves (reflexive)

**DL Query**:
```
Disease and hasSymptom Self
```

**Expected Result**: 
- None (diseases don't have themselves as symptoms)

**Explanation**: `Self` checks for reflexive relationships.

---

## DL Query UI Test Cases

### UI-DL-01: Query Input
**Test**: Enter and execute query
1. Open DL Query tab
2. Type: `Disease`
3. Click "Execute Query"
4. View results

**Expected**: All disease instances displayed

---

### UI-DL-02: Auto-complete
**Test**: Class name suggestion
1. Type: `Chr`
2. Observe auto-complete suggestions

**Expected**: Suggests `ChronicDisease`

---

### UI-DL-03: Syntax Highlighting
**Test**: DL syntax coloring
1. Type: `Disease and hasSymptom some Symptom`
2. Observe keyword highlighting

**Expected**: Keywords `and`, `some` colored differently

---

### UI-DL-04: Result Types
**Test**: Switch between result views
1. Execute query: `Disease`
2. Toggle "Show Subclasses"
3. Toggle "Show Superclasses"

**Expected**: Different result sets displayed

---

### UI-DL-05: Error Messages
**Test**: Invalid syntax handling
1. Type: `Disease and and Symptom`
2. Execute query

**Expected**: Clear syntax error message

---

### UI-DL-06: Query History
**Test**: Access previous queries
1. Execute multiple queries
2. Click history button
3. Select previous query

**Expected**: Query reloaded into input

---

### UI-DL-07: Result Export
**Test**: Export query results
1. Execute query with results
2. Click "Export" button
3. Save as CSV

**Expected**: Results exported to file

---

### UI-DL-08: Query Templates
**Test**: Use predefined templates
1. Click "Templates" dropdown
2. Select "Property Restriction"
3. Template loads

**Expected**: Example query appears: `Class and property some Class`

---

## DL Query Syntax Reference

### Basic Syntax
| Syntax | Meaning | Example |
|--------|---------|---------|
| `Class` | Class membership | `Disease` |
| `and` | Intersection | `A and B` |
| `or` | Union | `A or B` |
| `not` | Negation | `not A` |
| `some` | Existential (∃) | `hasSymptom some Fever` |
| `only` | Universal (∀) | `hasSymptom only Fever` |
| `value` | Individual value | `treatedBy value Inhaler` |
| `min` | Minimum cardinality | `hasSymptom min 2 Symptom` |
| `max` | Maximum cardinality | `hasSymptom max 1 Symptom` |
| `exactly` | Exact cardinality | `hasSymptom exactly 2 Symptom` |
| `Self` | Reflexive | `hasParent Self` |
| `inverse(P)` | Inverse property | `inverse(hasSymptom)` |

### Data Ranges
| Syntax | Meaning | Example |
|--------|---------|---------|
| `integer[> n]` | Greater than | `age some integer[> 40]` |
| `integer[>= n]` | Greater or equal | `age some integer[>= 40]` |
| `integer[< n]` | Less than | `age some integer[< 65]` |
| `integer[<= n]` | Less or equal | `age some integer[<= 65]` |
| `string[length n]` | String length | `name some string[length 5]` |

---

## Common Query Patterns

### 1. Find all instances of a class
```
ClassName
```

### 2. Find subclasses with specific property
```
ParentClass and hasProperty some TargetClass
```

### 3. Find entities with multiple conditions
```
ClassA and propertyB some ClassC and propertyD value Individual
```

### 4. Find entities WITHOUT a property
```
Class and not (hasProperty some Thing)
```

### 5. Find entities with specific property count
```
Class and hasProperty min 2 TargetClass
```

### 6. Chain multiple properties
```
Class and property1 some (Class2 and property2 some Class3)
```

---

## Troubleshooting DL Queries

### Issue 1: No Results
**Problem**: Query returns empty set
**Solutions**:
- Check class names are correct (case-sensitive)
- Verify property domains/ranges
- Ensure individuals exist matching criteria
- Use `owl:Thing` to test if any individuals load

### Issue 2: Too Many Results
**Problem**: Query returns everything
**Solutions**:
- Add more specific restrictions
- Use `and` to narrow criteria
- Check for typos in class names

### Issue 3: Syntax Error
**Problem**: Parser rejects query
**Solutions**:
- Match parentheses: `(A and B) or C`
- Use proper keywords: `some`, `only`, not `has`, `all`
- Quote string values: `value "text"`

### Issue 4: Unexpected Results
**Problem**: Results don't match expectations
**Solutions**:
- Review class hierarchy (subclasses included)
- Check inferred vs asserted triples
- Verify property domains/ranges
- Use reasoner to infer additional relationships

---

## Test Execution Checklist

- [ ] Load test-dl-query-ontology.owl
- [ ] Execute TC-DL-01 through TC-DL-10 (Basic)
- [ ] Execute TC-DL-11 through TC-DL-20 (Intermediate)
- [ ] Execute TC-DL-21 through TC-DL-30 (Advanced)
- [ ] Test UI functionality (UI-DL-01 through UI-DL-08)
- [ ] Verify syntax highlighting
- [ ] Test auto-complete
- [ ] Test error handling
- [ ] Export results
- [ ] Check query history

---

## Integration with Reasoner

### Inferred vs Asserted
- **Asserted**: Explicitly stated in ontology
- **Inferred**: Derived by reasoner from axioms

**Example**:
```
# Query for inferred relationships
Patient and diagnosedWith some (Disease and hasSymptom some ChestPain)
```

With reasoner enabled:
- Infers transitive properties
- Computes class hierarchies
- Derives implicit relationships

---

## Performance Considerations

1. **Simple queries** (< 0.1s): Direct class membership
2. **Medium queries** (0.1-1s): Property restrictions with cardinality
3. **Complex queries** (1-5s): Multiple nested restrictions
4. **Very complex** (> 5s): Universal quantification + negation + cardinality

**Optimization Tips**:
- Use specific classes instead of `owl:Thing`
- Limit nesting depth
- Avoid universal quantification when possible
- Use cardinality constraints wisely

---

## References

- **OWL 2 Primer**: https://www.w3.org/TR/owl2-primer/
- **DL Query Plugin**: GraphDB DL Query documentation
- **Description Logic**: https://en.wikipedia.org/wiki/Description_logic
- **Manchester Syntax**: https://www.w3.org/TR/owl2-manchester-syntax/

---

## Example Test Session

```
1. Load test-dl-query-ontology.owl into GraphDB
2. Open DL Query tab
3. Query: Disease
   Result: 4 individuals (Influenza, HeartDisease, Asthma, Diabetes)
4. Query: Disease and hasSymptom some Fever
   Result: 1 individual (Influenza)
5. Query: Patient and treatedByDoctor some Cardiologist
   Result: 1 individual (Patient001)
6. Query: ChronicDisease and not CardiacDisease
   Result: 1 individual (Diabetes)
7. Export results to CSV
8. Verify all results match expected values
```

---

## Conclusion

This comprehensive test suite covers:
- **30 DL Query test cases** (basic to advanced)
- **8 UI test cases** for user interface
- **Syntax reference** for quick lookup
- **Common patterns** for typical queries
- **Troubleshooting guide** for issues
- **Performance tips** for optimization

Use these test cases to validate DL Query functionality, train users, and ensure consistent behavior across updates.
