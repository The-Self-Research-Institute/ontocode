# SPARQL Query Plugin

A full-featured SPARQL query editor plugin for OntoCode.

## Features

- **Query Editor**: Write and edit SPARQL queries with a clean interface
- **Query Management**: Save, load, and delete queries
- **Sample Queries**: Pre-built sample queries to get started
- **Live Execution**: Execute queries against your ontology in real-time
- **Results Display**: View results in table or JSON format
- **Export Options**: Download results as CSV or JSON
- **Prefix Support**: Automatically displays ontology prefixes

## Installation

```bash
cd plugins/sparql-query-plugin
npm install
npm run build
```

## Usage

The plugin is automatically loaded when you select the "SPARQL" tab in the OntoCode editor.

### Sample Queries Included

1. **List All Classes** - Shows all OWL classes with labels
2. **List All Properties** - Shows object, data, and annotation properties
3. **List All Individuals** - Shows all named individuals by type
4. **Count Triples** - Counts total triples in the graph
5. **SubClass Hierarchy** - Shows the class hierarchy

## API Integration

The plugin uses the following backend endpoints:

- `GET /api/sparql/{projectId}/queries` - List saved queries
- `POST /api/sparql/{projectId}/queries` - Create new query
- `PUT /api/sparql/{projectId}/queries/{id}` - Update query
- `DELETE /api/sparql/{projectId}/queries/{id}` - Delete query
- `POST /api/sparql/{projectId}/execute` - Execute query

## Development

```bash
npm run watch  # Development mode with hot reload
npm run build  # Production build
```
