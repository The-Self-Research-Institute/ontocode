# Ontology Graph View Plugin

Interactive graph visualization plugin for OntoCode that displays ontology classes, properties, and relationships as network diagrams using vis-network.

## Features

- **Interactive Network Visualization**: Explore ontology structure with pan, zoom, and node selection
- **Multiple Layout Algorithms**: Choose between force-directed, hierarchical, or circular layouts
- **Type Filtering**: Show/hide different node types (classes, individuals, properties)
- **Customizable Display**: Adjust node size, labels, arrows, and physics simulation
- **Export Capability**: Save graph visualizations as PNG images
- **Real-time Updates**: Refresh graph to reflect ontology changes
- **Node Selection**: Click nodes to view detailed information

## Installation

1. Open OntoCode VS Code extension
2. Navigate to **View → Plugin Marketplace**
3. Search for "Ontology Graph View"
4. Click **Install** button
5. The Graph View tab will appear in your project workspace

## Usage

### Basic Navigation

- **Pan**: Click and drag the canvas
- **Zoom**: Use mouse wheel or zoom buttons in toolbar
- **Select Node**: Click any node to view details
- **Fit to Screen**: Click maximize button to fit entire graph

### Layout Options

**Force-Directed** (Default)
- Nodes repel each other with physics simulation
- Best for medium-sized ontologies with organic structure

**Hierarchical**
- Arranges nodes in tree-like levels
- Ideal for taxonomies and inheritance hierarchies

**Circular**
- Places nodes in a circular pattern
- Good for small ontologies or overview visualization

### Filtering Node Types

Use the Filter panel to show/hide:
- **Classes**: OWL classes (blue boxes)
- **Individuals**: Class instances (green circles)
- **Properties**: All property types (orange diamonds)
- **Data Properties**: Literal-valued properties (purple diamonds)
- **Object Properties**: Object-valued properties (cyan diamonds)

### Graph Settings

- **Layout Algorithm**: Switch between visualization modes
- **Node Size**: Adjust from 15 to 50 pixels
- **Show Labels**: Toggle node label visibility
- **Show Arrows**: Display edge directionality
- **Enable Physics**: Turn on/off force simulation

### Export Graph

1. Position and zoom graph as desired
2. Click **Download** button in toolbar
3. Graph saves as PNG image with project ID filename

## API Integration

The plugin fetches graph data from:
```
GET /api/ontology/{projectId}/graph
```

Expected response format:
```json
{
  "nodes": [
    {
      "id": "class_1",
      "label": "Person",
      "type": "class",
      "color": "#4A90E2"
    }
  ],
  "edges": [
    {
      "id": "edge_1",
      "from": "class_1",
      "to": "class_2",
      "label": "subClassOf",
      "type": "subClassOf"
    }
  ]
}
```

### Node Types
- `class`: OWL classes
- `individual`: Class instances
- `property`: Generic properties
- `dataProperty`: Datatype properties
- `objectProperty`: Object properties

### Edge Types
- `subClassOf`: Class hierarchy
- `instanceOf`: Individual to class relationship
- `propertyRelation`: Property connections
- `custom`: User-defined relationships

## Dependencies

- **vis-network** ^9.1.9: Network visualization library
- **react** ^18.2.0: UI framework
- **lucide-react** ^0.263.1: Icon components

## Version History

### 1.0.0
- Initial release
- Force-directed, hierarchical, and circular layouts
- Node type filtering
- Graph settings panel
- PNG export
- Node selection and details display

## Support

- Report issues: [GitHub Issues](https://github.com/ontocode/graph-view-plugin/issues)
- Documentation: [Plugin Wiki](https://github.com/ontocode/graph-view-plugin/wiki)
- Contact: support@ontocode.dev

## License

MIT License - See LICENSE file for details
