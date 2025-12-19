# Inconsistency Explanation Feature

## Overview
The Reasoner Plugin now includes comprehensive functionality to detect, explain, and visualize inconsistent ontologies and unsatisfiable classes.

## Features

### 1. **Enhanced Consistency Check**
When running a consistency check, the reasoner now:
- Detects if the ontology is inconsistent
- Automatically retrieves all unsatisfiable classes
- Displays them prominently in the results panel

### 2. **Unsatisfiable Classes Display**
The results panel now shows:
- **Count**: Total number of unsatisfiable classes
- **Class Names**: Both labels and IRIs for each unsatisfiable class
- **Visual Indicators**: Red badges and alerts for easy identification
- **Helpful Hints**: Guidance on what unsatisfiable classes mean and how to fix them

### 3. **Explain Inconsistency Button**
A new button appears in the Reasoning Tasks section when an inconsistent ontology is detected:
- **Button Color**: Orange/amber to indicate warning
- **Icon**: AlertTriangle icon for visibility
- **Behavior**: Only visible after a consistency check reveals inconsistencies

### 4. **Inconsistency Explanation Panel**
A dedicated expandable section that displays:

#### a. Summary Header
- Clear statement of why the ontology is inconsistent
- Count of unsatisfiable classes
- Visual indicators (icons and colors)

#### b. Unsatisfiable Classes Details
For each unsatisfiable class:
- **Class Name**: Human-readable label
- **IRI**: Full class identifier
- **Reason**: Explanation of why the class is unsatisfiable
- **Conflicting Axioms**: List of axioms that cause the conflict

#### c. Detailed Explanations
- Multiple explanation paths if available
- Conflicting axiom sets
- Logical reasoning behind the inconsistency

#### d. Fix Suggestions
Footer with:
- Common causes of inconsistencies
- Recommendations for fixing the issues
- Best practices for ontology development

## UI Components

### Result Panel Enhancement
```
✗ Ontology is inconsistent ✗

⚠ Unsatisfiable Classes (3)
┌─────────────────────────────────────┐
│ DisjointClass1                      │
│ http://example.org/onto#Class1      │
├─────────────────────────────────────┤
│ ConflictingClass                    │
│ http://example.org/onto#Class2      │
└─────────────────────────────────────┘

ℹ These classes cannot have any instances due to 
  logical contradictions. Click "Explain 
  Inconsistency" to understand why.
```

### Explanation Panel Structure
```
🔴 Why is the ontology inconsistent?
The reasoner detected 3 unsatisfiable class(es)

Unsatisfiable Classes
┌─────────────────────────────────────────────┐
│ Class: Person                    [Unsatisfiable]│
│ IRI: http://example.org/onto#Person         │
│                                             │
│ Reason: This class is both declared as     │
│ disjoint with Animal and a subclass of     │
│ Animal, creating a logical contradiction.  │
│                                             │
│ Conflicting Axioms:                        │
│ • Person SubClassOf Animal                 │
│ • Person DisjointWith Animal               │
└─────────────────────────────────────────────┘

ℹ To fix these issues, review the conflicting
  axioms and modify your ontology to remove 
  logical contradictions.
```

## Technical Implementation

### New Types (types.ts)
```typescript
export interface UnsatisfiableClass {
  iri: string;
  label: string;
}

export interface InconsistencyExplanation {
  isConsistent: boolean;
  unsatisfiableClasses: UnsatisfiableClass[];
  explanations: ClassExplanation[];
  reasonerType: string;
  timestamp?: string;
}

export interface ClassExplanation {
  classIri: string;
  classLabel: string;
  reason: string;
  axioms: string[];
}
```

### API Endpoints Used
1. **Consistency Check**: `POST /api/reasoner/{projectId}/consistency`
   - Returns: `consistent`, `unsatisfiableClasses[]`

2. **Explain Inconsistency**: `POST /api/reasoner/{projectId}/explain-inconsistency`
   - Returns: Detailed explanation with conflicting axioms

## User Workflow

1. **Run Consistency Check**
   - Click "Check Consistency" button
   - Reasoner analyzes ontology

2. **View Results**
   - See if ontology is consistent or inconsistent
   - If inconsistent, see list of unsatisfiable classes

3. **Get Detailed Explanation**
   - Click "Explain Inconsistency" button (appears only when inconsistent)
   - View detailed explanations for each unsatisfiable class
   - Review conflicting axioms

4. **Fix Issues**
   - Use the information to identify and fix logical contradictions
   - Re-run consistency check to verify fixes

## Benefits

- **Clear Identification**: Instantly see which classes are problematic
- **Root Cause Analysis**: Understand why classes are unsatisfiable
- **Guided Fixes**: Get hints on how to resolve issues
- **Visual Clarity**: Color-coded UI elements for quick comprehension
- **Comprehensive Details**: Access both high-level and detailed views

## Example Scenarios

### Scenario 1: Disjoint Class Conflict
```
Class: VegetarianPizza
Problem: Subclass of both Pizza and Vegetarian, 
         but these are declared disjoint
Fix: Remove disjoint declaration or reclassify
```

### Scenario 2: Cardinality Conflict
```
Class: Person
Problem: Has exactly 1 parent (someValuesFrom)
         but also maxCardinality 0 on hasParent
Fix: Adjust cardinality restrictions
```

### Scenario 3: Property Domain/Range Conflict
```
Class: Document
Problem: hasAuthor property has range Person,
         but Document asserts hasAuthor some Animal
Fix: Align property restrictions with range
```

## Styling

All UI components follow the plugin's design system:
- **Error Colors**: Red (#ef4444) for inconsistencies
- **Warning Colors**: Amber (#f59e0b) for alerts
- **Info Colors**: Blue (#3b82f6) for hints
- **Success Colors**: Green (#10b981) for consistency
- **Neutral Colors**: Gray scale for secondary information

## Future Enhancements

Potential improvements:
- Interactive axiom editing from explanation panel
- Visualization of conflicting class hierarchies
- Suggestions for automated fixes
- Export explanation reports
- Integration with ontology diff tools
