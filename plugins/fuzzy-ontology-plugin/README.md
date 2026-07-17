# Fuzzy Ontology Plugin for OntoCode

Fuzzy logic reasoning, visualization, and querying for ontologies.

## Features

1. **Advanced Membership Functions**
   - Triangular, Trapezoidal, Gaussian, Sigmoid, Bell-shaped
   - Real-time function visualization
   - Custom membership function definitions

2. **Sophisticated Fuzzy Reasoning**
   - Multiple T-norms (Product, Gödel, Łukasiewicz)
   - Multiple T-conorms (Probabilistic, Gödel, Łukasiewicz)
   - Subsumption checking with degrees
   - Consistency verification
   - Alpha-cut based reasoning

3. **Interactive Visualizations**
   - Membership degree heatmaps
   - Concept hierarchy trees with fuzzy degrees
   - Individual radar charts
   - Membership function plots
   - Interactive matrix views

4. **Powerful Query DSL**
   - SQL-like fuzzy query language
   - Fluent query builder API
   - Pre-defined query templates
   - Multiple output formats (Table, JSON, CSV, HTML)

5. **Alpha-Embeddings**
   - Hierarchical fuzzy concept embeddings
   - Semantic grounding based on research
   - Real-time query composition without retraining

## Installation

1. Download the `.vsix` file
2. In OntoCode, go to Extensions → Install from VSIX
3. Select the `fuzzy-ontology-plugin-1.0.0.vsix` file

## Quick Start

### 1. Enable Fuzzy Mode

```
Ctrl+Shift+P → "Enable Fuzzy Ontology Mode"
```

### 2. Annotate Your Ontology

```turtle
@prefix fuzzy: <http://www.ontocode.org/fuzzy#> .
@prefix ex: <http://example.org/> .

# Define a fuzzy concept
ex:Diabetic a owl:Class ;
    fuzzy:membershipFunction fuzzy:Gaussian ;
    fuzzy:parameters "100.0, 20.0"^^xsd:string .

# Assert fuzzy membership
ex:Patient123 a ex:Diabetic ;
    fuzzy:membershipDegree "0.85"^^xsd:double .
```

### 3. Run Fuzzy Queries

```sql
-- Find highly diabetic patients
FIND individuals WHERE memberOf(Diabetic) >= 0.8

-- Complex query with conjunction
FIND individuals WHERE memberOf(Diabetic AND Hypertensive) > 0.6

-- Top-K query
SELECT TOP 10 FROM Patient ORDER BY memberOf(HighRisk) DESC

-- Existential quantification
FIND individuals WHERE exists(hasDiagnosis, DiabetesMellitus) > 0.7
```

### 4. Visualize Memberships

```
Ctrl+Shift+P → "Visualize Membership Functions"
```

Choose from:
- **Membership Matrix**: Heatmap of all individuals vs concepts
- **Concept Hierarchy**: Tree view with average membership degrees
- **Individual Radar Chart**: Multi-concept membership for one individual
- **Membership Function Plot**: Visual representation of membership functions

## API Usage

### Programmatic Fuzzy Ontology

```typescript
import { FuzzyOntology, TNorm, TCoNorm } from 'fuzzy-ontology-plugin';

// Create fuzzy ontology
const ontology = new FuzzyOntology({
  tNorm: TNorm.PRODUCT,
  tCoNorm: TCoNorm.PROBABILISTIC
});

// Add concepts
ontology.addConcept({
  uri: 'http://example.org/Diabetic',
  label: 'Diabetic Patient',
  instances: new Map(),
  membershipFunction: {
    type: MembershipFunctionType.GAUSSIAN,
    parameters: [100.0, 20.0]
  }
});

// Set membership degrees
ontology.setMembershipDegree('ex:Patient123', 'ex:Diabetic', 0.85);

// Query
const results = ontology.getInstances('ex:Diabetic', 0.7);
```

### Fluent Query Builder

```typescript
import { FuzzyQueryBuilder } from 'fuzzy-ontology-plugin';

const result = new FuzzyQueryBuilder(ontology)
  .concept('Diabetic')
  .and('Hypertensive')
  .threshold(0.6)
  .limit(20)
  .orderBy('desc')
  .execute();

console.log(result.individuals);
```

### Fuzzy Reasoning

