# Change Assistant Plugin - Testing Document

**Plugin:** change-assistant-plugin v1.0.0  
**Categories:** Ontology, Collaboration, Version Control  
**Last Updated:** April 2026

---

## Table of Contents
1. [Overview](#overview)
2. [Test Environment Setup](#test-environment-setup)
3. [Change Tracking Tests](#change-tracking-tests)
4. [Conflict Detection Tests](#conflict-detection-tests)
5. [Conflict Resolution Tests](#conflict-resolution-tests)
6. [Review Workflow Tests](#review-workflow-tests)
7. [Visualization Tests](#visualization-tests)
8. [Collaboration Tests](#collaboration-tests)
9. [Integration Tests](#integration-tests)
10. [Edge Case Tests](#edge-case-tests)

---

## Overview

The Change Assistant Plugin tracks and manages collaborative ontology edits with change history, conflict detection, version control integration, and multi-user review workflows.

### Components Under Test
- `ChangeAssistant.tsx` — Central change management UI
- `ChangeTimeline.tsx` — Historical timeline of changes
- `ChangeGraph.tsx` — Dependency graph of changes
- `ConflictResolver.tsx` — Conflict detection and resolution
- `AuthorActivityChart.tsx` — Contributor analytics

### Change Types Tracked
| Type | Actions | Status Options |
|------|---------|----------------|
| class | added, deleted, modified | pending, approved, rejected, conflicted, draft |
| property | added, deleted, modified | pending, approved, rejected, conflicted, draft |
| individual | added, deleted, modified | pending, approved, rejected, conflicted, draft |
| axiom | added, deleted, modified | pending, approved, rejected, conflicted, draft |
| annotation | added, deleted, modified | pending, approved, rejected, conflicted, draft |
| import | added, deleted, modified | pending, approved, rejected, conflicted, draft |

### API Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/changes/{projectId}` | List all changes |
| POST | `/api/changes/{projectId}/approve` | Approve change |
| POST | `/api/changes/{projectId}/reject` | Reject change |
| POST | `/api/changes/{projectId}/rollback` | Rollback change |
| GET | `/api/changes/{projectId}/conflicts` | Get conflicts |

---

## Test Environment Setup

### Prerequisites
- OntoCode platform running with multiple user accounts
- GraphDB with loaded shared ontology
- At least 2 browser sessions for multi-user testing
- Git repository connected (for version control integration)

### Test Users
| User | Role | Purpose |
|------|------|---------|
| UserA | Editor | Makes changes |
| UserB | Editor | Makes concurrent changes |
| Admin | Reviewer | Approves/rejects changes |

---

## Change Tracking Tests

### TC-CA-001: Track Class Addition
**Objective:** Verify adding a class creates a tracked change  
**Steps:**
1. UserA adds a new class "Vehicle" to the ontology
2. Open Change Assistant
3. Verify change appears in timeline

**Expected Results:**
- Change record with:
  - type: `class`
  - action: `added`
  - author: UserA
  - entityUri: IRI of new class
  - entityLabel: "Vehicle"
  - status: `pending`
  - timestamp: current time
- Description auto-generated

---

### TC-CA-002: Track Class Deletion
**Objective:** Verify deleting a class creates a tracked change  
**Steps:**
1. UserA deletes an existing class
2. Check Change Assistant

**Expected Results:**
- Change record with action: `deleted`
- oldValue contains class definition
- newValue is empty/null
- Status: `pending`

---

### TC-CA-003: Track Class Modification
**Objective:** Verify modifying a class creates a tracked change  
**Steps:**
1. UserA renames a class or changes its superclass
2. Check Change Assistant

**Expected Results:**
- Change record with action: `modified`
- oldValue: previous state
- newValue: new state
- Diff available between old and new

---

### TC-CA-004: Track Property Changes
**Objective:** Verify object/data property changes tracked  
**Steps:**
1. Add new object property "hasOwner"
2. Modify existing data property domain
3. Delete an annotation property

**Expected Results:**
- Three change records created
- Each with correct type: `property`
- Actions: added, modified, deleted respectively

---

### TC-CA-005: Track Individual Changes
**Objective:** Verify individual CRUD operations tracked  
**Steps:**
1. Add individual "Car001"
2. Add property assertion to Car001
3. Delete Car001

**Expected Results:**
- Three change records for type: `individual`
- Full change trail preserved

---

### TC-CA-006: Track Axiom Changes
**Objective:** Verify axiom modifications tracked  
**Steps:**
1. Add SubClassOf axiom
2. Add DisjointWith axiom
3. Remove an axiom

**Expected Results:**
- Changes with type: `axiom`
- Axiom text in description field
- Old/new values for modifications

---

### TC-CA-007: Track Import Changes
**Objective:** Verify import statement changes tracked  
**Steps:**
1. Add an ontology import
2. Remove an existing import

**Expected Results:**
- Changes with type: `import`
- Import URI recorded in entityUri

---

### TC-CA-008: Change Comments
**Objective:** Verify comments can be added to changes  
**Steps:**
1. Select a pending change
2. Add a comment: "Needs review by domain expert"
3. Add another comment by different user

**Expected Results:**
- Comments stored in `comments[]` array
- Each comment has author, timestamp, text
- Comments visible in change detail view
- Threaded/chronological display

---

## Conflict Detection Tests

### TC-CA-009: Concurrent Edit Conflict
**Objective:** Verify detection when two users edit the same entity  
**Steps:**
1. UserA modifies class "Animal" label
2. UserB modifies class "Animal" superclass (simultaneously)
3. Check conflicts

**Expected Results:**
- Conflict detected with type: `concurrent_edit`
- Both changes marked with status: `conflicted`
- ConflictInfo includes both change IDs
- Conflict resolver activated

---

### TC-CA-010: Dependency Conflict
**Objective:** Verify detection when a change breaks dependent relationships  
**Steps:**
1. UserA deletes class "Animal"
2. Class "Dog" has SubClassOf "Animal" relationship
3. Check conflicts

**Expected Results:**
- Dependency conflict detected: `dependency`
- Warning: "Deleting Animal will break Dog's superclass relationship"
- Dependent entities listed

---

### TC-CA-011: Constraint Violation Conflict
**Objective:** Verify detection of ontology constraint violations  
**Steps:**
1. UserA adds DisjointWith between A and B
2. Individual exists that is instance of both A and B
3. Check conflicts

**Expected Results:**
- Constraint violation detected: `constraint_violation`
- Description explains the violation
- Affected individual identified

---

### TC-CA-012: No Conflict — Independent Edits
**Objective:** Verify no false positive conflicts for independent changes  
**Steps:**
1. UserA modifies class in namespace A
2. UserB modifies unrelated class in namespace B

**Expected Results:**
- No conflicts detected
- Both changes remain in `pending` status
- Both can be approved independently

---

## Conflict Resolution Tests

### TC-CA-013: Accept Mine (Local Changes)
**Objective:** Verify "accept mine" resolution strategy  
**Steps:**
1. Create a concurrent edit conflict
2. Open ConflictResolver
3. Select "Accept Mine" for UserA's change

**Expected Results:**
- UserA's change applied
- UserB's change rejected
- Conflict status resolved
- Notification sent to UserB

---

### TC-CA-014: Accept Theirs (Remote Changes)
**Objective:** Verify "accept theirs" resolution strategy  
**Steps:**
1. Create a concurrent edit conflict
2. Select "Accept Theirs" for UserB's change

**Expected Results:**
- UserB's change applied
- UserA's change rejected
- Both users notified

---

### TC-CA-015: Manual Merge
**Objective:** Verify manual merge of conflicting changes  
**Steps:**
1. Create a conflict with both changes partially valid
2. Open ConflictResolver
3. Manually select portions from each change
4. Submit merged result

**Expected Results:**
- Custom merged version created
- Both original changes marked as resolved
- New merged change record created
- Ontology reflects merged state

---

### TC-CA-016: Rollback Change
**Objective:** Verify rolling back an approved change  
**Steps:**
1. Approve a change (class addition)
2. Select the change in timeline
3. Click "Rollback"
4. Confirm rollback

**Expected Results:**
- Change reversed in ontology
- New change record: "Rollback of [original change]"
- Original change marked accordingly
- Related entities restored to previous state

---

## Review Workflow Tests

### TC-CA-017: Approve Change
**Objective:** Verify change approval workflow  
**Steps:**
1. UserA creates a change (status: pending)
2. Admin opens Change Assistant
3. Reviews the change
4. Clicks "Approve"

**Expected Results:**
- Status changes to `approved`
- Change applied to ontology
- Approval timestamp recorded
- UserA notified of approval

---

### TC-CA-018: Reject Change
**Objective:** Verify change rejection workflow  
**Steps:**
1. UserA creates a change
2. Admin reviews and clicks "Reject"
3. Admin adds rejection reason

**Expected Results:**
- Status changes to `rejected`
- Change NOT applied to ontology
- Rejection reason stored
- UserA notified with reason

---

### TC-CA-019: Draft Status
**Objective:** Verify draft changes are not visible for review  
**Steps:**
1. UserA creates a change with status: `draft`
2. Admin opens Change Assistant

**Expected Results:**
- Draft changes only visible to author
- Not listed in review queue for Admin
- UserA can promote draft to `pending`

---

## Visualization Tests

### TC-CA-020: Change Timeline Display
**Objective:** Verify ChangeTimeline shows chronological changes  
**Steps:**
1. Make 5+ changes over a time period
2. Open Change Timeline view

**Expected Results:**
- Changes displayed chronologically (newest first or oldest first)
- Each entry shows: timestamp, author, type, action, entity
- Color coding by change type
- Clickable entries for details
- Filter by date range works

---

### TC-CA-021: Change Dependency Graph
**Objective:** Verify ChangeGraph shows change relationships  
**Steps:**
1. Make related changes (add class, then add property to that class)
2. Open Change Graph view

**Expected Results:**
- Nodes represent changes
- Edges represent dependencies
- Conflicting changes highlighted in red
- Graph is interactive (drag, zoom)

---

### TC-CA-022: Author Activity Chart
**Objective:** Verify AuthorActivityChart shows contributor metrics  
**Steps:**
1. Multiple users make changes
2. Open Author Activity view

**Expected Results:**
- Bar/chart showing changes per author
- Breakdown by change type (class, property, etc.)
- Time-based activity trend
- Click author to filter changes

---

### TC-CA-023: Visual Diff Comparison
**Objective:** Verify side-by-side diff for modifications  
**Steps:**
1. Select a "modified" change
2. Click "View Diff"

**Expected Results:**
- Side-by-side comparison: old value vs new value
- Additions highlighted in green
- Deletions highlighted in red
- Unchanged content in normal color

---

## Collaboration Tests

### TC-CA-024: Real-Time Change Notification
**Objective:** Verify users see changes made by others in real-time  
**Steps:**
1. UserA and UserB open same project
2. UserA makes a change
3. Observe UserB's Change Assistant

**Expected Results:**
- UserB receives notification of new change
- Change appears in timeline without page refresh
- WebSocket or polling mechanism works

---

### TC-CA-025: Git Integration
**Objective:** Verify changes link to Git commit history  
**Steps:**
1. Make changes that trigger a Git commit
2. Open change details
3. Check Git commit reference

**Expected Results:**
- Change linked to Git commit hash
- Commit message viewable
- Link to repository diff
- Author matches Git author

---

### TC-CA-026: Multi-User Concurrent Workflow
**Objective:** Verify full workflow with multiple simultaneous users  
**Steps:**
1. UserA adds 3 classes
2. UserB modifies 2 of those classes simultaneously
3. Admin reviews all pending changes
4. Resolve any conflicts
5. Approve valid changes

**Expected Results:**
- All changes tracked correctly
- Conflicts detected where applicable
- Resolution workflow works end-to-end
- Final ontology state consistent

---

## Integration Tests

### TC-CA-027: Change Assistant ↔ Editor Sync
**Objective:** Verify changes in editor appear in Change Assistant  
**Steps:**
1. Make changes in the ontology editor view
2. Switch to Change Assistant

**Expected Results:**
- All editor changes reflected
- Real-time sync (no manual refresh needed)

---

### TC-CA-028: Change Assistant ↔ Graph View Sync
**Objective:** Verify approved changes reflected in Graph View  
**Steps:**
1. Approve a class addition change
2. Switch to Graph View

**Expected Results:**
- New class node visible in graph
- Relationships updated
- No stale data

---

## Edge Case Tests

### TC-CA-029: Empty Change History
**Objective:** Verify display when no changes exist  
**Expected Results:**
- "No changes recorded" message
- Empty state UI (not blank/broken)

---

### TC-CA-030: Rapid Sequential Changes
**Objective:** Verify handling many changes in quick succession  
**Steps:**
1. Make 20+ changes within 30 seconds

**Expected Results:**
- All changes tracked individually
- No duplicate records
- Timeline scrollable and performant
- No dropped changes

---

### TC-CA-031: Rollback Cascade
**Objective:** Verify rollback of change that has dependent changes  
**Steps:**
1. Add class A
2. Add subclass B under A
3. Rollback addition of A

**Expected Results:**
- Warning about dependent changes
- Option to cascade rollback or abort
- If cascaded: both A and B removed
- If aborted: nothing changed

---

### TC-CA-032: Change on Deleted Entity
**Objective:** Verify handling when modifying an entity that was already deleted  
**Steps:**
1. UserA deletes class X
2. UserB (unaware) tries to modify class X

**Expected Results:**
- Conflict detected: entity no longer exists
- UserB's change marked as conflicted
- Clear error message with context

---

## Test Summary Matrix

| Test ID | Category | Priority | Status |
|---------|----------|----------|--------|
| TC-CA-001 | Change Tracking | P0 | ☐ |
| TC-CA-002 | Change Tracking | P0 | ☐ |
| TC-CA-003 | Change Tracking | P0 | ☐ |
| TC-CA-004 | Change Tracking | P1 | ☐ |
| TC-CA-005 | Change Tracking | P1 | ☐ |
| TC-CA-006 | Change Tracking | P1 | ☐ |
| TC-CA-007 | Change Tracking | P2 | ☐ |
| TC-CA-008 | Comments | P1 | ☐ |
| TC-CA-009 | Conflict Detection | P0 | ☐ |
| TC-CA-010 | Conflict Detection | P0 | ☐ |
| TC-CA-011 | Conflict Detection | P1 | ☐ |
| TC-CA-012 | Conflict Detection | P1 | ☐ |
| TC-CA-013 | Conflict Resolution | P0 | ☐ |
| TC-CA-014 | Conflict Resolution | P0 | ☐ |
| TC-CA-015 | Conflict Resolution | P1 | ☐ |
| TC-CA-016 | Rollback | P0 | ☐ |
| TC-CA-017 | Review Workflow | P0 | ☐ |
| TC-CA-018 | Review Workflow | P0 | ☐ |
| TC-CA-019 | Review Workflow | P1 | ☐ |
| TC-CA-020 | Visualization | P1 | ☐ |
| TC-CA-021 | Visualization | P1 | ☐ |
| TC-CA-022 | Visualization | P2 | ☐ |
| TC-CA-023 | Visualization | P1 | ☐ |
| TC-CA-024 | Collaboration | P0 | ☐ |
| TC-CA-025 | Integration | P1 | ☐ |
| TC-CA-026 | Collaboration | P0 | ☐ |
| TC-CA-027 | Integration | P0 | ☐ |
| TC-CA-028 | Integration | P1 | ☐ |
| TC-CA-029 | Edge Cases | P2 | ☐ |
| TC-CA-030 | Edge Cases | P1 | ☐ |
| TC-CA-031 | Edge Cases | P1 | ☐ |
| TC-CA-032 | Edge Cases | P1 | ☐ |
