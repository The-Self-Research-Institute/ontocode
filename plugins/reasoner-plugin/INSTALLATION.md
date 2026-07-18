# OWL Reasoner Plugin (Archived)

> ⚠️ **Deprecated** – The standalone OWL Reasoner plugin has been retired now that OntoCode ships with a native Reasoner Console inside the dashboard. Keep this document only for historical reference; new deployments should use the built-in experience.

## Use the Built-in Reasoner
1. Open the OntoCode dashboard and switch to the **Reasoner** tab.
2. Pick the desired reasoner (HermiT, ELK, Pellet, Openllet, Structural) from the dropdown.
3. Click **Classify** to run reasoning, review unsatisfiable classes/equivalent classes, and refresh as ontology edits occur.
4. Toggle **Auto-sync** if you want the reasoner to re-run automatically after changes.

All classic plugin features—consistency checks, hierarchy visualization, statistics, and exports—are available there without installing anything.

---

## Historical Installation Summary

## ✅ Plugin Successfully Created!

The OWL Reasoner Plugin has been successfully created, built, and deployed to your OntoCode system.

### 📦 Plugin Details
- **Name**: OWL Reasoner
- **Plugin ID**: `reasoner-plugin`
- **Version**: 1.0.0
- **Category**: Reasoning
- **Status**: Active, Verified, Featured

### 🎯 Key Features
1. **Consistency Checking** - Verify if the ontology is logically consistent
2. **Classification** - Compute class hierarchy and identify equivalent/unsatisfiable classes
3. **Realization** - Determine the most specific types for all individuals
4. **Multiple Reasoners** - Support for HermiT, Pellet, FaCT++, and ELK
5. **Incremental Reasoning** - Efficient handling of ontology changes
6. **Inferred Axioms** - View all axioms inferred by the reasoner
7. **Statistics** - Detailed metrics about the reasoning process
8. **Export Results** - Save reasoning results as JSON

### 🔧 Reasoner Types Available
- **HermiT** (default) - Hypertableau-based reasoner, inspired by University of Oxford's HermiT
- **Pellet** - Complete and incremental reasoner
- **FaCT++** - Fast classifier for expressive ontologies
- **ELK** - Efficient reasoner for EL++ ontologies

### 📋 Reasoning Tasks
1. **Consistency** - Check for logical contradictions in the ontology
2. **Classification** - Build the complete class hierarchy with subsumption relationships
3. **Realization** - Compute the most specific types (direct types) for individuals
4. **Satisfiability** - Check if a class can have instances
5. **Entailment** - Verify if an axiom is logically entailed by the ontology
6. **Explanation** - Generate justifications for entailments

### 📊 Results and Analytics
- Real-time progress tracking
- Detailed timing information
- Error and warning messages
- Inferred axioms with confidence scores
- Statistical summaries:
  - Total classes, individuals, properties
  - Satisfiable/unsatisfiable classes
  - Number of inferred axioms
  - Reasoning duration

### 🚀 How to Use

#### 1. Install the Plugin
- Open VS Code extension
- Navigate to Plugin Marketplace
- Search for "OWL Reasoner"
- Click Install

#### 2. Open the Plugin
- Open an ontology project
- Click on the Reasoner icon in the sidebar
- Configure reasoner settings

#### 3. Configure Reasoner
- Select reasoner type (HermiT, Pellet, FaCT++, or ELK)
- Set timeout (5-300 seconds)
- Enable/disable incremental reasoning
- Enable/disable result caching

#### 4. Run Reasoning Tasks
- Click "Check Consistency" to verify ontology consistency
- Click "Classify" to compute the class hierarchy
- Click "Realize Instances" to compute individual types
- View results in real-time

#### 5. View and Export Results
- Expand results section to view details
- View inferred axioms and statistics
- Click download button to export results as JSON

### 🔌 Backend Integration Required

The plugin requires a backend reasoner service. You'll need to implement the following API endpoints:

```
POST /api/reasoner/consistency
POST /api/reasoner/classification
POST /api/reasoner/realization
POST /api/reasoner/satisfiability
POST /api/reasoner/entailment
POST /api/reasoner/stop
```

### 📚 Documentation
Full documentation is available in:
- `plugins/reasoner-plugin/README.md` - Detailed usage guide
- `plugins/reasoner-plugin/src/types.ts` - TypeScript type definitions

### 🎨 UI Features
- Modern, clean interface
- Expandable/collapsible sections
- Real-time status updates
- Progress tracking for long-running tasks
- Color-coded results (green for success, red for errors)
- Responsive design

### 📦 Files Created
```
plugins/reasoner-plugin/
├── dist/
│   └── index.js (16.3 KB - minified)
├── src/
│   ├── index.ts
│   ├── types.ts
│   └── ReasonerPlugin.tsx
├── package.json
├── tsconfig.json
├── webpack.config.js
└── README.md
```

### 🗄️ Database Records
- Plugin metadata inserted into `plugins` collection
- Bundle uploaded to GridFS with ID: `694170539f315d1a57092f27`
- Plugin version document created in `plugin_versions` collection

### 🌐 Marketplace Availability
The plugin is now available in the OntoCode Plugin Marketplace with:
- ✅ Verified status
- ⭐ Featured plugin
- 📁 Category: Reasoning
- 🔍 Keywords: reasoner, hermit, owl, inference, classification, consistency, realization

### 🔄 Next Steps

#### Backend Implementation
To make the plugin fully functional, you need to:

1. **Add Reasoner Service** - Implement backend reasoning service
2. **Integrate OWL API** - Use OWL API with HermiT/Pellet/FaCT++
3. **Implement Endpoints** - Create REST API endpoints for reasoning tasks
4. **Handle Timeouts** - Implement timeout and cancellation logic
5. **Cache Results** - Add result caching for performance

#### Example Backend Structure (Java/Spring Boot):
```java
@RestController
@RequestMapping("/api/reasoner")
public class ReasonerController {
    
    @PostMapping("/consistency")
    public ConsistencyResult checkConsistency(@RequestBody ReasoningRequest request) {
        // Load ontology from GraphDB
        // Run consistency check using HermiT
        // Return results
    }
    
    @PostMapping("/classification")
    public ClassificationResult classify(@RequestBody ReasoningRequest request) {
        // Load ontology
        // Run classification
        // Return class hierarchy
    }
    
    @PostMapping("/realization")
    public RealizationResult realize(@RequestBody ReasoningRequest request) {
        // Load ontology
        // Run realization
        // Return instance types
    }
}
```

### License
MIT License - Free to use and modify