```typescript
import { FuzzySubsumptionReasoner } from 'fuzzy-ontology-plugin';

const reasoner = new FuzzySubsumptionReasoner(ontology);

// Check subsumption
const result = reasoner.checkSubsumption('Type2Diabetes', 'Diabetic');
console.log(`Subsumption degree: ${result.degree}`);

// Find most specific concepts
const concepts = reasoner.findMostSpecificConcepts('ex:Patient123', 0.5);
```

### Custom Visualizations

```typescript
import { MembershipFunctionPlotter } from 'fuzzy-ontology-plugin';

const plot = MembershipFunctionPlotter.generateSVG(
  {
    type: MembershipFunctionType.TRIANGULAR,
    parameters: [0, 50, 100]
  },
  [0, 100],
  { theme: 'gradient', width: 800, height: 400 }
);
```

## Configuration

### Extension Settings

- `fuzzy.defaultTNorm`: Default T-norm for conjunction (Product, Gödel, Łukasiewicz)
- `fuzzy.defaultTCoNorm`: Default T-conorm for disjunction (Probabilistic, Gödel, Łukasiewicz)
- `fuzzy.visualizationTheme`: Theme for visualizations (gradient, heatmap, categorical)
- `fuzzy.alphaDecay`: Alpha decay parameter for hierarchical embeddings (0.0 - 1.0)

## Query Language Reference

### Basic Syntax

```sql
FIND|SELECT|GET individuals
WHERE <condition>
[ORDER BY <field> ASC|DESC]
[TOP|LIMIT <number>]
```

### Conditions

- `memberOf(Concept)`: Check membership in a concept
- `memberOf(C1 AND C2)`: Conjunction of concepts
- `memberOf(C1 OR C2)`: Disjunction of concepts
- `memberOf(NOT C)`: Negation of concept
- `exists(property, Concept)`: Existential quantification
- `forall(property, Concept)`: Universal quantification

### Operators

- `>=`, `>`, `<=`, `<`, `=`: Comparison with threshold
- `AND`, `OR`, `NOT`: Logical operators

### Examples

```sql
-- High certainty query
FIND individuals WHERE memberOf(Diabetic) >= 0.9

-- Boundary cases (uncertain)
FIND individuals WHERE memberOf(Diabetic) >= 0.3 AND memberOf(Diabetic) <= 0.7

-- Complex conjunction
FIND individuals WHERE memberOf(Diabetic AND Hypertensive AND Obese) > 0.5

-- Existential query
FIND individuals WHERE exists(hasDiagnosis, DiabetesMellitus) >= 0.8

-- Top-K most diabetic
SELECT TOP 10 FROM individuals WHERE memberOf(Diabetic) > 0 ORDER BY degree DESC
```

## Research Background

This plugin implements concepts from recent research:

- **Fuzzy Ontology Embeddings**: α-embeddings with decay parameters for hierarchical concept structures
- **T-norms and T-conorms**: Multiple fuzzy logic operators for flexible reasoning
- **Graded Membership**: Continuous truth values between 0 and 1
- **Compositional Semantics**: Real-time concept composition without retraining

Reference: [Fuzzy Ontology Embeddings (arXiv)](https://arxiv.org/html/2508.08128v1)

## Architecture

```
fuzzy-ontology-plugin/
├── src/
│   ├── core/
│   │   ├── FuzzyLogic.ts          # T-norms, T-conorms, membership functions
│   │   └── FuzzyOntology.ts       # Core ontology model
│   ├── reasoning/
│   │   └── FuzzyReasoner.ts       # Subsumption, consistency, alpha-cuts
│   ├── query/
│   │   └── FuzzyQueryDSL.ts       # Query parser and builder
│   ├── visualization/
│   │   └── MembershipVisualizer.ts # Charts, plots, matrices
│   └── extension.ts                # Main entry point
├── package.json
└── README.md
```

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPL v3 License - see [LICENSE](LICENSE)

## Citation

If you use this plugin in research, please cite:

```bibtex
@software{fuzzy_ontology_plugin,
  title = {Fuzzy Ontology Plugin for OntoCode},
  year = {2024},
  author = {OntoCode Team},
  url = {https://github.com/ontocode/fuzzy-ontology-plugin}
}
```

## Support

- Documentation: [docs.ontocode.org/fuzzy](https://docs.ontocode.org/fuzzy)
- Issues: [GitHub Issues](https://github.com/ontocode/fuzzy-ontology-plugin/issues)
- Community: [Discord](https://discord.gg/ontocode)
