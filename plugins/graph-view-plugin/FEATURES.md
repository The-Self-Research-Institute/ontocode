# Graph View Plugin - Feature List

## Overview
The Graph View Plugin provides interactive visualization of ontologies with support for multiple layout algorithms, hierarchical navigation, and dynamic filtering capabilities.

## Quick Feature Summary

1. **Multiple Visualization Modes** - Switch between WebVOWL, Force-Directed, and OntoGraph layouts for different visualization needs
2. **Layout Controls** - Adjust class and datatype distances in real-time with sliders (20-200 range)
3. **Color-Coded Node Visualization** - Distinct colors and shapes for classes, datatypes, individuals, and properties
4. **Intelligent Edge Routing** - Layer-based straight-line routing prevents edge crossings and overlaps
5. **Expand/Collapse Navigation** - Interactive hierarchy control with expand/collapse icons for each node
6. **Lazy Loading** - Incremental hierarchy loading prevents performance issues with large ontologies
7. **Node Type Filters** - Toggle visibility of classes, individuals, datatypes, and properties
8. **Property Visibility Controls** - Show/hide object properties, data properties, and subClassOf relationships with counts
9. **Entity Search & Selection** - Real-time search with auto-centering and highlighting of selected entities
10. **Dynamic Context-Aware Legend** - Automatically updates to show only visible node and edge types
11. **Real-Time Statistics** - Live entity counts for classes, individuals, properties, and relationships
12. **Interactive Tooltips** - Hover over nodes and edges to see labels, types, and URIs
13. **Zoom & Pan Controls** - Mouse wheel zoom, drag to pan, and double-click to center nodes
14. **Sidebar Organization** - Collapsible accordion sections for controls, filters, search, and statistics
15. **Settings Management** - Toggle VOWL controls visibility with settings icon for cleaner interface
16. **Performance Optimizations** - Memoized computations, efficient filtering, and optimized DOM manipulation
17. **Layer-Based Edge Routing** - Priority system (subClassOf → properties → domain/range) with perpendicular offsets
18. **OWL 2 Parsing Support** - Full support for classes, properties, individuals, and relationships from OWL files
19. **Browser Compatibility** - Tested on Chrome 90+, Firefox 88+, Safari 14+, and Edge 90+
20. **Technical Stack** - Built with React 18, D3.js v7, TypeScript, and Webpack
21. **Future Roadmap** - Planned features include SVG/PNG export, custom color schemes, and SPARQL integration
22. **Known Limitations** - Performance impact with 500+ entities, deep hierarchies require scrolling
23. **Testing Support** - Includes test ontologies and 25 comprehensive test cases
24. **Documentation** - Complete README, test cases, debug guide, and inline code documentation


Toolbar Features (Top of Graph View)
Primary Actions & Visualization
Refresh Data: Reloads graph data from the server to fetch the latest ontology changes.

Visualization Selector: Switch between Force-Directed (physics-based), WebVOWL (concentric rings), and OntoGraph (class hierarchy) modes.

View & Navigation Controls
Zoom Controls: Buttons to increase (+) or decrease (-) the zoom level.

Fit to Screen: Auto-adjusts the viewport to display all visible nodes.

Expand All: Reveals the full hierarchy of all entity types (classes, properties, individuals).

Collapse All: Retracts the view to show only root entities and immediate children.

Feature Toggles
Edit Mode: Enables editing capabilities (add/delete/modify) when not in read-only mode.

Panel Toggles: Buttons to show/hide Search, Filters, Settings, Explorer, and Legend panels.

Visual Aids: Toggle the background Grid for alignment or Physics for simulation control.

Collaboration: Opens the real-time multi-user editing panel.

Statistics & Export
Status Display: Shows visible/total node counts, expanded nodes, and current zoom level.

Lazy Loading Indicator: Displays a "⚡" icon when the graph exceeds 1000 nodes.

Export Options: Download the current graph view as a vector SVG or raster PNG image.

Sidebar Features (Right Panel - Graph Explorer)
Search & Hierarchy
Entity Search: Real-time, case-insensitive filtering by name or IRI across all tabs.

Entity Tabs: Browse specific types via six dedicated tabs (Classes, Object/Data Properties, Individuals, Annotations, Datatypes).

Class Navigator: Tree view with specific Parent (↑) and Child (↓) expansion buttons.

Hierarchy Dialog: Click class names to open a detailed navigator showing parent/child counts.

