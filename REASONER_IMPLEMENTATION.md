# Reasoner Implementation Guide

## Overview
Complete Protégé-style reasoner functionality integrated into the main navigation bar of OntoCode VSCode extension.

## Features Implemented

### 1. Main Navigation Bar Integration
- **Location**: Reasoner menu appears between "View" and "Tools" in the top menu bar
- **Visual Indicators**:
  - Green pulsing dot when reasoner is running
  - Selected reasoner name shown in parentheses when stopped
  - Green background highlight when active

### 2. Reasoner Menu Options

#### Start Reasoner
- Triggers classification with selected reasoner (HermiT, ELK, Pellet, Openllet, or Structural)
- Calls backend API: `POST /plugin-service/api/reasoner/{projectId}/classify`
- Shows loading spinner during classification
- Displays results in floating panel on success
- Disabled while reasoner is already running

#### Synchronize Reasoner
- Checkbox toggle for auto-sync mode
- When enabled, automatically re-runs reasoner 2 seconds after any ontology change
- Shows "Auto-sync ON" indicator in results panel
- Helps keep inferred hierarchy up-to-date

#### Stop Reasoner
- Stops the reasoner and clears results
- Closes the results panel
- Resets running state
- Disabled when reasoner is not running

#### Configure...
- Placeholder for future reasoner preferences dialog

### 3. Reasoner Selection
Available reasoners (matching backend):
- **HermiT 1.4.5.519** (Default, Recommended)
- **ELK 0.4.3** (Fast, incomplete reasoning)
- **Pellet** (Complete OWL 2 DL reasoner)
- **Openllet 2.6.5** (Modern Pellet fork)
- **Structural Reasoner** (Fast, structural-only)

Visual feedback:
- Selected reasoner highlighted with blue background
- Bullet point (•) prefix on active reasoner
- Notification shown when reasoner is switched

### 4. Reasoner Results Panel
Floating panel appears on the right side showing:

#### Statistics Dashboard
- **Classes**: Number of classes in hierarchy
- **Properties**: Object property count
- **Data Properties**: Data property count  
- **Individuals**: Individual count

#### Unsatisfiable Classes Warning
- Red-highlighted section
- Lists all classes that are unsatisfiable (contradictory)
- Shows count in header
- Scrollable list if many classes

#### Equivalent Classes
- Blue-highlighted section
- Shows groups of equivalent classes
- Uses ≡ symbol to separate equivalent classes
- Example: `Pizza ≡ ItalianDish ≡ CircularFood`

#### Inferred Class Hierarchy
- Tree view of inferred class relationships
- Green "inferred" badges on inferred subclass relationships
- Expandable/collapsible nodes with chevron icons
- Color-coded class names in blue

#### Explanation Tooltips
- **Hover over any class** to see explanation
- Yellow tooltip with border appears near cursor
- Shows "Why inferred:" explanation
- Example: "This class is a subclass of Pizza because it has property hasTopping with domain Pizza"

#### Status Footer
- Green pulsing indicator when running, gray when stopped
- "Auto-sync ON" badge when synchronization enabled
- Timestamp showing when results were generated

### 5. State Management
State variables in Dashboard.tsx:
```typescript
const [selectedReasoner, setSelectedReasoner] = useState<string>('HermiT');
const [isReasonerRunning, setIsReasonerRunning] = useState(false);
const [isReasonerSynced, setIsReasonerSynced] = useState(false);
const [reasonerResults, setReasonerResults] = useState<any>(null);
const [isReasonerLoading, setIsReasonerLoading] = useState(false);
const [showReasonerResults, setShowReasonerResults] = useState(false);
```

## Backend Integration

### Classification Endpoint
```
POST /plugin-service/api/reasoner/{projectId}/classify
Body: { reasonerType: "HERMIT" | "ELK" | "PELLET" | "OPENLLET" | "STRUCTURAL" }
```

### Response Structure
```json
{
  "stats": {
    "classHierarchyNodes": 42,
    "objectPropertyNodes": 15,
    "dataPropertyNodes": 8,
    "individuals": 23
  },
  "unsatisfiableClasses": ["InconsistentClass1", "InconsistentClass2"],
  "equivalentClasses": [
    ["Pizza", "ItalianDish"],
    ["MozzarellaTopping", "Mozzarella"]
  ],
  "classHierarchy": [
    {
      "name": "owl:Thing",
      "children": [
        {
          "name": "Pizza",
          "inferred": true,
          "explanation": "Inferred from hasTopping domain axiom",
          "children": [
            { "name": "MargheritaPizza" }
          ]
        }
      ]
    }
  ]
}
```

