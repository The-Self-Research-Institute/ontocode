# Graph View Plugin - Test Examples (Cardiovascular Ontology)

This document provides comprehensive visualization test cases for the cardiovascular measurement ontology using the Graph View Plugin.

## Table of Contents
1. [Basic Visualization](#1-basic-visualization)
2. [Class Hierarchy Views](#2-class-hierarchy-views)
3. [Individual Relationship Views](#3-individual-relationship-views)
4. [Property Graphs](#4-property-graphs)
5. [VOWL Notation Views](#5-vowl-notation-views)
6. [Filter and Search](#6-filter-and-search)
7. [Layout Options](#7-layout-options)
8. [Interactive Features](#8-interactive-features)
9. [Export and Sharing](#9-export-and-sharing)
10. [Testing Checklist](#10-testing-checklist)

---

## 1. Basic Visualization

### Visualization 1.1: Full Ontology Overview

**View Configuration**:
- **Layout**: Hierarchical (Top-Down)
- **Node Types**: All (Classes, Individuals, Properties)
- **Depth**: Unlimited
- **Theme**: Auto (Light/Dark)

**Expected Nodes**:
- 10 classes (Measurement hierarchy, Patient, Device, Environment, RiskLevel)
- 20+ individuals
- 7 properties (4 object, 3 datatype)

**Expected Structure**:
```
Measurement (root)
├── Circulatory
    └── Cardiovascular
        ├── BloodPressure
        ├── HeartRate
        ├── Pulse
        ├── WalkingHeartRate
        └── HeartRateVariability
```

### Visualization 1.2: Compact View

**View Configuration**:
- **Layout**: Force-Directed
- **Node Types**: Classes only
- **Show Labels**: Yes
- **Color Scheme**: VOWL Notation

**Expected Result**: Clean graph showing class structure without individuals

### Visualization 1.3: Detailed View with Annotations

**View Configuration**:
- **Layout**: Radial
- **Show Annotations**: Yes
- **Node Size**: Based on property count
- **Edge Labels**: Show property names

**Expected Result**: Full graph with annotation properties visible (loincCode, measurementUnit, etc.)

---

## 2. Class Hierarchy Views

### View 2.1: Measurement Class Hierarchy

**Focus**: Measurement class and all subclasses

**Filter**:
```javascript
{
  "rootClass": "Measurement",
  "depth": 4,
  "includeSubclasses": true,
  "excludeIndividuals": true
}
```

**Expected Nodes**:
1. Measurement (Internal Class - light blue)
2. Circulatory (Internal Class - light blue)
3. Cardiovascular (Internal Class - light blue)
4. BloodPressure (Internal Class - light blue)
5. HeartRate (Internal Class - light blue)
6. Pulse (Internal Class - light blue)
7. WalkingHeartRate (Internal Class - light blue)
8. HeartRateVariability (Internal Class - light blue)

**Expected Edges**:
- 7 subClassOf relationships (solid lines)

**VOWL Colors**:
- All internal classes: Light blue (#acd5f2 light / #6b92c4 dark)
- SubClassOf edges: Dark gray (#374151 light / #9ca3af dark)

### View 2.2: Patient-Device-Environment Triangle

**Focus**: Core domain classes

**Filter**:
```javascript
{
  "classes": ["Patient", "Device", "Environment", "RiskLevel"],
  "showRelatedProperties": true
}
```

**Expected Nodes**:
- Patient (Internal Class)
- Device (Internal Class)
- Environment (Internal Class)
- RiskLevel (Internal Class)

**Expected Properties**:
- hasMeasurement (Patient → Measurement)
- recordedBy (Measurement → Device)
- measuredAt (Measurement → Environment)
- hasRiskLevel (Patient → RiskLevel)

---

## 3. Individual Relationship Views

### View 3.1: Patient_Healthy_Adult Complete Graph

**Focus**: Single patient with all relationships

**Filter**:
```javascript
{
  "individual": "Patient_Healthy_Adult",
  "includeRelated": true,
  "depth": 2
}
```

**Expected Nodes**:
- Patient_Healthy_Adult (Individual - lavender #dcd5f7 light / pink #fbb6ce dark)
- BP_Reading_Healthy_001 (Individual)
- HR_Reading_Healthy_001 (Individual)
- HRV_Reading_Healthy_001 (Individual)
- AppleWatch_Series8 (Device individual)
- HomeEnvironment (Environment individual)
- LowRisk (RiskLevel individual)

**Expected Edges**:
- hasMeasurement (Patient → 3 measurements)
- recordedBy (measurements → AppleWatch)
- measuredAt (measurements → HomeEnvironment)
- hasRiskLevel (Patient → LowRisk)

**Node Labels**:
- Show individual names and key values
- Badge below node showing type (e.g., "Patient", "Blood Pressure")

### View 3.2: All Patients Comparison

**Focus**: Compare 3 patients side-by-side

**Filter**:
```javascript
{
  "individuals": [
    "Patient_Healthy_Adult",
    "Patient_PreHypertensive",
    "Patient_Hypertensive"
  ],
  "groupBy": "hasRiskLevel"
}
```

**Expected Layout**:
- Three patient clusters
- Color-coded by risk level
- Measurements grouped around each patient

**Visual Grouping**:
- LowRisk group (green tint): Patient_Healthy_Adult
- ModerateRisk group (yellow tint): Patient_PreHypertensive
- HighRisk group (red tint): Patient_Hypertensive

### View 3.3: Measurement Network

**Focus**: All measurement individuals and their relationships

**Filter**:
```javascript
{
  "type": "BloodPressure OR HeartRate OR HeartRateVariability",
  "showConnections": ["recordedBy", "measuredAt"]
}
```

**Expected Result**: Network showing which devices recorded which measurements in which environments

---

## 4. Property Graphs

### View 4.1: Object Property Graph

**Focus**: All object properties and their domains/ranges

**Configuration**:
```javascript
{
  "propertyType": "ObjectProperty",
  "showDomainRange": true,
  "layout": "Hierarchical"
}
```

**Expected Properties**:
1. hasMeasurement
   - Domain: Patient
   - Range: Measurement
   - Color: Cyan (#0891b2 light / #22d3ee dark)

2. recordedBy
   - Domain: Measurement
   - Range: Device
   - Color: Cyan

3. measuredAt
   - Domain: Measurement
   - Range: Environment
   - Color: Cyan

4. hasRiskLevel
   - Domain: Patient
   - Range: RiskLevel
   - Color: Cyan

**Visual Style**:
- Solid arrows from domain to range
- Property name labels on edges
- Thicker edges for frequently used properties

### View 4.2: Datatype Property Graph

**Focus**: All datatype properties

**Configuration**:
```javascript
{
  "propertyType": "DatatypeProperty",
  "showDataTypes": true
}
```

**Expected Properties**:
1. hasValue
   - Domain: Measurement
   - Range: xsd:double
   - Color: Pink (#db2777 light / #f472b6 dark)

2. hasClassification
   - Domain: Measurement
   - Range: xsd:string
   - Color: Pink

3. hasReadingCount
   - Domain: Measurement
   - Range: xsd:integer
   - Color: Pink

**Visual Style**:
- Dashed edges for datatype properties
- Datatype nodes shown as rectangles (peach #FFD9B3 light / amber #d97706 dark)

### View 4.3: Annotation Property Cloud

**Focus**: Annotation properties on BloodPressure class

**Target**: BloodPressure class

**Expected Annotations** (40+ properties):
- loincCode: "8480-6; 8462-4"
- measurementUnit: "Millimeters of Mercury (mmHg)"
- measurementEnvironment: "Clinical / Home"
- device: "Wearable BP monitor; Clinical BP monitor"
- referenceSource: "https://www.heart.org/..."
- evidenceGrade: "V1"
- ageBand: "Adult (18–65 years)"
- etc.

**Visual Style**:
- Central BloodPressure node
- Annotation properties radiating outward
- Purple edges (#7c3aed light / #a78bfa dark)
- Annotations grouped by category

---

## 5. VOWL Notation Views

### View 5.1: Standard VOWL Visualization

**Configuration**:
- **Notation**: VOWL (Visual Notation for OWL Ontologies)
- **Theme**: Light mode
- **Node Sizing**: Dynamic based on label length
- **Badge Position**: Below nodes

**VOWL Color Scheme (Light Mode)**:
- **Thing**: White (#ffffff)
- **External Class**: Steel blue (#4682b4)
- **Internal Class**: Light blue (#acd5f2)
- **Datatype**: Peach (#FFD9B3)
- **Individual**: Light lavender (#dcd5f7)
- **SubClassOf Edge**: Dark gray (#374151)
- **Object Property**: Cyan (#0891b2)
- **Data Property**: Pink (#db2777)
- **Annotation Property**: Purple (#7c3aed)

### View 5.2: VOWL Dark Theme

**Configuration**:
- **Notation**: VOWL
- **Theme**: Dark mode
- **High Contrast**: Enabled

**VOWL Color Scheme (Dark Mode)**:
- **Thing**: Dark gray (#374151)
- **External Class**: Light blue (#60a5fa)
- **Internal Class**: Medium blue (#6b92c4)
- **Datatype**: Amber (#d97706)
- **Individual**: Light pink (#fbb6ce)
- **SubClassOf Edge**: Light gray (#9ca3af)
- **Object Property**: Cyan (#22d3ee)
- **Data Property**: Pink (#f472b6)
- **Annotation Property**: Purple (#a78bfa)

### View 5.3: VOWL Legend Sidebar

**Components**:
- Node Types section showing all colors
- Property Types section showing edge styles
- Quick reference for symbols
- Theme toggle button

**Expected Display**:
```
┌─ VOWL Notation ─────────────┐
│ Node Types:                  │
│ ○ Thing          (white)     │
│ ○ External Class (blue)      │
│ ○ Internal Class (lt blue)   │
│ □ Datatype       (peach)     │
│ ◇ Individual     (lavender)  │
│                              │
│ Edge Types:                  │
│ ─── SubClassOf   (gray)      │
│ ──▷ Object Prop  (cyan)      │
│ ─ ─▷ Data Prop   (pink)      │
│ ····▷ Annotation (purple)    │
└──────────────────────────────┘
```

---

## 6. Filter and Search

### Filter 6.1: Show Only High-Risk Patient Data

**Filter Expression**:
```javascript
{
  "individuals": {
    "hasRiskLevel": "HighRisk"
  },
  "includeRelated": true
}
```

**Expected Result**: Patient_Hypertensive with all measurements and devices

### Filter 6.2: Blood Pressure > 140

**Filter Expression**:
```javascript
{
  "type": "BloodPressure",
  "property": "hasValue",
  "operator": ">",
  "value": 140
}
```

**Expected Nodes**:
- BP_Reading_PreHT_001 (135.0 - if >= 140)
- BP_Reading_HT_001 (155.0)

### Filter 6.3: Wearable Device Measurements

**Filter Expression**:
```javascript
{
  "path": "recordedBy.device",
  "contains": "Wearable"
}
```

**Expected Result**: All measurements from AppleWatch and Garmin devices

### Filter 6.4: Search by Label

**Search Query**: "Heart"

**Expected Results**:
- HeartRate class
- WalkingHeartRate class
- HeartRateVariability class
- All HR measurement individuals

---

## 7. Layout Options

### Layout 7.1: Hierarchical (Top-Down)

**Best For**: Class hierarchies, taxonomies

**Configuration**:
```javascript
{
  "layout": "hierarchical",
  "direction": "TB",  // Top to Bottom
  "spacing": 100,
  "nodeSpacing": 80
}
```

**Use Case**: Visualize Measurement class hierarchy

### Layout 7.2: Force-Directed (Spring)

**Best For**: General network visualization

**Configuration**:
```javascript
{
  "layout": "force",
  "linkDistance": 150,
  "linkStrength": 0.5,
  "chargeStrength": -300
}
```

**Use Case**: Explore patient-measurement-device relationships

### Layout 7.3: Radial (Circular)

**Best For**: Showing relationships from central node

**Configuration**:
```javascript
{
  "layout": "radial",
  "center": "Patient_Healthy_Adult",
  "radius": 200
}
```

**Use Case**: Visualize all data for one patient

### Layout 7.4: Tree (Organizational)

**Best For**: Strictly hierarchical data

**Configuration**:
```javascript
{
  "layout": "tree",
  "orientation": "vertical",
  "levelSeparation": 100
}
```

**Use Case**: Show class inheritance tree

### Layout 7.5: Grid Layout

**Best For**: Comparing similar entities

**Configuration**:
```javascript
{
  "layout": "grid",
  "columns": 3,
  "rowHeight": 150,
  "cellPadding": 20
}
```

**Use Case**: Display all 3 patients in grid for comparison

---

## 8. Interactive Features

### Feature 8.1: Node Expansion/Collapse

**Interaction**: Click on Patient node

**Expected Behavior**:
- First click: Expand to show all hasMeasurement relationships
- Second click: Collapse to hide measurements
- Badge updates: "+" changes to "−"

**Test Nodes**:
- Patient_Healthy_Adult (3 measurements)
- Patient_Hypertensive (3 measurements)

### Feature 8.2: Node Details Panel

**Interaction**: Click on BloodPressure class node

**Expected Panel Content**:
```
┌─ BloodPressure ──────────────────────────┐
│ Type: Class                               │
│ SubClass of: Cardiovascular               │
│ Instances: 3                              │
│                                           │
│ Properties:                               │
│ • hasValue (xsd:double)                   │
│ • hasClassification (xsd:string)          │
│ • hasReadingCount (xsd:integer)           │
│                                           │
│ Annotations:                              │
│ • loincCode: "8480-6; 8462-4"            │
│ • measurementUnit: "mmHg"                 │
│ • refLow: "90/60"                         │
│ • refHigh: "120/80"                       │
│ • source: "AHA"                           │
│                                           │
│ [View All Annotations] [Close]            │
└───────────────────────────────────────────┘
```

### Feature 8.3: Edge Hover Tooltip

**Interaction**: Hover over "hasMeasurement" edge

**Expected Tooltip**:
```
hasMeasurement
Object Property
Domain: Patient
Range: Measurement
Instances: 9
```

### Feature 8.4: Multi-Select Nodes

**Interaction**: Ctrl+Click multiple nodes

**Actions Available**:
- Export selected nodes
- Create group
- Apply bulk styling
- Hide/show selected

**Test Selection**:
- Select all 3 BloodPressure measurement individuals
- Apply red highlight
- Export as separate graph

### Feature 8.5: Path Highlighting

**Interaction**: Click source node, Ctrl+Click target node

**Test Path**: Patient_Healthy_Adult → BP_Reading_Healthy_001 → AppleWatch_Series8

**Expected Result**:
- Entire path highlighted in bright color
- Intermediate edges emphasized
- Other nodes dimmed (opacity 30%)

---

## 9. Export and Sharing

### Export 9.1: PNG Image Export

**Configuration**:
- **Resolution**: 2400x1800 (high quality)
- **Background**: Transparent
- **Include Legend**: Yes

**Test Cases**:
- Export full ontology overview
- Export patient comparison view
- Export class hierarchy only

### Export 9.2: SVG Vector Export

**Configuration**:
- **Format**: SVG
- **Preserve Styling**: Yes
- **Embed Fonts**: Yes

**Use Case**: Include in research papers, presentations

### Export 9.3: Graph Data Export (JSON)

**Configuration**:
- **Format**: JSON-LD
- **Include**: Nodes, Edges, Metadata
- **Layout**: Save current layout positions

**Example Output**:
```json
{
  "nodes": [
    {
      "id": "Patient_Healthy_Adult",
      "type": "Individual",
      "label": "Patient 1: Healthy Adult",
      "x": 250,
      "y": 100,
      "color": "#dcd5f7"
    },
    ...
  ],
  "edges": [
    {
      "source": "Patient_Healthy_Adult",
      "target": "BP_Reading_Healthy_001",
      "type": "hasMeasurement",
      "label": "has measurement"
    },
    ...
  ]
}
```

### Export 9.4: Share View Link

**Feature**: Generate shareable link with view configuration

**Link Format**: `app://graph-view?config=base64encodedConfig`

**Includes**:
- Filter settings
- Layout choice
- Node positions
- Theme preference
- Zoom level

---

## 10. Testing Checklist

### Basic Functionality
- [ ] Load cardiovascular ontology successfully
- [ ] Display all classes (10 classes)
- [ ] Display all individuals (20+ individuals)
- [ ] Display all properties (7 properties)
- [ ] Render without errors or crashes

### Class Visualization
- [ ] Measurement hierarchy displays correctly (4 levels)
- [ ] Patient, Device, Environment, RiskLevel classes visible
- [ ] SubClassOf relationships shown as solid edges
- [ ] Class labels readable in all zoom levels

### Individual Visualization
- [ ] All 3 patients visible
- [ ] All 9+ measurements visible
- [ ] All 4 devices visible
- [ ] Individual-to-class type relationships shown

### Property Visualization
- [ ] Object properties (cyan color, solid arrows)
- [ ] Datatype properties (pink color, dashed arrows)
- [ ] Annotation properties (purple color, dotted arrows)
- [ ] Property labels displayed correctly

### VOWL Notation
- [ ] Light mode colors correct (Thing white, Internal blue, Individual lavender)
- [ ] Dark mode colors correct (Thing gray, Internal blue, Individual pink)
- [ ] Node type badges below nodes (not covering labels)
- [ ] Individual node width dynamic based on label length
- [ ] Legend sidebar accurate and complete

### Filtering
- [ ] Filter by risk level works
- [ ] Filter by value threshold works
- [ ] Filter by device type works
- [ ] Search by label works
- [ ] Clear filters restores full view

### Layout Options
- [ ] Hierarchical layout works
- [ ] Force-directed layout works
- [ ] Radial layout works
- [ ] Tree layout works
- [ ] Grid layout works
- [ ] Layout transitions smooth (< 1 second)

### Interactions
- [ ] Node click shows details panel
- [ ] Node expand/collapse works
- [ ] Edge hover shows tooltip
- [ ] Multi-select nodes (Ctrl+Click)
- [ ] Path highlighting works
- [ ] Zoom in/out smooth
- [ ] Pan (drag canvas)

### Performance
- [ ] Initial load < 2 seconds
- [ ] Layout calculation < 1 second
- [ ] Smooth interactions (60 FPS)
- [ ] No lag with 50+ nodes
- [ ] Memory usage reasonable (< 300 MB)

### Export Features
- [ ] PNG export works (correct resolution)
- [ ] SVG export works (scalable)
- [ ] JSON export contains all data
- [ ] Share link generates correctly
- [ ] Exported files readable

### Theme Support
- [ ] Light theme renders correctly
- [ ] Dark theme renders correctly
- [ ] Theme toggle works instantly
- [ ] All colors visible in both themes
- [ ] Legend updates with theme

### Accessibility
- [ ] Node labels readable (font size ≥ 12px)
- [ ] Sufficient color contrast (WCAG AA)
- [ ] Keyboard navigation supported
- [ ] Screen reader compatible
- [ ] Zoom up to 300% without loss

### Error Handling
- [ ] Handle missing ontology gracefully
- [ ] Handle malformed OWL gracefully
- [ ] Display error messages clearly
- [ ] Recover from layout errors
- [ ] Warn on performance issues

---

## Appendix A: Visualization Best Practices

### For Class Hierarchies
1. Use hierarchical layout (top-down)
2. Hide individuals initially
3. Use VOWL notation colors
4. Show subClassOf edges prominently

### For Individual Networks
1. Use force-directed layout
2. Group by class type
3. Color-code by property values
4. Show edge labels

### For Comparison Views
1. Use grid or side-by-side layout
2. Align similar nodes
3. Highlight differences
4. Use consistent coloring

### For Presentations
1. Export as SVG (scalable)
2. Use high contrast colors
3. Increase font sizes (18px+)
4. Simplify by hiding less important nodes

---

## Appendix B: Common Visualization Scenarios

### Scenario 1: Medical Presentation
**Goal**: Show cardiovascular risk factors

**View**:
- Focus on 3 patients
- Highlight BP and HR measurements
- Color-code by risk level
- Include legend

**Layout**: Grid (3 columns)

### Scenario 2: Research Paper
**Goal**: Illustrate ontology structure

**View**:
- Show class hierarchy only
- VOWL notation
- Hide individuals
- Include annotations on key classes

**Layout**: Hierarchical

### Scenario 3: Developer Documentation
**Goal**: Explain property relationships

**View**:
- Focus on properties
- Show domain/range
- Include all property types
- Display cardinality

**Layout**: Bipartite (classes on left, properties on right)

### Scenario 4: Dashboard
**Goal**: Monitor patient data

**View**:
- Live data (individuals)
- Filter by recent timestamps
- Alert colors for concerning values
- Compact, information-dense

**Layout**: Radial (patient at center)

---

## Appendix C: Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| **Ctrl + F** | Search/Filter |
| **Ctrl + +** | Zoom In |
| **Ctrl + -** | Zoom Out |
| **Ctrl + 0** | Reset Zoom |
| **Ctrl + A** | Select All |
| **Ctrl + Click** | Multi-Select |
| **Ctrl + E** | Export |
| **Ctrl + L** | Toggle Legend |
| **Ctrl + T** | Toggle Theme |
| **Space** | Recalculate Layout |
| **Esc** | Deselect All |
| **Delete** | Hide Selected |
| **F** | Fit to Screen |

---

**Test Document Version**: 1.0
**Last Updated**: December 30, 2025
**Compatible With**: Cardiovascular Ontology v1.0