Graph Filtering
Node Type Filters: Checkboxes to toggle classes, individuals, datatypes, and annotations with live counts.

Relationship Filters: Toggle edge types (subClassOf, domain, range) with specific counts.

Property Visibility: Controls to show/hide object and datatype properties in WebVOWL/OntoGraph modes.

WebVOWL Controls
Distance Sliders: Adjust the radius of Class and Datatype rings (20–200 range).

Simulation Controls: Options to Pause/Resume physics or Reset the layout to defaults.

Entity Details & Statistics
Selected Node Info: Displays Label, Type, IRI, Description, and related entities (Parents, Children, Instances).

Global Statistics: Comprehensive breakdown of total nodes, total edges, and counts per entity type.

Dynamic Legend: Visual guide for node shapes/colors and edge styles; updates based on visible content.

Resize Handle: Drag the left edge to adjust sidebar width (280–600px).

Special Interactions
Graph Interactivity
Cross-Highlighting: Hovering a sidebar entity highlights the corresponding node in the graph.

Synced Selection: Clicking an entity selects it in both the sidebar and the graph view.

Real-Time Filtering: All filter changes immediately update the graph visualization and statistics.

Context Menu: Right-click nodes to Add Child/Sibling, Delete Class, or Expand/Collapse specific branches.

---

## Core Visualization Features

### 1. Multiple Visualization Modes
- **WebVOWL Mode**: OWL-compliant concentric ring layout
  - Classes arranged in hierarchical rings
  - Datatypes positioned in separate outer ring
  - Follows WebVOWL notation standards
  - Static, predictable layout

- **Force-Directed Mode**: Physics-based dynamic layout
  - Real-time force simulation
  - Draggable nodes with physics response
  - Customizable force parameters
  - Natural clustering of related entities

- **OntoGraph Mode**: Hierarchical tree layout
  - Top-down class hierarchy visualization
  - Automatic tree positioning
  - Clear parent-child relationships

### 2. WebVOWL Layout Controls
- **Class Distance Slider** (20-200, default: 100)
  - Adjusts radius of class rings
  - Real-time layout updates
  - Compact to expanded views

- **Datatype Distance Slider** (20-200, default: 100)
  - Controls datatype node positioning
  - Independent from class distance
  - Optimizes datatype visibility

### 3. Node Visualization
- **Color-Coded Node Types**:
  - Classes: Blue circles (solid border)
  - Thing: White circle (dashed border)
  - Datatypes: Orange rounded rectangles (dashed border)
  - Individuals: Red rectangles
  - Object Properties: Green circles
  - Data Properties: Orange squares

- **Visual Indicators**:
  - Border styles (solid/dashed) indicate special types
  - Node size reflects importance/hierarchy
  - Hover highlights with tooltips
  - Selection highlighting with centering

### 4. Edge Visualization
- **Straight-Line Routing (WebVOWL)**:
  - No curves in property edges
  - Layer-based perpendicular offsets
  - Prevents edge crossings within layers
  - Clear visual separation

- **Edge Types**:
  - subClassOf: Black dashed line (hierarchy)
  - Object Property: Black solid line
  - Data Property: Black solid line
  - Domain: Green solid line
  - Range: Orange solid line
  - instanceOf: Gray solid line

