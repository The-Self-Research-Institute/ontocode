# Protégé-Style Features Implementation

Complete feature parity with Protégé Desktop for ontology visualization and reasoning.

---

## 🎨 Graph View Plugin (OntoGraf)

Complete feature parity with Protégé Desktop OntoGraf plugin for ontology visualization.

### Features Implemented

#### 1. **Class Hierarchy Tree (Left Sidebar)**
- ✅ Expandable/collapsible class tree
- ✅ Color-coded nodes (gold circles for classes)
- ✅ Click to focus on class in graph
- ✅ Nested hierarchy visualization
- ✅ Toggle sidebar visibility

#### 2. **Top Toolbar**
- ✅ **Assertion View Dropdown**: Switch between Asserted/Inferred/All views
- ✅ **Search Bar**: Search for classes, properties, and individuals
  - Contains-based filtering
  - Clear button
  - Search and Clear actions
- ✅ **Zoom Controls**: Zoom In, Zoom Out, Fit to Screen
- ✅ **Export**: Download graph as PNG
- ✅ **Refresh**: Reload ontology data
- ✅ **Toggle Class Tree**: Show/hide left sidebar

#### 3. **Icon Toolbar (Below Search)**
- ✅ **Selection Tools**: Select, Pan
- ✅ **Edit Tools**: Add Node, Add Edge, Remove
- ✅ **View Controls**: Zoom In, Zoom Out, Fit to Window
- ✅ **Display Options**: Layout, Filter, Settings
- ✅ **Export Options**: Copy, Save View, Export Image, Export Data

#### 4. **Graph Visualization**
- ✅ **Node Types**:
  - Classes: Gold boxes
  - Individuals: Light green ellipses
  - Object Properties: Sky blue diamonds
  - Data Properties: Plum stars
  - Annotation Properties: Khaki triangles

- ✅ **Edge Types**:
  - subClassOf: Solid black arrows
  - type/instanceOf: Dashed gray arrows
  - property: Solid blue arrows
  - equivalentClass: Dotted red lines
  - disjointWith: Long-dashed red lines