## Usage Workflow

### Basic Usage
1. **Load an ontology** (File → Open)
2. **Select a reasoner** from Reasoner menu (HermiT recommended)
3. **Click "Start reasoner"**
4. **View results** in the floating panel
5. **Hover over classes** to see inference explanations
6. **Click "Stop reasoner"** when done

### Auto-Sync Workflow
1. Load ontology and start reasoner
2. **Enable "Synchronize reasoner"** checkbox
3. Make changes to your ontology (add classes, properties, axioms)
4. Reasoner automatically re-classifies after 2 seconds
5. Results panel updates with new inferences

### Troubleshooting Inconsistencies
1. Start reasoner
2. Check **Unsatisfiable Classes** section (red)
3. If classes appear, your ontology has logical contradictions
4. Click on unsatisfiable class to see details
5. Hover for explanation of why it's unsatisfiable
6. Fix axioms and re-run reasoner

## Key Features vs Desktop Protégé

| Feature | Desktop Protégé | OntoCode |
|---------|----------------|----------|
| Reasoner Menu | ✅ Top bar | ✅ Top bar |
| Start/Stop | ✅ | ✅ |
| Auto-sync | ✅ | ✅ |
| Multiple reasoners | ✅ | ✅ |
| Explanation tooltips | ✅ | ✅ |
| Inferred hierarchy | ✅ | ✅ |
| Unsatisfiable detection | ✅ | ✅ |
| Statistics | ✅ | ✅ |
| Visual indicators | ✅ | ✅ Enhanced |

## Files Modified

### Frontend
- `ontology-vscode-extension/webview-src/components/Dashboard.tsx`
  - Lines 1143-1149: Added reasoner state variables
  - Lines 440-530: Implemented Reasoner menu with actions
  - Lines 411-428: Added visual indicators to menu button
  - Lines 2952-2975: Added auto-sync logic to markAsUnsaved
  - Lines 4353-4408: Added renderClassHierarchy helper function
  - Lines 5638-5740: Added ReasonerResults floating panel

### Backend (Previously Implemented)
- `ontology-plugin-service/src/main/java/.../controller/ReasonerController.java`
  - Enhanced classify and realize endpoints
  
- `ontology-plugin-service/src/main/java/.../service/ReasonerService.java`
  - Added getClassificationResults() method
  - Added getRealizationResults() method
  - Added buildClassHierarchy() helper

## Testing Checklist

- [x] Reasoner menu appears in navigation bar
- [x] Visual indicators show reasoner status
- [x] Can select different reasoners
- [x] Start reasoner triggers classification
- [x] Results panel displays statistics
- [x] Unsatisfiable classes highlighted
- [x] Equivalent classes shown
- [x] Inferred hierarchy displays
- [x] Explanation tooltips appear on hover
- [x] Stop reasoner clears results
- [x] Auto-sync checkbox toggles
- [x] Auto-sync re-runs on changes
- [x] Notifications show for actions

## Future Enhancements

1. **Configure Dialog**: Settings for reasoner timeout, depth limits, etc.
2. **Explain Inconsistent Ontology**: Full explanation tree for contradictions
3. **Realization**: Show inferred individual types
4. **Property Hierarchy**: Inferred object/data property relationships
5. **SWRL Rules**: Integration with SWRL reasoner
6. **Caching**: Cache reasoner results between sessions
7. **Incremental Reasoning**: Only re-classify changed portions
8. **Export Inferences**: Save inferred axioms to file

## Build Commands

```bash
# Build webview
cd ontology-vscode-extension/webview-src
npm run build

# Build extension
cd ..
npm run bundle:extension

# Reload VS Code window
Press Ctrl+R in extension development host
```

## Notes

- **Performance**: First classification may take 5-30 seconds depending on ontology size
- **Memory**: Large ontologies (>10,000 classes) may require increased heap size
- **Consistency**: Always run reasoner after major ontology changes
- **HermiT**: Most complete reasoner, recommended for OWL 2 DL
- **ELK**: Fastest reasoner, but only supports EL profile
- **Auto-sync**: Disable for large ontologies to avoid performance issues
