# OWL Reasoner Plugin - Testing Document

**Plugin:** @ontocode/reasoner-plugin v1.0.0  
**Categories:** Reasoner, Ontology, Inference, Classification  
**Last Updated:** April 2026

---

## Table of Contents
1. [Overview](#overview)
2. [Test Environment Setup](#test-environment-setup)
3. [Consistency Checking Tests](#consistency-checking-tests)
4. [Classification Tests](#classification-tests)
5. [Realization Tests](#realization-tests)
6. [Satisfiability & Entailment Tests](#satisfiability--entailment-tests)
7. [Explanation Tests](#explanation-tests)
8. [Reasoner Engine Tests](#reasoner-engine-tests)
9. [Configuration Tests](#configuration-tests)
10. [Performance Tests](#performance-tests)
11. [Error Handling Tests](#error-handling-tests)

---

## Overview

The OWL Reasoner Plugin provides HermiT-inspired OWL reasoning for consistency checking, classification, realization, satisfiability, entailment, and explanation generation. Supports HermiT, Pellet, FaCT++, and ELK engines.

### Components Under Test
- `ReasonerPlugin.tsx` — Full reasoning interface with configuration, status, tasks, results, inferred axioms, stats, and explanation panels

### API Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/reasoner/{projectId}/consistency` | Check consistency |
| POST | `/api/reasoner/{projectId}/classify` | Run classification |
| POST | `/api/reasoner/{projectId}/realize` | Realize individuals |
| GET | `/api/reasoner/{projectId}/stats` | Get statistics |

### Supported Reasoners
| Engine | Type | Best For |
|--------|------|----------|
| HermiT | Hypertableau-based | General OWL DL (default) |
| Pellet | Complete + Incremental | Incremental updates |
| FaCT++ | Fast classifier | Large classification tasks |
| ELK | EL++ efficient | EL++ profile ontologies |

---

## Test Environment Setup

### Prerequisites
- OntoCode platform running with reasoner service (`ontology-swrl`)
- GraphDB with loaded test ontologies
- Test ontologies with known consistency/inconsistency properties

### Test Ontologies
| File | Description | Properties |
|------|-------------|------------|
| `consistent-ontology.owl` | Simple consistent ontology | All classes satisfiable |
| `inconsistent-ontology.owl` | Ontology with contradictions | Contains unsatisfiable classes |
| `large-ontology.owl` | 1000+ class ontology | Performance testing |
| `test-swrl-ontology.owl` | Ontology with individuals | Realization testing |

---

## Consistency Checking Tests

### TC-RS-001: Consistent Ontology Check
**Objective:** Verify consistent ontology returns positive result  
**Preconditions:** Load a known-consistent ontology  
**Steps:**
1. Open ReasonerPlugin
2. Select "Consistency Checking" task
3. Click "Run"
4. Wait for completion

**Expected Results:**
- `isConsistent: true`
- `unsatisfiableClasses: []` (empty)
- Duration displayed in milliseconds
- Timestamp recorded
- Green status indicator

---

### TC-RS-002: Inconsistent Ontology Detection
**Objective:** Verify inconsistent ontology is properly detected  
**Preconditions:** Load ontology with contradictory axioms (e.g., A ⊑ B, A ⊑ ¬B, A has instance)  
**Steps:**
1. Run Consistency Checking

**Expected Results:**
- `isConsistent: false`
- `unsatisfiableClasses` lists conflicting classes
- Red status indicator
- Explanation available for each unsatisfiable class

---

### TC-RS-003: Consistency After Modification
**Objective:** Verify re-checking after adding contradictory axiom  
**Steps:**
1. Load consistent ontology
2. Run consistency check → passes
3. Add contradictory axiom (e.g., DisjointWith + SubClassOf)
4. Re-run consistency check

**Expected Results:**
- First check: consistent
- Second check after modification: inconsistent
- Changed classes identified

---

## Classification Tests

### TC-RS-004: Class Hierarchy Classification
**Objective:** Verify classification computes complete class hierarchy  
**Steps:**
1. Load ontology with class hierarchy
2. Select "Classification" task
3. Run classification

**Expected Results:**
- `classHierarchy[]` returned with parent → children structure
- Equivalent classes grouped in `equivalentClasses[][]`
- Unsatisfiable classes identified
- All subsumption relationships discovered

---

### TC-RS-005: Equivalent Class Detection
**Objective:** Verify detection of equivalent classes  
**Preconditions:** Ontology with A ≡ B defined  
**Steps:**
1. Run classification

**Expected Results:**
- A and B listed as equivalent
- Both appear in same equivalence group
- Hierarchy shows them as interchangeable

---

### TC-RS-006: Unsatisfiable Class Detection During Classification
**Objective:** Verify unsatisfiable classes found during classification  
**Preconditions:** Ontology with class that cannot have instances  
**Steps:**
1. Run classification
2. Check unsatisfiable classes section

**Expected Results:**
- Unsatisfiable classes listed with `owl:Nothing`
- Explanation available for each
- Visual indicator (red) in classification results

---

### TC-RS-007: Inferred SubClass Relationships
**Objective:** Verify reasoner discovers implicit subclass relations  
**Preconditions:** A ⊑ B, B ⊑ C → should infer A ⊑ C  
**Steps:**
1. Run classification
2. Check inferred axioms section

**Expected Results:**
- Inferred axiom: `SubClassOf(A, C)` listed
- Confidence: 1.0
- Type: `subClassOf`
- Distinguishable from asserted axioms

---

## Realization Tests

### TC-RS-008: Individual Type Realization
**Objective:** Verify realization identifies most specific types  
**Preconditions:** Individuals with multiple possible types  
**Steps:**
1. Run "Realization" task
2. Examine individual → type mapping

**Expected Results:**
- Each individual mapped to direct types
- Most specific classes listed (not all superclasses)
- Example: JohnDoe → {UndergraduateStudent} (not just {Student, Person})

---

### TC-RS-009: Realization After Adding Individual
**Objective:** Verify realization updates for new individuals  
**Steps:**
1. Run realization
2. Add new individual with class membership
3. Re-run realization

**Expected Results:**
- New individual appears in results
- Correct most-specific type assigned
- Existing results unchanged

---

## Satisfiability & Entailment Tests

### TC-RS-010: Satisfiable Class Check
**Objective:** Verify class satisfiability checking  
**Steps:**
1. Select a class that can have instances
2. Run satisfiability check

**Expected Results:**
- Class reported as satisfiable
- Explanation shows why (no contradictory constraints)

---

### TC-RS-011: Unsatisfiable Class Check
**Objective:** Verify unsatisfiable class detection  
**Preconditions:** Class with contradictory constraints  
**Steps:**
1. Select class with A ⊑ B ⊓ ¬B
2. Run satisfiability check

**Expected Results:**
- Class reported as unsatisfiable
- Explanation shows conflicting axioms

---

### TC-RS-012: Axiom Entailment Verification
**Objective:** Verify entailment checking for axioms  
**Steps:**
1. Input axiom to check: `SubClassOf(A, C)`
2. Run entailment check

**Expected Results:**
- Returns `true` if axiom logically follows from ontology
- Returns `false` if not entailed
- Explanation available for positive entailment

---

## Explanation Tests

### TC-RS-013: Justification Generation
**Objective:** Verify explanation/justification for inferred axioms  
**Steps:**
1. Run classification
2. Select an inferred axiom
3. Request explanation

**Expected Results:**
- List of axioms that justify the inference
- Minimal set (justification)
- Each axiom linked to source in ontology
- Human-readable format

---

### TC-RS-014: Multiple Justifications
**Objective:** Verify multiple explanations for redundant inferences  
**Steps:**
1. Use ontology where an inference has multiple independent justifications
2. Request all explanations

**Expected Results:**
- Multiple justification sets returned
- Each set is independently sufficient
- All sets shown to user

---

## Reasoner Engine Tests

### TC-RS-015: HermiT Engine
**Objective:** Verify HermiT reasoner performs all operations  
**Steps:**
1. Set reasoner to "hermit"
2. Run consistency, classification, realization in sequence

**Expected Results:**
- All three operations complete successfully
- Correct results for test ontology
- Performance within acceptable range

---

### TC-RS-016: Pellet Engine
**Objective:** Verify Pellet reasoner with incremental mode  
**Steps:**
1. Set reasoner to "pellet"
2. Enable incremental reasoning
3. Run classification
4. Add one axiom
5. Re-run classification

**Expected Results:**
- Initial classification completes
- Incremental re-classification faster than full
- Results consistent with full classification

---

### TC-RS-017: FaCT++ Engine
**Objective:** Verify FaCT++ fast classification  
**Steps:**
1. Set reasoner to "fact++"
2. Run classification on medium ontology

**Expected Results:**
- Classification completes
- Results match HermiT classification
- Potentially faster for large ontologies

---

### TC-RS-018: ELK Engine
**Objective:** Verify ELK for EL++ profile ontologies  
**Steps:**
1. Set reasoner to "elk"
2. Load EL++ profile ontology
3. Run classification

**Expected Results:**
- Classification completes efficiently
- Correct hierarchy for EL++ ontology
- Warning if ontology exceeds EL++ profile

---

### TC-RS-019: Engine Comparison
**Objective:** Verify all engines produce consistent results  
**Steps:**
1. Load the same ontology
2. Run classification with each engine: HermiT, Pellet, FaCT++, ELK
3. Compare results

**Expected Results:**
- All engines produce same class hierarchy
- Same equivalent classes detected
- Same unsatisfiable classes found
- Performance may differ

---

## Configuration Tests

### TC-RS-020: Timeout Configuration
**Objective:** Verify timeout stops long-running reasoning  
**Steps:**
1. Set timeout to 5000ms
2. Load complex ontology requiring > 5s
3. Run classification

**Expected Results:**
- Reasoning stops at timeout
- Partial results displayed (if available)
- Error message: "Reasoning timed out"
- UI remains responsive

---

### TC-RS-021: Incremental Reasoning Toggle
**Objective:** Verify incremental reasoning configuration  
**Steps:**
1. Enable `useIncrementalReasoning: true`
2. Run classification
3. Modify ontology slightly
4. Re-run classification

**Expected Results:**
- Second run uses cached partial results
- Faster than full re-classification
- Correct results

---

### TC-RS-022: Result Caching
**Objective:** Verify caching configuration  
**Steps:**
1. Enable `cacheResults: true`
2. Run consistency check
3. Immediately re-run without changes

**Expected Results:**
- Second run returns cached result instantly
- Cached results invalidated on ontology change
- Cache can be manually cleared

---

### TC-RS-023: Max Concurrent Tasks
**Objective:** Verify concurrent task limiting  
**Steps:**
1. Set `maxConcurrentTasks: 2`
2. Launch 3 reasoning tasks simultaneously

**Expected Results:**
- First 2 tasks start immediately
- Third task queued
- Third starts when one of first 2 completes
- All tasks complete correctly

---

## Performance Tests

### TC-RS-024: Small Ontology (< 100 classes)
**Metrics:**
- Consistency check: < 1 second
- Classification: < 2 seconds
- Realization: < 2 seconds

---

### TC-RS-025: Medium Ontology (500 classes)
**Metrics:**
- Consistency check: < 5 seconds
- Classification: < 10 seconds
- Realization: < 10 seconds

---

### TC-RS-026: Large Ontology (5000+ classes)
**Metrics:**
- Consistency check: < 30 seconds
- Classification: < 60 seconds
- Realization: < 60 seconds
- UI remains responsive during reasoning

---

### TC-RS-027: Inferred Axioms Export
**Objective:** Verify export of all inferred axioms  
**Steps:**
1. Run classification
2. Click "Export Results as JSON"

**Expected Results:**
- JSON file downloaded with all inferred axioms
- Each axiom has: type, subject, object, confidence
- Valid JSON format
- File named with project ID and timestamp

---

## Error Handling Tests

### TC-RS-028: Invalid Ontology
**Objective:** Verify handling of malformed OWL  
**Steps:**
1. Load ontology with syntax errors
2. Attempt reasoning

**Expected Results:**
- Error message with specific parsing issue
- No crash
- Suggestion to fix syntax

---

### TC-RS-029: Backend Unreachable
**Objective:** Verify handling when reasoner service is down  
**Steps:**
1. Stop the `ontology-swrl` service
2. Attempt reasoning

**Expected Results:**
- Connection error displayed
- Retry option available
- Previous cached results still accessible

---

### TC-RS-030: Progress Tracking
**Objective:** Verify progress display during long operations  
**Steps:**
1. Run classification on large ontology

**Expected Results:**
- Progress bar/spinner visible
- Status updates (e.g., "Classifying...", "Computing hierarchy...")
- Cancel button available
- Final status: "Complete" or error

---

## Statistics Tests

### TC-RS-031: Reasoner Statistics
**Objective:** Verify statistics endpoint returns correct data  
**Steps:**
1. Load ontology
2. Call stats endpoint or view stats section

**Expected Results:**
- `classCount`: matches actual class count
- `propertyCount`: object + data properties
- `individualCount`: matches named individuals
- `axiomCount`: total axiom count
- Additional metrics accurate

---

## Test Summary Matrix

| Test ID | Category | Priority | Status |
|---------|----------|----------|--------|
| TC-RS-001 | Consistency | P0 | ☐ |
| TC-RS-002 | Consistency | P0 | ☐ |
| TC-RS-003 | Consistency | P1 | ☐ |
| TC-RS-004 | Classification | P0 | ☐ |
| TC-RS-005 | Classification | P1 | ☐ |
| TC-RS-006 | Classification | P0 | ☐ |
| TC-RS-007 | Classification | P1 | ☐ |
| TC-RS-008 | Realization | P0 | ☐ |
| TC-RS-009 | Realization | P1 | ☐ |
| TC-RS-010 | Satisfiability | P1 | ☐ |
| TC-RS-011 | Satisfiability | P1 | ☐ |
| TC-RS-012 | Entailment | P1 | ☐ |
| TC-RS-013 | Explanation | P1 | ☐ |
| TC-RS-014 | Explanation | P2 | ☐ |
| TC-RS-015 | Engine | P0 | ☐ |
| TC-RS-016 | Engine | P1 | ☐ |
| TC-RS-017 | Engine | P1 | ☐ |
| TC-RS-018 | Engine | P1 | ☐ |
| TC-RS-019 | Engine | P2 | ☐ |
| TC-RS-020 | Configuration | P0 | ☐ |
| TC-RS-021 | Configuration | P1 | ☐ |
| TC-RS-022 | Configuration | P1 | ☐ |
| TC-RS-023 | Configuration | P2 | ☐ |
| TC-RS-024 | Performance | P0 | ☐ |
| TC-RS-025 | Performance | P1 | ☐ |
| TC-RS-026 | Performance | P1 | ☐ |
| TC-RS-027 | Export | P1 | ☐ |
| TC-RS-028 | Error Handling | P0 | ☐ |
| TC-RS-029 | Error Handling | P0 | ☐ |
| TC-RS-030 | Progress | P1 | ☐ |
| TC-RS-031 | Statistics | P1 | ☐ |