- **Edge Labels**:
  - Property names displayed at midpoint
  - Color-coded backgrounds (functional vs regular)
  - Functional properties: Green background (#C8E6C9)
  - Regular properties: Blue background (#BBDEFB)

---

## Hierarchical Navigation

### 5. Expand/Collapse Controls
- **Individual Node Control**:
  - Click expand icon (▶) to show children
  - Click collapse icon (▼) to hide descendants
  - Icons toggle based on state
  - Smooth transitions

- **Collapse All Button**:
  - Shows root classes + first-level children
  - Maintains all non-class entities (individuals, properties)
  - Quick reset to overview state
  - Prevents information overload

### 6. Lazy Loading
- Initial load shows roots + first-level children only
- Children load incrementally on expansion
- Prevents performance issues with large ontologies
- Maintains responsive user experience
- No duplicate nodes in hierarchy

---

## Filtering & Search

### 7. Node Type Filters
- **Toggleable Types**:
  - Class
  - Individual
  - Datatype
  - Object Property (excluded from filter by default)
  - Data Property (excluded from filter by default)

- **Features**:
  - Multiple types can be hidden simultaneously
  - Real-time graph updates
  - Related edges remain if targets visible
  - Legend updates automatically

### 8. Property Visibility Controls
- **Toggle Options**:
  - Object Properties (with count)
  - Data Properties (with count)
  - SubClass Of Relationships (with count)

- **Behavior**:
  - Hides/shows corresponding edge types
  - Displays entity counts next to each toggle
  - Immediate visual feedback
  - Node positions remain stable

### 9. Entity Search & Selection
- **Search Features**:
  - Real-time filtering as you type
  - Case-insensitive matching
  - Partial string matching
  - Searches labels and URIs

- **Selection Behavior**:
  - Click to select entity from list
  - Auto-centers selected node in viewport
  - Highlights node with visual indicator
  - Smooth animated transition

---

## Dynamic Legend

### 10. Context-Aware Legend
- **Node Types Section**:
  - Shows only visible node types
  - Accurate color representations
  - Border style indicators
  - Updates on expand/collapse

- **Relationship Types Section**:
  - Lists visible edge types
  - SVG line representations
  - Stroke style matching (solid/dashed)
  - Color-coded by relationship type

- **Property Label Colors**:
  - Functional vs Regular distinction
  - Color swatches with labels
  - Appears only when properties visible

### 11. Symbol Accuracy
- Legend symbols exactly match graph rendering
- Colors synchronized
- Border styles (solid/dashed) consistent
- Real-time updates on graph changes
- React-based dynamic rendering

---

## Statistics & Information

### 12. Ontology Statistics
- **Entity Counts**:
  - Total Entities
  - Classes
  - Individuals
  - Object Properties
  - Datatype Properties
  - Relationships (edges)

- **Live Updates**:
  - Counts update on filter changes
  - Reflects current visible state
  - Accurate entity tracking

### 13. Node Tooltips
- **Information Displayed**:
  - Entity label
  - Entity type
  - Full URI
  - Additional metadata (if available)

- **Interaction**:
  - Appears on hover
  - Follows cursor
  - Auto-dismisses on mouse leave
  - Readable contrast

### 14. Edge Tooltips
- **Information Displayed**:
  - Relationship type
  - Source entity
  - Target entity
  - Property characteristics (functional, etc.)

---

## Viewport Controls

### 15. Zoom & Pan
- **Zoom Controls**:
  - Mouse wheel zoom in/out
  - Pinch gesture support (touch devices)
  - Zoom limits prevent excessive zoom
  - Smooth animation transitions

- **Pan Controls**:
  - Click and drag background to pan
  - Moves entire graph viewport
  - Bounded by graph extent
  - Inertia for smooth movement

- **Center Functions**:
  - Double-click node to center and highlight
  - Auto-center on entity selection
  - Animated viewport transitions
  - Smart zoom level adjustment

### 16. Force Simulation Controls
- **Link Strength Slider**: Adjusts edge tension/rigidity
- **Charge Strength Slider**: Controls node repulsion/spacing
- **Real-time Physics**: Simulation updates on parameter change
- **Drag Nodes**: Temporarily displace nodes, physics re-stabilizes

---

## User Interface

### 17. Sidebar Organization
- **Accordion Sections**:
  - Controls (physics/layout parameters)
  - VOWL Controls (WebVOWL-specific settings)
  - Node Types (visibility filters)
  - Property Visibility (edge toggles)
  - Entity Selector (search and list)
  - Legend (dynamic symbols)
  - Statistics (entity counts)

- **Collapsible Sections**:
  - Click header to expand/collapse
  - Chevron icon indicates state
  - Smooth animations
  - Saves screen space

### 18. Settings Control
- **VOWL Controls Visibility**:
  - Settings icon toggles VOWL controls
  - Controls only shown when clicked
  - Reduces UI clutter
  - Context-sensitive display

### 19. Visualization Mode Selector
- Dropdown to switch between modes
- Preserves entity selection across modes
- Smooth mode transitions
- No data loss on switch

---

## Performance Features

### 20. Optimization Techniques
- **Lazy Loading**: Incremental hierarchy loading
- **Memoized Legend**: React useMemo prevents unnecessary recalculations
- **Efficient Filtering**: O(1) Set-based visibility checks
- **Debounced Search**: Reduces re-renders during typing
- **Canvas Optimization**: D3.js efficient DOM manipulation

### 21. Large Ontology Support
- Handles 100+ classes smoothly
- Responsive interactions maintained
- No browser freeze on operations
- Memory-efficient data structures
- Optimized edge routing algorithms

---

## Edge Routing Intelligence

### 22. Layer-Based Routing (WebVOWL)
- **Layer Priority System**:
  - Layer 0: subClassOf edges
  - Layer 1: propertyRelation edges
  - Layer 2: domain/range edges
  - Layer 3: instanceOf edges
  - Layer 4: custom edges

- **Parallel Edge Handling**:
  - Perpendicular offset calculation
  - Prevents overlapping edges
  - Maintains readability
  - Dynamic offset adjustment

### 23. Curve Control
- Straight lines in WebVOWL mode (no curves)
- Optional curves in Force-Directed mode
- Configurable curvature strength
- Adaptive based on node proximity

---

## Data Integrity

### 24. OWL Parsing
- Full OWL 2 support
- RDF/XML format parsing
- Class hierarchy extraction
- Property domain/range detection
- Individual instance recognition
- Annotation property handling

### 25. Relationship Mapping
- subClassOf relationships preserved
- Object property connections
- Data property associations
- Domain and range links
- Instance-of relationships
- Inverse property detection

---

## Accessibility Features

### 26. Visual Accessibility
- High contrast color schemes
- Clear border indicators
- Readable font sizes
- Distinct node shapes
- Color + shape redundancy

### 27. Keyboard Support
- Tab navigation through controls
- Enter to select entities
- Arrow keys for navigation (planned)
- Escape to deselect (planned)

---

## Export & Integration

### 28. Console Logging
- Legend computation details
- Node/edge counts
- Filtering operations
- Performance metrics
- Debug information

### 29. Plugin Integration
- Seamless editor integration
- File import/export support
- Real-time ontology updates
- Cross-plugin communication
- Event-driven architecture

---

## Browser Compatibility

### 30. Supported Browsers
- Chrome 90+ ✅ (Recommended)
- Firefox 88+ ✅ (Recommended)
- Safari 14+ ✅ (Minor CSS differences)
- Edge 90+ ✅ (Chromium-based)
- IE11 ❌ (Not supported)

---

## Technical Stack

### 31. Core Technologies
- **React 18**: Component-based UI
- **D3.js v7**: Graph visualization and force simulation
- **TypeScript**: Type-safe development
- **Webpack**: Module bundling
- **CSS Modules**: Scoped styling

### 32. Data Structures
- Set-based visibility tracking
- Map-based edge metadata
- Hierarchical tree structures
- Efficient graph representations

---

## Future Enhancements (Planned)

### Roadmap
- [ ] Export graph as SVG/PNG
- [ ] Custom color schemes
- [ ] Advanced SPARQL query integration
- [ ] Collaborative editing markers
- [ ] Undo/Redo for layout changes
- [ ] Minimap for large graphs
- [ ] Full keyboard navigation
- [ ] Animation speed control
- [ ] Layout presets/templates
- [ ] Enhanced touch device support

---

## Known Limitations

1. Very large ontologies (500+ entities) may impact performance
2. Deep hierarchies (10+ levels) require scrolling
3. Circular dependencies not fully supported
4. Limited undo/redo functionality
5. No graph animation recording

---

## Testing Support

### Test Files Included
- `test-ontology.owl`: Comprehensive 18-class test ontology
- `sample-ontology.owl`: Simple 5-class cardiovascular ontology
- `TEST_CASES.md`: Complete test case documentation

### Test Coverage
- 25 detailed test cases
- WebVOWL layout verification
- Hierarchy navigation tests
- Legend dynamic update tests
- Performance benchmarks
- Edge case handling

---

## Documentation

- **README.md**: Plugin overview and setup
- **TEST_CASES.md**: Comprehensive test documentation
- **FEATURES.md**: This feature list
- **DEBUG_GUIDE.md**: Troubleshooting and debugging
- Inline code documentation
- JSDoc comments for major functions

---

## Version Information

**Current Version**: 2.0.0  
**Last Updated**: December 16, 2025  
**Compatibility**: VS Code Extension API compatible  
**License**: MIT (or as specified in package.json)

---

## Support & Contact

For issues, feature requests, or questions:
- File an issue in the repository
- Contact the development team
- Review the DEBUG_GUIDE.md for troubleshooting
- Check TEST_CASES.md for usage examples
