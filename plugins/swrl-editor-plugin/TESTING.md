# SWRL Editor Plugin - Testing Document

**Plugin:** swrl-editor-plugin v1.1.2  
**Category:** Editor  
**Last Updated:** April 2026

---

## Table of Contents
1. [Overview](#overview)
2. [Test Environment Setup](#test-environment-setup)
3. [Rule Editor Tests](#rule-editor-tests)
4. [Validation Tests](#validation-tests)
5. [Rule Execution Tests](#rule-execution-tests)
6. [Rule Management Tests](#rule-management-tests)
7. [Template Tests](#template-tests)
8. [Built-in Function Tests](#built-in-function-tests)
9. [VS Code Integration Tests](#vs-code-integration-tests)
10. [Error Handling Tests](#error-handling-tests)
11. [Performance Tests](#performance-tests)

---

## Overview

The SWRL Editor Plugin provides a visual SWRL (Semantic Web Rule Language) editor and validator for ontologies. Supports rule creation, editing, real-time syntax validation, 40+ built-in templates, and rule execution against loaded ontologies.

### Components Under Test
- `SWRLEditor.tsx` — Main editor with syntax highlighting, validation, templates, help, management, execution, and statistics

### API Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/swrl/{projectId}/validate` | Validate rule syntax |
| POST | `/api/swrl/{projectId}/execute` | Execute rule |
| GET | `/api/swrl/{projectId}/rules` | List all rules |
| POST | `/api/swrl/{projectId}/rules` | Create rule |
| PUT | `/api/swrl/{projectId}/rules/{id}` | Update rule |
| DELETE | `/api/swrl/{projectId}/rules/{id}` | Delete rule |

### SWRL Atom Types
| Atom Type | Syntax | Example |
|-----------|--------|---------|
| Class | `ClassName(?var)` | `Student(?s)` |
| Data Property | `propName(?subj, ?val)` | `gpa(?s, ?g)` |
| Object Property | `propName(?subj, ?obj)` | `advisedBy(?s, ?p)` |
| Built-In | `swrlb:func(?args)` | `swrlb:greaterThan(?g, 3.75)` |
| Same Individual | `sameAs(?x, ?y)` | `sameAs(?a, ?b)` |
| Different Individuals | `differentFrom(?x, ?y)` | `differentFrom(?a, ?b)` |

---

## Test Environment Setup

### Prerequisites
- OntoCode platform running with SWRL service (`ontology-swrl`)
- GraphDB with loaded test ontology (`test-swrl-ontology.owl`)

### Test Ontology Structure
**Classes:** Person → (Student → UndergraduateStudent, GraduateStudent, HonorsStudent), Professor; Course → AdvancedCourse; Department  
**Object Properties:** enrolledIn, teaches, advisedBy, worksIn, hasMentor  
**Data Properties:** age (integer), gpa (float), credits (integer), name (string), courseLevel (integer)  

**Individuals:**
| Individual | Type | Properties |
|-----------|------|------------|
| JohnDoe | UndergraduateStudent | age=20, gpa=3.8 |
| JaneSmith | GraduateStudent | age=24, gpa=3.9 |
| DrJohnson | Professor | age=45 |
| CS101 | Course | credits=3, courseLevel=100 |
| CS501 | Course | credits=4, courseLevel=500 |
| ComputerScience | Department | — |

---

## Rule Editor Tests

### TC-SW-001: Editor Initialization
**Objective:** Verify SWRL editor loads correctly  
**Steps:**
1. Open SWRL Editor plugin
2. Verify editor area is visible

**Expected Results:**
- Rule text area visible and editable
- Rule name input field available
- "Validate", "Execute", "Save" buttons visible
- Templates dropdown accessible
- Help section available

---

### TC-SW-002: Rule Input with Syntax Highlighting
**Objective:** Verify syntax highlighting for SWRL rules  
**Steps:**
1. Enter rule: `Student(?s) ∧ gpa(?s, ?g) ∧ swrlb:greaterThan(?g, 3.75) → HonorsStudent(?s)`
2. Observe syntax coloring

**Expected Results:**
- Class atoms (Student, HonorsStudent) in distinct color
- Variables (?s, ?g) in different color
- Built-in atoms (swrlb:greaterThan) highlighted
- Operators (∧, →) highlighted
- Literals (3.75) in number color

---

### TC-SW-003: Rule Name Input
**Objective:** Verify rule naming  
**Steps:**
1. Enter rule name: "Honors Student Classification"
2. Enter rule text

**Expected Results:**
- Name field accepts text
- Name displayed in rule list
- Name persisted on save

---

## Validation Tests

### TC-SW-004: Valid Rule Validation
**Objective:** Verify valid SWRL rule passes validation  
**Rule:** `Student(?s) ∧ gpa(?s, ?g) ∧ swrlb:greaterThan(?g, 3.75) → HonorsStudent(?s)`  
**Steps:**
1. Enter rule
2. Click "Validate"

**Expected Results:**
- `valid: true`
- `errorMessage: null`
- Parsed atoms displayed:
  - ClassAtom: Student(?s)
  - DataPropertyAtom: gpa(?s, ?g)
  - BuiltInAtom: swrlb:greaterThan(?g, 3.75)
  - ClassAtom: HonorsStudent(?s)
- `usedClasses`: [Student, HonorsStudent]
- `usedProperties`: [gpa]
- `usedBuiltIns`: [swrlb:greaterThan]
- Green success indicator

---

### TC-SW-005: Invalid Rule — Missing Consequent
**Objective:** Verify error for rule without head (→ part)  
**Rule:** `Student(?s) ∧ gpa(?s, ?g)`  
**Expected Results:**
- `valid: false`
- `errorMessage`: indicates missing consequent/implication
- Suggestions provided

---

### TC-SW-006: Invalid Rule — Unknown Class
**Objective:** Verify warning for undeclared class reference  
**Rule:** `NonExistentClass(?x) → Student(?x)`  
**Expected Results:**
- Warning: "Class 'NonExistentClass' not found in ontology"
- Rule may still be syntactically valid
- Suggestion to check class name

---

### TC-SW-007: Invalid Rule — Unbound Variable
**Objective:** Verify error for variable used only in head  
**Rule:** `Student(?s) → HonorsStudent(?x)`  
**Expected Results:**
- Warning or error: variable ?x appears only in consequent
- Suggestion: ensure all head variables appear in body

---

### TC-SW-008: Invalid Rule — Wrong Built-in Arguments
**Objective:** Verify error for incorrect built-in function usage  
**Rule:** `Student(?s) ∧ swrlb:greaterThan(?s) → HonorsStudent(?s)`  
**Expected Results:**
- `valid: false`
- Error: "swrlb:greaterThan requires 2 arguments, got 1"

---

### TC-SW-009: Real-Time Validation
**Objective:** Verify validation triggers as user types  
**Steps:**
1. Start typing a rule
2. Observe validation indicators updating in real-time

**Expected Results:**
- Validation updates after brief debounce
- Red indicator for invalid state
- Green indicator when valid
- No blocking of input while validating

---

## Rule Execution Tests

### TC-SW-010: Basic Class Inference
**Objective:** Test class membership inference  
**Rule:** `Student(?s) ∧ gpa(?s, ?g) ∧ swrlb:greaterThan(?g, 3.75) → HonorsStudent(?s)`  
**Steps:**
1. Create and save the rule
2. Click "Execute"

**Expected Results:**
- JohnDoe inferred as HonorsStudent (gpa=3.8 > 3.75)
- JaneSmith inferred as HonorsStudent (gpa=3.9 > 3.75)
- Inferred axioms displayed in results section
- Execution time shown

---

### TC-SW-011: Advanced Course Classification
**Objective:** Test property-based classification  
**Rule:** `Course(?c) ∧ courseLevel(?c, ?l) ∧ swrlb:greaterThanOrEqual(?l, 500) → AdvancedCourse(?c)`  
**Expected Results:**
- CS501 classified as AdvancedCourse (courseLevel=500 ≥ 500)
- CS101 NOT classified as AdvancedCourse (courseLevel=100 < 500)

---

### TC-SW-012: Object Property Inference
**Objective:** Test relationship inference  
**Rule:** `Student(?s) ∧ advisedBy(?s, ?p) ∧ Professor(?p) → hasMentor(?s, ?p)`  
**Expected Results:**
- hasMentor relationships inferred for advised students
- New object property assertions created

---

### TC-SW-013: Math Built-in Execution
**Objective:** Test mathematical built-in functions  
**Rule:** `Student(?s) ∧ gpa(?s, ?g) ∧ swrlb:multiply(?bonus, ?g, 1000) → scholarshipAmount(?s, ?bonus)`  
**Expected Results:**
- JohnDoe: scholarshipAmount = 3800
- JaneSmith: scholarshipAmount = 3900
- Computed values correct

---

### TC-SW-014: String Built-in Execution
**Objective:** Test string built-in functions  
**Rule:** `Person(?p) ∧ name(?p, ?n) ∧ swrlb:startsWith(?n, "Dr") → Professor(?p)`  
**Expected Results:**
- Individuals whose name starts with "Dr" classified as Professor
- String comparison case-sensitive

---

### TC-SW-015: Multiple Rules Execution
**Objective:** Test executing multiple rules in sequence  
**Steps:**
1. Create 3 rules
2. Enable all 3
3. Execute all

**Expected Results:**
- All rules execute in order
- Results from rule 1 available to rule 2 (chaining)
- Combined inferred axioms displayed
- No rule interference

---

## Rule Management Tests

### TC-SW-016: Create Rule
**Objective:** Verify creating and saving a new rule  
**Steps:**
1. Enter rule name and text
2. Click Save

**Expected Results:**
- Rule saved with unique ID
- Appears in rules list
- `enabled: true` by default
- Timestamps set (createdAt, updatedAt)

---

### TC-SW-017: Update Rule
**Objective:** Verify editing an existing rule  
**Steps:**
1. Select saved rule from list
2. Modify rule text
3. Save changes

**Expected Results:**
- Rule text updated
- `updatedAt` timestamp refreshed
- Rule ID unchanged
- Previous version not retained

---

### TC-SW-018: Delete Rule
**Objective:** Verify deleting a rule  
**Steps:**
1. Select a rule
2. Click Delete
3. Confirm deletion

**Expected Results:**
- Rule removed from list
- Confirmation prompt before deletion
- Associated inferred axioms optionally removed

---

### TC-SW-019: Enable/Disable Rule
**Objective:** Verify toggling rule enabled state  
**Steps:**
1. Select an enabled rule
2. Toggle to disabled
3. Execute all rules

**Expected Results:**
- Disabled rule skipped during execution
- Visual indicator (grey/strike-through) for disabled
- Re-enabling restores functionality

---

### TC-SW-020: Duplicate Rule
**Objective:** Verify duplicating an existing rule  
**Steps:**
1. Select a rule
2. Click "Duplicate"

**Expected Results:**
- New rule created with same text
- Name appended with "(Copy)" or similar
- New unique ID assigned
- Editable independently

---

### TC-SW-021: List All Rules
**Objective:** Verify rule listing for a project  
**Steps:**
1. Create 5+ rules
2. View rules list

**Expected Results:**
- All rules listed
- Shows: name, enabled status, last updated
- Sortable by name or date
- Clickable to edit

---

## Template Tests

### TC-SW-022: Load Classification Template
**Objective:** Verify loading a built-in template  
**Steps:**
1. Open Templates dropdown
2. Select "Classification" category
3. Choose a template

**Expected Results:**
- Template text loaded in editor
- Placeholder variables present
- Template is valid SWRL syntax
- Ready to customize and save

---

### TC-SW-023: Load Math Template
**Objective:** Verify math template (e.g., BMI calculation)  
**Steps:**
1. Select Math category → BMI template

**Expected Results:**
- Rule uses swrlb:multiply, swrlb:divide
- Correct BMI formula structure
- Variables for height, weight, result

---

### TC-SW-024: Load Property Chain Template
**Objective:** Verify property chain template  
**Steps:**
1. Select "Property Chains" → Uncle relation

**Expected Results:**
- Chain: parent(?x, ?y) ∧ brother(?y, ?z) → uncle(?x, ?z)
- Correct object property chain
- Valid and executable

---

### TC-SW-025: Load SQWRL Template
**Objective:** Verify SQWRL query template  
**Steps:**
1. Select "SQWRL" → Count template

**Expected Results:**
- SQWRL aggregation syntax loaded
- Uses sqwrl:count or similar
- Template is syntactically valid

---

### TC-SW-026: All 40+ Templates Valid
**Objective:** Verify all built-in templates pass validation  
**Steps:**
1. Iterate through all template categories
2. Load each template
3. Run validation

**Expected Results:**
- All templates return `valid: true`
- No syntax errors in any template
- Each has descriptive name

---

## Built-in Function Tests

### TC-SW-027: Comparison Functions
**Functions:** `equal`, `notEqual`, `lessThan`, `greaterThan`, `lessThanOrEqual`, `greaterThanOrEqual`  
**Steps:**
1. Create rules using each comparison function
2. Execute against test data

**Expected Results:**
| Function | Input | Expected |
|----------|-------|----------|
| `swrlb:equal(?x, 20)` | age=20 | match |
| `swrlb:notEqual(?x, 20)` | age=24 | match |
| `swrlb:lessThan(?x, 25)` | age=20 | match |
| `swrlb:greaterThan(?x, 3.5)` | gpa=3.8 | match |
| `swrlb:lessThanOrEqual(?x, 3)` | credits=3 | match |
| `swrlb:greaterThanOrEqual(?x, 500)` | level=500 | match |

---

### TC-SW-028: Math Functions
**Functions:** `add`, `subtract`, `multiply`, `divide`, `abs`, `round`  
**Steps:**
1. Create rules using math operations
2. Execute and verify computed values

**Expected Results:**
| Function | Inputs | Expected |
|----------|--------|----------|
| `swrlb:add(?r, 3, 4)` | — | ?r = 7 |
| `swrlb:subtract(?r, 10, 3)` | — | ?r = 7 |
| `swrlb:multiply(?r, 3.8, 1000)` | — | ?r = 3800 |
| `swrlb:divide(?r, 10, 3)` | — | ?r ≈ 3.33 |
| `swrlb:abs(?r, -5)` | — | ?r = 5 |
| `swrlb:round(?r, 3.7)` | — | ?r = 4 |

---

### TC-SW-029: String Functions
**Functions:** `stringConcat`, `contains`, `startsWith`, `endsWith`, `matches`  
**Steps:**
1. Create rules using string operations
2. Execute against named individuals

**Expected Results:**
| Function | Input | Expected |
|----------|-------|----------|
| `swrlb:contains(?n, "John")` | "JohnDoe" | match |
| `swrlb:startsWith(?n, "Dr")` | "DrJohnson" | match |
| `swrlb:endsWith(?n, "Smith")` | "JaneSmith" | match |
| `swrlb:matches(?n, "^J.*")` | "JohnDoe" | match |

---

### TC-SW-030: Date/Time Functions
**Objective:** Verify date comparison built-ins  
**Steps:**
1. Create rules with date comparisons
2. Execute against individuals with date properties

**Expected Results:**
- Date comparison works correctly
- Temporal ordering respected

---

## VS Code Integration Tests

### TC-SW-031: SWRL Rules Explorer View
**Objective:** Verify SWRL rules appear in VS Code sidebar  
**Steps:**
1. Open VS Code
2. Look for "SWRL Rules" in sidebar

**Expected Results:**
- Tree view with all saved rules
- Each rule shows name and enabled status
- Click opens rule in editor

---

### TC-SW-032: Command — New Rule
**Objective:** Verify `swrl.newRule` command  
**Steps:**
1. Open Command Palette (Ctrl+Shift+P)
2. Run "SWRL: New Rule"

**Expected Results:**
- SWRL editor opens with empty rule
- Cursor in rule name field
- Ready to create new rule

---

### TC-SW-033: Command — Validate Rule
**Objective:** Verify `swrl.validateRule` command  
**Steps:**
1. Open existing rule
2. Run "SWRL: Validate Rule"

**Expected Results:**
- Validation runs on current rule
- Result shown in notification or output panel

---

### TC-SW-034: Command — Execute Rule
**Objective:** Verify `swrl.executeRule` command  
**Steps:**
1. Open existing rule
2. Run "SWRL: Execute Rule"

**Expected Results:**
- Rule executed against loaded ontology
- Results displayed in SWRL editor panel

---

## Error Handling Tests

### TC-SW-035: Backend Unreachable
**Objective:** Verify handling when SWRL service is down  
**Steps:**
1. Stop `ontology-swrl` service
2. Attempt to validate/execute a rule

**Expected Results:**
- Connection error message
- Rule text preserved
- Retry option available

---

### TC-SW-036: Execution Error — No Ontology Loaded
**Objective:** Verify error when executing without loaded ontology  
**Steps:**
1. Open SWRL editor without loading ontology
2. Attempt to execute a rule

**Expected Results:**
- Error: "No ontology loaded for this project"
- Instruction to load ontology first

---

### TC-SW-037: Execution Error — Rule Causes Inconsistency
**Objective:** Verify handling when rule creates contradictions  
**Steps:**
1. Create a rule that would make ontology inconsistent
2. Execute

**Expected Results:**
- Warning or error about inconsistency
- Option to rollback inferred axioms
- Ontology state preserved

---

## Performance Tests

### TC-SW-038: Simple Rule Execution Speed
**Objective:** Simple rule completes quickly  
**Metrics:**
- 10 individuals: < 1 second
- 100 individuals: < 3 seconds
- 1000 individuals: < 10 seconds

---

### TC-SW-039: Complex Rule with Multiple Joins
**Objective:** Multi-join rule performance  
**Rule:** 5+ body atoms with 3+ variables  
**Metrics:**
- Small ontology: < 5 seconds
- Medium ontology: < 15 seconds

---

### TC-SW-040: Rule Statistics Dashboard
**Objective:** Verify statistics display  
**Steps:**
1. Create and execute several rules
2. View statistics section

**Expected Results:**
- Total rules count
- Enabled/disabled count
- Last execution time
- Total inferred axioms count

---

## Test Summary Matrix

| Test ID | Category | Priority | Status |
|---------|----------|----------|--------|
| TC-SW-001 | Editor | P0 | ☐ |
| TC-SW-002 | Editor | P1 | ☐ |
| TC-SW-003 | Editor | P1 | ☐ |
| TC-SW-004 | Validation | P0 | ☐ |
| TC-SW-005 | Validation | P0 | ☐ |
| TC-SW-006 | Validation | P1 | ☐ |
| TC-SW-007 | Validation | P1 | ☐ |
| TC-SW-008 | Validation | P1 | ☐ |
| TC-SW-009 | Validation | P1 | ☐ |
| TC-SW-010 | Execution | P0 | ☐ |
| TC-SW-011 | Execution | P0 | ☐ |
| TC-SW-012 | Execution | P0 | ☐ |
| TC-SW-013 | Execution | P1 | ☐ |
| TC-SW-014 | Execution | P1 | ☐ |
| TC-SW-015 | Execution | P1 | ☐ |
| TC-SW-016 | Management | P0 | ☐ |
| TC-SW-017 | Management | P0 | ☐ |
| TC-SW-018 | Management | P0 | ☐ |
| TC-SW-019 | Management | P1 | ☐ |
| TC-SW-020 | Management | P2 | ☐ |
| TC-SW-021 | Management | P1 | ☐ |
| TC-SW-022 | Templates | P1 | ☐ |
| TC-SW-023 | Templates | P1 | ☐ |
| TC-SW-024 | Templates | P1 | ☐ |
| TC-SW-025 | Templates | P2 | ☐ |
| TC-SW-026 | Templates | P1 | ☐ |
| TC-SW-027 | Built-ins | P0 | ☐ |
| TC-SW-028 | Built-ins | P1 | ☐ |
| TC-SW-029 | Built-ins | P1 | ☐ |
| TC-SW-030 | Built-ins | P2 | ☐ |
| TC-SW-031 | VS Code | P1 | ☐ |
| TC-SW-032 | VS Code | P1 | ☐ |
| TC-SW-033 | VS Code | P1 | ☐ |
| TC-SW-034 | VS Code | P1 | ☐ |
| TC-SW-035 | Error Handling | P0 | ☐ |
| TC-SW-036 | Error Handling | P0 | ☐ |
| TC-SW-037 | Error Handling | P1 | ☐ |
| TC-SW-038 | Performance | P1 | ☐ |
| TC-SW-039 | Performance | P2 | ☐ |
| TC-SW-040 | Statistics | P2 | ☐ |
