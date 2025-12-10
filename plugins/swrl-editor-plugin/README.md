# SWRL Editor Plugin

A powerful plugin for the OntoCode ontology editor that provides comprehensive SWRL (Semantic Web Rule Language) editing and validation capabilities.

## Features

- **Visual Rule Editor**: Create and edit SWRL rules with a user-friendly interface
- **Syntax Validation**: Real-time validation of SWRL syntax
- **Rule Execution**: Execute rules against your ontology
- **Rule Management**: Enable/disable individual rules, duplicate, and organize
- **Built-in Help**: Inline syntax help and examples

## Installation

1. Open OntoCode extension
2. Navigate to View → Plugin Marketplace
3. Search for "SWRL Editor"
4. Click Install

## Usage

### Creating a Rule

1. Click "New Rule" button
2. Enter your SWRL rule in the editor
3. Click "Validate" to check syntax
4. Click "Execute" to apply the rule

### Example Rules

```swrl
// Define adults as persons over 18
Person(?p) ∧ hasAge(?p, ?age) ∧ swrlb:greaterThan(?age, 18) → Adult(?p)

// Infer uncles
Person(?x) ∧ hasBrother(?x, ?y) ∧ hasParent(?z, ?x) → hasUncle(?z, ?y)

// Calculate BMI category
Person(?p) ∧ hasBMI(?p, ?bmi) ∧ swrlb:greaterThan(?bmi, 30) → Obese(?p)
```

## SWRL Syntax

### Atoms
- **Class Atoms**: `ClassName(?variable)`
- **Property Atoms**: `propertyName(?subject, ?object)`
- **Built-in Atoms**: `swrlb:functionName(?arg1, ?arg2, ...)`

### Operators
- **Conjunction (AND)**: `∧` or `^`
- **Implication**: `→` or `->`

### Built-in Functions
- **Comparison**: `greaterThan`, `lessThan`, `equal`, `notEqual`
- **Math**: `add`, `subtract`, `multiply`, `divide`
- **String**: `contains`, `startsWith`, `endsWith`
- **Date**: `date`, `time`, `dateTime`

## Requirements

- OntoCode Extension v1.0.0 or higher
- Active ontology project

## Contributing

Contributions are welcome! Please visit our [GitHub repository](https://github.com/The-Self-Research-Institute/ontocode).

## License

MIT License - see LICENSE file for details

## Support

For issues and questions:
- GitHub Issues: https://github.com/The-Self-Research-Institute/ontocode/issues
- Documentation: https://ontocode.dev/docs/plugins/swrl-editor

## Version History

### 1.0.0
- Initial release
- Rule creation and editing
- Syntax validation
- Rule execution
- Built-in help system
