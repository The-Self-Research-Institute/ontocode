# Graph View Plugin Test Cases

## Test Overview
This document outlines test cases for the Graph View Plugin, covering WebVOWL visualization, force-directed layout, hierarchical navigation, and legend functionality.

## Test Files
- **test-ontology.owl** - Comprehensive test ontology with various entity types
- **sample-ontology.owl** - Simple 5-class ontology for basic testing

---

## 1. WebVOWL Layout Tests

### TC-001: Basic Layout Rendering
**Objective:** Verify WebVOWL concentric ring layout displays correctly
**Test Data:** test-ontology.owl
**Steps:**
1. Load test-ontology.owl
2. Switch to "WebVOWL" visualization mode
3. Verify classes arranged in concentric circles
4. Verify datatypes positioned separately
5. Verify no node overlap

**Expected Results:**
- Thing at center (if present)
- Classes in inner rings based on hierarchy depth
- Datatypes in separate ring
- Clear spacing between nodes
- Readable labels

---

### TC-002: Class Distance Parameter
**Objective:** Verify Class Distance slider affects layout
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology in WebVOWL mode
2. Open VOWL Controls (settings icon)
3. Set Class Distance to 50 (minimum)
4. Observe layout compression
5. Set Class Distance to 150 (maximum)
6. Observe layout expansion
7. Reset to 100 (default)

**Expected Results:**
- Distance 50: Compact layout, smaller radii
- Distance 100: Standard spacing
- Distance 150: Expanded layout, larger radii
- Layout updates immediately on slider change

---

### TC-003: Datatype Distance Parameter
**Objective:** Verify Datatype Distance slider affects datatype positioning
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology with datatypes in WebVOWL mode
2. Open VOWL Controls
3. Adjust Datatype Distance from 20 to 200
4. Observe datatype node positioning

**Expected Results:**
- Datatypes move closer/farther from center
- Does not affect class positioning
- Immediate visual update

---

## 2. Edge Routing Tests

### TC-004: Straight Property Edges
**Objective:** Verify property relation edges are straight lines in WebVOWL
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology in WebVOWL mode
2. Identify object and data properties
3. Verify edges are straight (no curves)
4. Verify parallel edges have perpendicular offsets

**Expected Results:**
- All property edges are straight lines
- Parallel edges offset to prevent overlap
- Edge labels positioned at midpoints
- No edge crossings within same layer

---

### TC-005: Edge Layer Priority
**Objective:** Verify edge layering prevents crossings
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology in WebVOWL mode
2. Observe different edge types:
   - subClassOf (dashed)
   - propertyRelation (solid black)
   - domain (green)
   - range (orange)
3. Verify parallel edges of same type are offset

**Expected Results:**
- Layer 0: subClassOf edges
- Layer 1: propertyRelation edges
- Layer 2: domain/range edges
- Perpendicular offsets for parallel edges in same layer

---

## 3. Hierarchy & Navigation Tests

### TC-006: Collapse All Functionality
**Objective:** Verify Collapse All shows roots + first-level children
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology and expand several nodes
2. Click "Collapse All" button
3. Count visible nodes

**Expected Results:**
- Root classes visible (e.g., Thing)
- First-level children of roots visible
- All non-class entities visible (individuals, properties)
- Deeper hierarchy levels collapsed
- Collapse icons show "▶" for expandable nodes

---

### TC-007: Expand/Collapse Individual Nodes
**Objective:** Verify expand/collapse toggles work correctly
**Test Data:** test-ontology.owl
**Steps:**
1. Start with Collapse All
2. Click expand icon (▶) on a parent class
3. Verify children appear
4. Click collapse icon (▼) on same class
5. Verify children disappear

**Expected Results:**
- Expand shows immediate children only
- Collapse hides all descendants
- Icons toggle between ▶ and ▼
- Graph re-layouts smoothly

---

### TC-008: Lazy Loading
**Objective:** Verify hierarchy loads incrementally
**Test Data:** Large ontology with deep hierarchy
**Steps:**
1. Load large ontology
2. Note initial node count
3. Expand root classes one by one
4. Observe node additions

**Expected Results:**
- Initial load shows only roots + first level
- Each expand loads only immediate children
- Performance remains responsive
- No duplicate nodes

---

## 4. Legend Tests

### TC-009: Dynamic Legend Updates
**Objective:** Verify legend reflects currently visible entities
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology in WebVOWL mode
2. Open Legend section in sidebar
3. Note initial legend items
4. Collapse a branch containing datatypes
5. Observe legend updates
6. Expand branch again
7. Observe legend updates