- ✅ **Selection Highlighting**: Green borders (#00FF00) like Protégé

#### 5. **Layout Algorithms**
- ✅ Hierarchical (top-down, directed)
- ✅ Force-Directed (physics-based)
- ✅ Circular
- ✅ Radial

#### 6. **Interactive Features**
- ✅ **Node Selection**: Click to select, see details with green border
- ✅ **Hover Tooltips**: Rich information on hover
  - Node label and URI
  - Superclasses list
  - Annotations (rdfs:label, etc.)
- ✅ **Context Menu** (right-click):
  - Add to Graph
  - Show Subclasses
  - Show Superclasses
  - Show Individuals
  - Remove from Graph
  - Hide Related
  - Properties...

#### 7. **Node Information Tooltip** (Yellow Protégé-style)
Displays:
- **Class Name**: HonorsStudent
- **URL**: Full IRI
- **Superclasses**: List of parent classes
- **Annotations**: rdfs:label values and other annotations

#### 8. **Reasoner Integration Notice**
- ✅ Bottom banner when no reasoner is active
- ✅ "Show Inferences" button
- ✅ Icon indicator (package icon)

#### 9. **Graph Manipulation**
- ✅ Drag nodes
- ✅ Pan canvas
- ✅ Zoom with mouse wheel
- ✅ Multi-select (Shift+Click)
- ✅ Focus on node (from class tree)

#### 10. **Visual Styling (Protégé-accurate)**
- ✅ Clean white background
- ✅ Gray toolbar with subtle shadows
- ✅ Yellow tooltip background (#FFFACD)
- ✅ Black borders on nodes
- ✅ Green selection borders (#00FF00)
- ✅ Smooth animations

---

## 🧠 Reasoner Plugin

Complete Protégé-style reasoning interface with explanations and interactive features.

### Features Implemented

#### 1. **Reasoner Selection & Controls**
- ✅ **Reasoner Dropdown**: Select from multiple reasoners
  - HermiT (Hypertableau-based, full OWL 2)
  - ELK (Fast, OWL 2 EL profile)
  - Openllet (Pellet fork)
  - Pellet (Classic reasoner)
  - FaCT++ (Optimized tableau)
  - Structural (Fast, no inference)

- ✅ **Control Buttons**:
  - **Start Reasoner**: Check consistency
  - **Classify**: Compute class hierarchy
  - **Realize**: Compute individual types
  - **Stop**: Halt reasoning

#### 2. **Status Indicators**
- ✅ **Color-Coded Status Bar**:
  - 🟢 Green: "Ontology is consistent"
  - 🔴 Red: "Ontology is inconsistent"
  - ⚪ Gray: "Not initialized"
- ✅ Real-time loading spinner during reasoning
- ✅ Status messages (e.g., "Running classification...")

#### 3. **Statistics Dashboard**
- ✅ **Ontology Metrics**:
  - Classes count
  - Individuals count
  - Properties count (object + data)
  - Satisfiable classes (green)
  - Unsatisfiable classes (red)

#### 4. **Explanation Tooltips** ⭐ (Key Protégé Feature)
**Hover over any class to see reasoning explanations:**

- ✅ **Dark tooltip with light bulb icon**
- ✅ **Explanation Types**:
  - 🔴 **Unsatisfiable**: "This class is unsatisfiable (equivalent to owl:Nothing). No individuals can be instances of this class."
  - 🟠 **Equivalent To**: "This class is equivalent to: [ClassX, ClassY]. They have exactly the same instances."
  - 🔵 **SubClass Of**: "This class is inferred to be a subclass based on its axioms and restrictions. Depth in hierarchy: N"
- ✅ **Related Classes**: Shows related entities
- ✅ **Multiple Reasons**: Can display multiple explanations per class

#### 5. **Unsatisfiable Classes Warning Panel**
- ✅ **Prominent Yellow Warning Box**:
  - Alert triangle icon
  - Count: "N Unsatisfiable Classes"
  - Description: "These classes are equivalent to owl:Nothing"
- ✅ **Class List**:
  - Red X icons
  - Class labels
  - Hover for detailed explanations

#### 6. **Equivalent Classes Section**
- ✅ **Orange/Yellow Highlighted Groups**
- ✅ **Visual Arrows** (→) connecting equivalent classes
- ✅ **Hover Tooltips**: Explanation of equivalence
- ✅ Shows all classes in each equivalence group

#### 7. **Inferred Class Hierarchy Tree**
- ✅ **Expandable/Collapsible Tree View**
- ✅ **Color-Coded Node Indicators**:
  - 🔵 Blue circle: Normal satisfiable class
  - 🟠 Orange circle: Part of equivalent class group
  - 🔴 Red circle: Unsatisfiable class
- ✅ **Red Left Border**: Highlights unsatisfiable classes
- ✅ **Help Icon (?)**: Indicates explanation available
- ✅ **Hierarchy Depth**: Shows nesting level
- ✅ **Children Count**: Displays number of subclasses
- ✅ **Hover for Explanations**: Full reasoning details on hover

#### 8. **Settings Panel**
- ✅ **Auto-Sync**: "Synchronize reasoner (apply changes automatically)"
- ✅ **Explanation Depth**: Full / Medium / Minimal
- ✅ Toggle visibility with settings icon

#### 9. **Empty State**
- ✅ **Helpful Instructions**: 
  - Light bulb icon
  - "No reasoning results yet"
  - "Click 'Start reasoner' or 'Classify' to compute..."
- ✅ Clear call-to-action

#### 10. **Backend Integration**
- ✅ **RESTful API Endpoints**:
  - POST `/api/ontology/{projectId}/reasoner/consistency`
  - POST `/api/ontology/{projectId}/reasoner/classify`
  - POST `/api/ontology/{projectId}/reasoner/realize`
  - GET `/api/ontology/{projectId}/reasoner/stats`
  - GET `/api/ontology/{projectId}/reasoner/inferred-axioms`
- ✅ **Reasoner Service** (Java/OWL API):
  - HermiT, ELK, Openllet, Pellet, FaCT++ support
  - Classification results with class hierarchy
  - Equivalence detection
  - Unsatisfiability detection
  - Explanation generation

#### 11. **Visual Design (Protégé-Accurate)**
- ✅ Clean professional layout
- ✅ Gray header with purple accent
- ✅ White content area
- ✅ Subtle shadows and borders
- ✅ Consistent icon usage (Lucide React)
- ✅ Responsive design

---

## 📋 Usage

### Graph View Plugin
```tsx
import { GraphView } from '@ontocode/graph-view-plugin';

<GraphView projectId="my-ontology-id" />
```

### Reasoner Plugin
```tsx
import { ReasonerPlugin } from '@ontocode/reasoner-plugin';

<ReasonerPlugin 
  projectId="my-ontology-id"
  apiBaseUrl="http://localhost:8080/api"
/>
```

---

## 🎯 Protégé Desktop Parity

### Graph View (OntoGraf)
- ✅ Class hierarchy sidebar
- ✅ Assertion view dropdown
- ✅ Search functionality
- ✅ Complete icon toolbar
- ✅ Yellow tooltips
- ✅ Green selection borders
- ✅ Context menus
- ✅ Multiple layout algorithms
- ✅ Export capabilities

### Reasoner Tab
- ✅ Reasoner selection dropdown
- ✅ Start/Classify/Realize/Stop buttons
- ✅ Status bar with color coding
- ✅ **Explanation tooltips on hover** ⭐
- ✅ Statistics dashboard
- ✅ Unsatisfiable classes warning
- ✅ Equivalent classes display
- ✅ Inferred class hierarchy tree
- ✅ Settings panel
- ✅ Empty state guidance

---

## 🚀 Key Differentiators

### What Makes This Implementation Special:

1. **Explanation Tooltips**: Just like desktop Protégé, hovering over any class shows WHY the reasoner made that inference
2. **Visual Feedback**: Color-coded nodes and borders make it easy to spot issues
3. **Professional UI**: Matches the look and feel of Protégé 5.x
4. **Full Reasoner Support**: 6 different reasoners including HermiT and ELK
5. **Real-time Status**: Live updates during reasoning operations
6. **Interactive Hierarchy**: Expandable tree with hover explanations
7. **Complete Integration**: Seamlessly works with backend OWL API reasoners

---

## 📦 Dependencies

- React 18.2.0
- Lucide React (icons)
- vis-network (graph visualization)
- OWL API 5.5.0 (backend)
- HermiT 1.4.5.519, ELK 0.4.3, Openllet 2.6.5 (reasoners)

---

## 🎓 Based on Protégé Wiki

Implementation follows official Protégé documentation:
- https://protegewiki.stanford.edu/wiki/Category:Reasoner
- https://protegewiki.stanford.edu/wiki/HermiT
- https://protegewiki.stanford.edu/wiki/ELK

---

The graph view will:
1. Load ontology data from backend API
2. Build class hierarchy tree
3. Render graph with Protégé styling
4. Enable all interactive features

## API Endpoints Used

- `GET /api/ontology/{projectId}/graph` - Fetch nodes and edges
- Returns: `{ nodes: [], edges: [] }`

## Keyboard Shortcuts

- **Ctrl+F**: Focus search bar
- **Ctrl+Z**: Zoom out
- **Ctrl++**: Zoom in
- **Ctrl+0**: Fit to screen
- **Esc**: Clear selection

## Comparison with Protégé Desktop

| Feature | Protégé Desktop | OntoCode Web |
|---------|----------------|--------------|
| Class hierarchy tree | ✅ | ✅ |
| Assertion view selector | ✅ | ✅ |
| Search functionality | ✅ | ✅ |
| Node tooltips | ✅ | ✅ |
| Context menus | ✅ | ✅ |
| Multiple layouts | ✅ | ✅ |
| Export to PNG | ✅ | ✅ |
| Reasoner integration | ✅ | ✅ |
| Drag & drop | ✅ | ✅ |
| Color-coded nodes | ✅ | ✅ |
| Edge styling | ✅ | ✅ |
| Zoom controls | ✅ | ✅ |
| **Collaborative editing** | ❌ | ✅ (Advanced) |
| **Real-time sync** | ❌ | ✅ (Advanced) |
| **AI suggestions** | ❌ | ✅ (Advanced) |

## Technical Details

### Libraries Used
- **vis-network**: Graph rendering engine
- **React**: UI framework
- **lucide-react**: Icon library

### Performance Optimizations
- Lazy loading of class hierarchy
- Efficient node filtering
- Canvas-based rendering (hardware accelerated)
- Edge hiding during drag/zoom
- Caching of network instance

### Browser Compatibility
- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+

## Screenshots

See the attached screenshot for reference of the Protégé OntoGraf interface that has been replicated.

## Future Enhancements

Potential additions beyond Protégé:
- [ ] Save/load graph layouts
- [ ] Custom color schemes
- [ ] Graph metrics dashboard
- [ ] Export to other formats (SVG, GraphML, etc.)
- [ ] Undo/Redo support
- [ ] Graph comparison view
- [ ] Animation of reasoning steps
- [ ] Integration with SPARQL queries

## License

Part of OntoCode - Enterprise Ontology Editor
