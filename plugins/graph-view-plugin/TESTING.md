# Graph View Plugin - Testing Document

**Plugin:** @ontocode/graph-view-plugin v3.1.0  
**Category:** Visualization  
**Last Updated:** April 2026

---

## Table of Contents
1. [Overview](#overview)
2. [Test Environment Setup](#test-environment-setup)
3. [Component Tests](#component-tests)
4. [Service Tests](#service-tests)
5. [Integration Tests](#integration-tests)
6. [Performance Tests](#performance-tests)
7. [Accessibility Tests](#accessibility-tests)

---

## Overview

The Graph View Plugin provides D3.js-powered graph visualization with hierarchical lazy loading, WebVOWL notation, smart search, path visualization, and 60 FPS performance targeting 100,000+ nodes.

### Key Components Under Test
- `GraphViewSidebar.tsx` — Entity selector, filters, statistics panel
- `MatrixView.tsx` — Adjacency matrix visualization
- `StatsDashboard.tsx` — Real-time ontology metrics
- `UnifiedSidebar.tsx` — Consolidated sidebar UI

### Key Services Under Test
- `GraphDataFetchService.ts` — Data fetching from GraphDB with lazy loading
- `GraphDataService.ts` — Caching and synchronization (5-min TTL)
- `GraphMutationService.ts` — Graph modification with optimistic updates
- `VOWLNotationService.ts` — VOWL-compliant visual representation

### API Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/ontology/{projectId}/graph` | Fetch graph data |
| POST | `/api/ontology/{projectId}/graph/nodes` | Create node |
| PUT | `/api/ontology/{projectId}/graph/nodes/{nodeId}` | Update node |
| POST | `/api/collab-graph/{projectId}/clear-cache` | Clear backend cache |

---

## Test Environment Setup

### Prerequisites
- OntoCode platform running (Docker Compose or local)
- GraphDB with at least one loaded ontology
- Browser: Chrome 90+ / Firefox 88+ / Edge 90+

### Test Ontologies
| File | Description | Size |
|------|-------------|------|
| `test-ontology.owl` | Comprehensive test ontology with all entity types | Medium |
| `sample-ontology.owl` | Simple 5-class ontology for basic testing | Small |
| Large OWL file (1000+ classes) | Performance testing | Large |

---

## Component Tests

### 1. Graph Rendering

#### TC-GV-001: Initial Graph Load
**Objective:** Verify graph loads and renders correctly on plugin activation  
**Preconditions:** Ontology loaded in editor  
**Steps:**
1. Open an ontology file in the editor
2. Activate the Graph View plugin from the sidebar
3. Wait for graph to render

**Expected Results:**
- Loading spinner appears during data fetch
- Nodes render as circles with labels
- Edges render as lines connecting nodes
- No JavaScript console errors
- Graph is interactive (draggable, zoomable)

---

#### TC-GV-002: Force-Directed Layout
**Objective:** Verify D3.js force-directed layout arranges nodes properly  
**Steps:**
1. Load a medium ontology (50+ classes)
2. Select "Force" layout mode
3. Wait for simulation to stabilize

**Expected Results:**
- Nodes spread evenly across viewport
- Connected nodes cluster together
- No node overlapping after stabilization
- Smooth animation during simulation

---

#### TC-GV-003: WebVOWL Layout
**Objective:** Verify WebVOWL concentric ring layout  
**Steps:**
1. Load test-ontology.owl
2. Switch to "WebVOWL" visualization mode
3. Verify classes arranged in concentric circles
4. Verify datatypes positioned separately

**Expected Results:**
- `owl:Thing` at center (if present)
- Classes in inner rings based on hierarchy depth
- Datatypes in separate ring
- Clear spacing between nodes
- Readable labels

---

#### TC-GV-004: Class Distance Parameter
**Objective:** Verify Class Distance slider affects layout  
**Steps:**
1. Load ontology in WebVOWL mode
2. Open VOWL Controls (settings icon)
3. Set Class Distance to 50 (minimum)
4. Set Class Distance to 150 (maximum)
5. Reset to 100 (default)

**Expected Results:**
- Distance 50: Compact layout, smaller radii
- Distance 100: Standard spacing
- Distance 150: Expanded layout, larger radii
- Layout updates immediately on slider change

---

#### TC-GV-005: Datatype Distance Parameter
**Objective:** Verify Datatype Distance slider affects datatype positioning  
**Steps:**
1. Load ontology with datatypes in WebVOWL mode
2. Adjust Datatype Distance from 20 to 200

**Expected Results:**
- Datatypes move closer/farther from center
- Does not affect class positioning
- Immediate visual update

---

#### TC-GV-006: Zoom and Pan
**Objective:** Verify zoom and pan controls  
**Steps:**
1. Load a graph
2. Scroll wheel to zoom in/out
3. Click and drag background to pan
4. Double-click to reset zoom

**Expected Results:**
- Zoom range: 0.1x to 10x
- Pan moves entire graph
- Smooth transitions
- Labels remain readable at reasonable zoom levels

---

#### TC-GV-007: Node Selection
**Objective:** Verify node selection and detail display  
**Steps:**
1. Click on a class node
2. Click on a property edge
3. Click on an individual node
4. Click on empty space (deselect)

**Expected Results:**
- Selected node highlighted with distinct border
- Sidebar shows entity details (IRI, type, annotations)
- Connected edges highlighted
- Clicking empty space clears selection

---

#### TC-GV-008: Layout Pause/Resume
**Objective:** Verify layout simulation can be paused and resumed  
**Steps:**
1. Load a graph with active force simulation
2. Click Pause button
3. Drag a node
4. Click Resume button

**Expected Results:**
- Pause: Nodes freeze in place, no simulation forces
- Dragging while paused: Node moves but stays where placed
- Resume: Simulation resumes, forces re-applied

---

### 2. Sidebar Components

#### TC-GV-009: GraphViewSidebar Filters
**Objective:** Verify entity type filtering  
**Steps:**
1. Open GraphViewSidebar
2. Toggle "Show Classes" off
3. Toggle "Show Properties" off
4. Toggle "Show Individuals" off
5. Re-enable all filters

**Expected Results:**
- Toggling off removes corresponding nodes from graph
- Toggling on restores them
- Edge count updates accordingly
- Statistics panel reflects filtered counts

---

#### TC-GV-010: Search Functionality
**Objective:** Verify smart search locates entities  
**Steps:**
1. Open search in sidebar
2. Type a partial class name
3. Select a search result
4. Clear search

**Expected Results:**
- Autocomplete suggestions appear after 2+ characters
- Matching entities listed with type icons
- Selecting result centers graph on that node
- Node highlighted after selection
- Clear search restores normal view

---

#### TC-GV-011: Path Visualization
**Objective:** Verify path finding between two nodes  
**Steps:**
1. Select a source node
2. Use "Find Path" to select a target node
3. Observe highlighted path

**Expected Results:**
- Shortest path highlighted between nodes
- Intermediate nodes and edges distinctly colored
- Non-path elements dimmed
- Path length displayed

---

### 3. Matrix View

#### TC-GV-012: Adjacency Matrix Rendering
**Objective:** Verify MatrixView displays relationships correctly  
**Steps:**
1. Switch to Matrix View tab
2. Load a small ontology (10-20 classes)
3. Hover over cells
4. Click a filled cell

**Expected Results:**
- Row and column headers show class names
- Filled cells indicate relationships
- Hover tooltip shows relationship details
- Click navigates to relationship details

---

### 4. Statistics Dashboard

#### TC-GV-013: Stats Dashboard Metrics
**Objective:** Verify StatsDashboard shows correct counts  
**Steps:**
1. Load an ontology
2. Open Statistics Dashboard
3. Compare displayed metrics with known ontology counts

**Expected Results:**
- Class count matches ontology
- Property count (object + data) matches
- Individual count matches
- Axiom count matches
- Metrics update after ontology modifications

---

## Service Tests

### 5. GraphDataFetchService

#### TC-GV-014: Fetch Graph Data
**Objective:** Verify parallel data fetching from GraphDB  
**Steps:**
1. Load an ontology project
2. Monitor network requests during graph load

**Expected Results:**
- Nodes and edges fetched in parallel
- All OWL classes returned recursively
- Individuals, object/data/annotation properties included
- Datatypes included
- No N+1 query problem (batch fetching)

---

#### TC-GV-015: Lazy Loading — Expand on Demand
**Objective:** Verify hierarchical lazy loading works  
**Steps:**
1. Load a large ontology
2. Verify only root nodes load initially
3. Click expand on a root node
4. Verify child nodes load

**Expected Results:**
- Initial load shows only root-level classes
- Expand fetches children on demand
- Loading indicator during child fetch
- Already-loaded branches don't re-fetch
- Smooth animation when new nodes appear

---

#### TC-GV-016: Fetch Error Handling
**Objective:** Verify graceful handling of API failures  
**Steps:**
1. Disconnect GraphDB or use invalid project ID
2. Attempt to load graph

**Expected Results:**
- User-friendly error message displayed
- No unhandled exceptions in console
- Retry option available
- UI remains functional

---

### 6. GraphDataService (Caching)

#### TC-GV-017: Cache Hit
**Objective:** Verify cached data is reused within TTL  
**Steps:**
1. Load graph data for a project
2. Navigate away and back within 5 minutes
3. Monitor network requests

**Expected Results:**
- Second load uses cached data (no network request)
- Data rendered instantly
- Cache TTL: 5 minutes

---

#### TC-GV-018: Cache Invalidation
**Objective:** Verify cache clears after TTL or on demand  
**Steps:**
1. Load graph data
2. Wait 5+ minutes OR call `clearProjectCache(projectId)`
3. Reload graph

**Expected Results:**
- Fresh data fetched from backend
- Local cache cleared
- Backend cache clear endpoint called

---

#### TC-GV-019: Request Cancellation
**Objective:** Verify abort controller cancels pending requests  
**Steps:**
1. Start loading a large graph
2. Navigate away before loading completes
3. Return and load again

**Expected Results:**
- Previous request aborted (no stale data)
- New request starts cleanly
- No duplicate renders

---

### 7. GraphMutationService

#### TC-GV-020: Create Node (Optimistic Update)
**Objective:** Verify node creation with optimistic UI update  
**Steps:**
1. Right-click on graph → "Add Class"
2. Enter class name and IRI
3. Submit

**Expected Results:**
- Node appears immediately in graph (optimistic)
- API call sent to backend
- On success: node persists
- On failure: node removed, error displayed

---

#### TC-GV-021: Update Node
**Objective:** Verify node update via mutation service  
**Steps:**
1. Select a node
2. Edit label or annotations in sidebar
3. Save changes

**Expected Results:**
- Changes reflected immediately in graph
- Backend updated via PUT endpoint
- Cache invalidated for modified node

---

### 8. VOWLNotationService

#### TC-GV-022: VOWL Node Styling
**Objective:** Verify VOWL-compliant visual representation  
**Steps:**
1. Switch to VOWL mode
2. Verify visual encoding of different entity types

**Expected Results:**
| Entity Type | Shape | Color | 
|-------------|-------|-------|
| Class | Circle | Light blue |
| Datatype | Rectangle | Yellow/Green |
| Object Property | Arrow/Line | Blue |
| Data Property | Arrow/Line | Green |
| Individual | Diamond | Purple |

- Node radius proportional to importance
- Stroke color/width matches VOWL spec
- Dashed lines for inferred relationships
- Statistics computed: classCount, propertyCount, individualCount

---

## Integration Tests

#### TC-GV-023: Graph ↔ Editor Synchronization
**Objective:** Verify changes in editor reflect in graph  
**Steps:**
1. Open ontology in editor and graph view side-by-side
2. Add a new class in the editor
3. Check graph view updates

**Expected Results:**
- New class node appears in graph
- Relationships updated
- Statistics dashboard updated

---

#### TC-GV-024: Graph ↔ Reasoner Integration
**Objective:** Verify inferred relationships appear in graph  
**Steps:**
1. Run reasoner on loaded ontology
2. Switch to graph view
3. Toggle "Show Inferred" option

**Expected Results:**
- Inferred subclass relationships shown (dashed lines)
- Inferred individual types displayed
- Clear visual distinction between asserted and inferred

---

#### TC-GV-025: Multi-User Collaboration
**Objective:** Verify graph updates when another user makes changes  
**Steps:**
1. User A opens graph for a project
2. User B adds a class to the same project
3. Observe User A's graph

**Expected Results:**
- User A receives real-time update notification
- New node appears after refresh/notification
- No data conflicts

---

## Performance Tests

#### TC-GV-026: Small Ontology (< 100 nodes)
**Objective:** Verify smooth rendering for small ontologies  
**Metrics:**
- Initial load: < 2 seconds
- Frame rate: 60 FPS
- Memory: < 50 MB

---

#### TC-GV-027: Medium Ontology (1,000 nodes)
**Objective:** Verify acceptable performance for medium ontologies  
**Metrics:**
- Initial load: < 5 seconds
- Frame rate: ≥ 30 FPS
- Memory: < 200 MB
- Lazy loading active

---

#### TC-GV-028: Large Ontology (10,000+ nodes)
**Objective:** Verify lazy loading prevents UI freeze  
**Metrics:**
- Initial load (root nodes only): < 3 seconds
- Expand node: < 1 second per level
- Frame rate: ≥ 30 FPS with visible nodes
- Memory: < 500 MB

---

#### TC-GV-029: Extreme Scale (100,000+ nodes)
**Objective:** Verify plugin handles maximum scale  
**Metrics:**
- Only visible nodes rendered (virtual scrolling)
- No browser crash or tab freeze
- Search still responsive (< 500ms)
- Memory stays under 1 GB

---

## Accessibility Tests

#### TC-GV-030: Keyboard Navigation
**Objective:** Verify graph is navigable via keyboard  
**Steps:**
1. Tab to graph view
2. Use arrow keys to navigate nodes
3. Press Enter to select
4. Press Escape to deselect

**Expected Results:**
- Focus indicator visible on current node
- Arrow keys move between connected nodes
- Enter triggers selection
- Screen reader announces node details

---

#### TC-GV-031: Color Contrast
**Objective:** Verify sufficient contrast for all visual elements  
**Expected Results:**
- All text meets WCAG AA (4.5:1 contrast ratio)
- Node colors distinguishable for color-blind users
- Legend available for color coding

---

## Test Summary Matrix

| Test ID | Category | Priority | Automated | Status |
|---------|----------|----------|-----------|--------|
| TC-GV-001 | Rendering | P0 | Manual | ☐ |
| TC-GV-002 | Layout | P0 | Manual | ☐ |
| TC-GV-003 | Layout | P1 | Manual | ☐ |
| TC-GV-004 | Controls | P1 | Manual | ☐ |
| TC-GV-005 | Controls | P2 | Manual | ☐ |
| TC-GV-006 | Interaction | P0 | Manual | ☐ |
| TC-GV-007 | Interaction | P0 | Manual | ☐ |
| TC-GV-008 | Controls | P1 | Manual | ☐ |
| TC-GV-009 | Sidebar | P1 | Manual | ☐ |
| TC-GV-010 | Search | P0 | Manual | ☐ |
| TC-GV-011 | Navigation | P1 | Manual | ☐ |
| TC-GV-012 | Matrix | P1 | Manual | ☐ |
| TC-GV-013 | Statistics | P1 | Manual | ☐ |
| TC-GV-014 | Service | P0 | Manual | ☐ |
| TC-GV-015 | Lazy Loading | P0 | Manual | ☐ |
| TC-GV-016 | Error Handling | P0 | Manual | ☐ |
| TC-GV-017 | Caching | P1 | Manual | ☐ |
| TC-GV-018 | Caching | P1 | Manual | ☐ |
| TC-GV-019 | Cancellation | P1 | Manual | ☐ |
| TC-GV-020 | Mutation | P0 | Manual | ☐ |
| TC-GV-021 | Mutation | P1 | Manual | ☐ |
| TC-GV-022 | VOWL | P1 | Manual | ☐ |
| TC-GV-023 | Integration | P0 | Manual | ☐ |
| TC-GV-024 | Integration | P1 | Manual | ☐ |
| TC-GV-025 | Collaboration | P1 | Manual | ☐ |
| TC-GV-026 | Performance | P0 | Manual | ☐ |
| TC-GV-027 | Performance | P1 | Manual | ☐ |
| TC-GV-028 | Performance | P1 | Manual | ☐ |
| TC-GV-029 | Performance | P2 | Manual | ☐ |
| TC-GV-030 | Accessibility | P2 | Manual | ☐ |
| TC-GV-031 | Accessibility | P2 | Manual | ☐ |