**Expected Results:**
- Legend shows only visible node types
- "Datatype / Literal" disappears when all datatypes hidden
- "External Class" appears only when external classes visible
- Edge types reflect visible relationships
- Symbols match node/edge appearance in graph

---

### TC-010: Legend Symbol Accuracy
**Objective:** Verify legend symbols match graph visualization
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology in WebVOWL mode
2. Compare legend symbols with actual nodes:
   - Class: Blue circle with solid border
   - Thing: White circle with dashed border
   - Datatype: Orange rounded rectangle with dashed border
   - Individual: Red rectangle
3. Compare edge symbols:
   - SubClass Of: Black dashed line
   - Object Property: Black solid line
   - Data Property: Black solid line
   - Domain: Green solid line
   - Range: Orange solid line

**Expected Results:**
- Legend symbols exactly match graph rendering
- Colors accurate
- Border styles (solid/dashed) accurate
- Shapes correct

---

### TC-011: Property Label Colors
**Objective:** Verify property labels color-coded correctly
**Test Data:** test-ontology.owl with functional properties
**Steps:**
1. Load ontology in WebVOWL mode
2. Identify functional properties (green background)
3. Identify regular properties (blue background)
4. Check legend shows both colors

**Expected Results:**
- Functional properties: Green background (#C8E6C9)
- Regular properties: Blue background (#BBDEFB)
- Legend lists both if present
- Label colors consistent

---

## 5. Filter & Search Tests

### TC-012: Node Type Filter
**Objective:** Verify node type filtering works
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology
2. Open "Node Types" filter
3. Uncheck "Class"
4. Verify only classes hidden
5. Re-check "Class"
6. Verify classes reappear

**Expected Results:**
- Unchecked types hidden from graph
- Legend updates to show only visible types
- Related edges remain if targets visible
- Performance smooth

---

### TC-013: Property Visibility Toggle
**Objective:** Verify property visibility controls
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology in WebVOWL mode
2. Open "Property Visibility" section
3. Toggle "Object Properties" off
4. Verify object property edges disappear
5. Toggle "Data Properties" off
6. Toggle "SubClass Of Relationships" off
7. Toggle all back on

**Expected Results:**
- Each toggle hides/shows corresponding edge type
- Counts displayed next to each toggle
- Immediate visual update
- No node position changes

---

### TC-014: Entity Search
**Objective:** Verify search functionality
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology
2. Type "Person" in Entity Selector search
3. Select result
4. Verify node highlighted and centered
5. Clear search and try partial match
6. Try case-insensitive search

**Expected Results:**
- Search filters entity list in real-time
- Selecting entity centers it in viewport
- Node highlighted with visual indicator
- Case-insensitive matching
- Partial string matching works

---

## 6. Visualization Mode Tests

### TC-015: Mode Switching
**Objective:** Verify switching between visualization modes
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology in Force-Directed mode
2. Note node positions
3. Switch to WebVOWL mode
4. Verify layout changes to concentric rings
5. Switch to OntoGraph mode
6. Switch back to Force-Directed
7. Verify physics simulation active

**Expected Results:**
- Force-Directed: Physics-based, draggable nodes
- WebVOWL: Static concentric layout
- OntoGraph: Static hierarchical layout
- Smooth transitions between modes
- No data loss

---

### TC-016: Force-Directed Physics
**Objective:** Verify force simulation parameters work
**Test Data:** test-ontology.owl
**Steps:**
1. Load in Force-Directed mode
2. Adjust Link Strength slider
3. Observe edge tension changes
4. Adjust Charge Strength
5. Observe node repulsion changes
6. Drag a node and release
7. Verify physics stabilization

**Expected Results:**
- Link Strength affects edge rigidity
- Charge Strength affects spacing
- Nodes settle into stable positions
- Dragging creates temporary displacement
- Physics re-stabilizes after drag

---

## 7. Performance Tests

### TC-017: Large Ontology Loading
**Objective:** Verify performance with large ontologies
**Test Data:** Ontology with 100+ classes
**Steps:**
1. Load large ontology
2. Measure initial load time
3. Expand/collapse operations
4. Switch visualization modes
5. Monitor browser performance

**Expected Results:**
- Load time < 5 seconds
- Smooth animations (60fps target)
- No browser freeze
- Memory usage reasonable
- Responsive interactions

---

### TC-018: Rapid Expand/Collapse
**Objective:** Verify stability under rapid user input
**Test Data:** test-ontology.owl
**Steps:**
1. Load ontology
2. Rapidly click expand/collapse on multiple nodes
3. Quickly toggle filters on/off
4. Switch modes rapidly
5. Check for errors in console

**Expected Results:**
- No crashes or errors
- State remains consistent
- UI responsive
- No duplicate nodes
- No memory leaks

---

## 8. Edge Cases

### TC-019: Empty Ontology
**Objective:** Verify handling of empty ontology
**Steps:**
1. Create new blank ontology
2. Open in graph view
3. Try switching modes
4. Check legend

**Expected Results:**
- Empty graph displayed
- No errors in console
- "No entities to display" message
- Legend shows empty state

---

### TC-020: Single Node Ontology
**Objective:** Verify single node display
**Steps:**
1. Create ontology with only owl:Thing
2. Open in WebVOWL mode
3. Verify single node at center

**Expected Results:**
- Thing centered in viewport
- No edges
- Legend shows only Thing
- No layout errors

---

### TC-021: Cyclic Relationships
**Objective:** Verify handling of cycles in class hierarchy
**Test Data:** Ontology with circular subClassOf (malformed)
**Steps:**
1. Load ontology with cycle
2. Attempt expand operations
3. Check for infinite loops

**Expected Results:**
- No infinite loops
- Cycle detection active
- Visual indication of cycle (if applicable)
- No browser hang

---

## 9. UI/UX Tests

### TC-022: Sidebar Accordion
**Objective:** Verify sidebar sections expand/collapse
**Steps:**
1. Load ontology
2. Click each accordion header:
   - Controls
   - Node Types
   - Property Visibility
   - Entity Selector
   - Legend
   - Statistics
3. Verify exclusive expansion

**Expected Results:**
- Click header toggles section
- Chevron icon rotates
- Smooth animation
- Only one section expanded at a time (optional)

---

### TC-023: Tooltips
**Objective:** Verify node/edge tooltips display
**Steps:**
1. Load ontology
2. Hover over various nodes
3. Hover over edges
4. Verify tooltip content

**Expected Results:**
- Node tooltip shows: label, type, URI
- Edge tooltip shows: type, source, target
- Tooltip follows cursor
- Readable text with good contrast

---

### TC-024: Zoom & Pan
**Objective:** Verify viewport controls
**Steps:**
1. Load ontology
2. Use mouse wheel to zoom in/out
3. Click and drag background to pan
4. Try zoom buttons (if present)
5. Double-click node to center

**Expected Results:**
- Smooth zoom animation
- Pan moves entire graph
- Zoom limits prevent excessive zoom
- Double-click centers and highlights node

---

## 10. Data Integrity Tests

### TC-025: OWL Parsing Accuracy
**Objective:** Verify ontology data correctly parsed
**Test Data:** test-ontology.owl
**Steps:**
1. Load test-ontology.owl
2. Check Statistics section for counts:
   - Total Entities
   - Classes
   - Individuals
   - Object Properties
   - Datatype Properties
3. Manually count entities in OWL file
4. Compare counts

**Expected Results:**
- Counts match OWL file exactly
- All entities loaded
- No missing relationships
- Properties correctly typed

---

## Test Execution Checklist

### Before Testing
- [ ] Latest plugin build installed
- [ ] Browser console open (F12)
- [ ] Test files available
- [ ] Network stable

### During Testing
- [ ] Record actual vs expected results
- [ ] Screenshot failures
- [ ] Note console errors
- [ ] Check browser performance tab

### After Testing
- [ ] Clear browser cache
- [ ] Test in different browser
- [ ] Report bugs with reproduction steps
- [ ] Suggest improvements

---

## Known Issues & Limitations
- Large ontologies (500+ entities) may have performance impact
- Very deep hierarchies (10+ levels) may require scrolling
- Circular dependencies not fully supported
- IE11 not supported (modern browsers only)

---

## Browser Compatibility Matrix

| Browser | Version | WebVOWL | Force-Directed | OntoGraph | Notes |
|---------|---------|---------|----------------|-----------|-------|
| Chrome  | 90+     | ✅      | ✅             | ✅        | Recommended |
| Firefox | 88+     | ✅      | ✅             | ✅        | Recommended |
| Safari  | 14+     | ✅      | ✅             | ✅        | Minor CSS differences |
| Edge    | 90+     | ✅      | ✅             | ✅        | Chromium-based |

---

## Regression Testing
Run full test suite after:
- Layout algorithm changes
- Filter/search modifications
- Legend generation updates
- Performance optimizations
- D3.js version upgrades

---

## Contact
For test failures or questions, contact the development team or file an issue in the repository.
