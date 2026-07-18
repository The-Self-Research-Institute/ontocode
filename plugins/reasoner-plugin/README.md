# OWL Reasoner Plugin

A HermiT-inspired OWL reasoner plugin for OntoCode that provides advanced reasoning capabilities including consistency checking, classification, realization, and inference.

## Features

### Reasoning Tasks
- **Consistency Checking**: Verify if the ontology is logically consistent
- **Classification**: Compute the class hierarchy and identify equivalent/unsatisfiable classes
- **Realization**: Determine the most specific types for all individuals
- **Satisfiability**: Check if a class can have instances
- **Entailment**: Verify if an axiom is logically entailed
- **Explanation**: Generate justifications for entailments

### Reasoner Types
- **HermiT**: Hypertableau-based reasoner (default)
- **Pellet**: Complete and incremental reasoner
- **FaCT++**: Fast classifier for expressive ontologies
- **ELK**: Efficient reasoner for EL++ ontologies

### Advanced Features
- **Incremental Reasoning**: Efficiently handle ontology changes
- **Result Caching**: Store and reuse reasoning results
- **Progress Tracking**: Real-time updates on reasoning progress
- **Inferred Axioms**: View all axioms inferred by the reasoner
- **Statistics**: Detailed metrics about the reasoning process
- **Export Results**: Save reasoning results as JSON

## Usage

### Installation
The plugin is automatically available in the OntoCode plugin marketplace.

### Configuration
1. **Select Reasoner Type**: Choose between HermiT, Pellet, FaCT++, or ELK
2. **Set Timeout**: Configure maximum reasoning time (5-300 seconds)
3. **Enable Options**:
   - Incremental Reasoning: Better performance for ontology updates
   - Cache Results: Reuse previous reasoning results

### Running Reasoning Tasks

#### Consistency Check
Verifies that the ontology contains no logical contradictions.
```
Result: Consistent / Inconsistent
Duration: Time taken in milliseconds
Errors: List of logical conflicts (if inconsistent)
```

#### Classification
Computes the complete class hierarchy, identifies equivalent classes, and detects unsatisfiable classes.
```
Result:
- Class hierarchy with subsumption relationships
- Equivalent class groups
- Unsatisfiable classes (if any)
Duration: Time taken in milliseconds
```

#### Realization
Determines the most specific types (direct types) for all individuals in the ontology.
```
Result:
- Individual → Direct Types mapping
- Individual → All Types mapping
Duration: Time taken in milliseconds
```

### Viewing Results

#### Consistency
- Green checkmark: Ontology is consistent
- Red cross: Ontology is inconsistent
- Error list: Specific logical conflicts

#### Classification
- Class Hierarchy Nodes: Total number of classes
- Equivalent Classes: Groups of logically equivalent classes
- Unsatisfiable Classes: Classes that cannot have instances

#### Realization
- Individuals Realized: Number of individuals with computed types

#### Inferred Axioms
View all axioms inferred by the reasoner:
- SubClassOf relationships
- EquivalentClass relationships
- DisjointWith relationships
- InstanceOf (type) assertions
- SameAs (equality) assertions
- DifferentFrom assertions

Each axiom shows:
- Type (relationship kind)
- Subject → Object
- Confidence (100%)

#### Statistics
- Total Classes
- Individuals
- Properties
- Satisfiable Classes
- Unsatisfiable Classes
- Inferred Axioms

### Export Results
Click the download button to export all reasoning results as JSON:
```json
{
  "consistency": {...},
  "classification": {...},
  "realization": {...},
  "inferredAxioms": [...],
  "stats": {...},
  "exportDate": "2025-12-16T..."
}
```

## API Integration

The plugin communicates with the backend reasoner service via REST API:

### Endpoints
- `POST /api/reasoner/consistency` - Check consistency
- `POST /api/reasoner/classification` - Classify ontology
- `POST /api/reasoner/realization` - Realize individuals
- `POST /api/reasoner/satisfiability` - Check class satisfiability
- `POST /api/reasoner/entailment` - Check axiom entailment
- `POST /api/reasoner/stop` - Stop running reasoning task

### Request Format
```json
{
  "projectId": "string",
  "ontologyIri": "string",
  "reasonerType": "hermit" | "pellet" | "fact++" | "elk",
  "timeout": 30000,
  "useIncrementalReasoning": true
}
```

## Performance Tips

1. **Use ELK for Large Ontologies**: ELK is optimized for EL++ and scales better
2. **Enable Incremental Reasoning**: Faster updates after ontology changes
3. **Enable Caching**: Reuse results when possible
4. **Adjust Timeout**: Increase for complex ontologies
5. **Run Classification First**: Required before realization

## Troubleshooting

### Reasoning Fails
- Check ontology is loaded correctly
- Increase timeout value
- Try a different reasoner type
- Check backend service is running

### Inconsistent Ontology
- Review error messages
- Check class definitions
- Verify property restrictions
- Look for contradictory axioms

### Slow Performance
- Use ELK for large ontologies
- Enable incremental reasoning
- Reduce timeout
- Simplify complex class expressions

## Technical Details

### Supported OWL Features
- Classes and individuals
- Object and datatype properties
- Property restrictions (some/all values from, cardinality)
- Class expressions (union, intersection, complement)
- Equivalence and disjointness
- Property chains and transitivity
- Nominals and datatype restrictions

### Reasoning Algorithms
- **HermiT**: Hypertableau calculus
- **Pellet**: Tableaux with absorption and caching
- **FaCT++**: Optimized tableaux
- **ELK**: Consequence-based reasoning

## License
MIT

## Credits
Inspired by HermiT reasoner developed by the University of Oxford.
