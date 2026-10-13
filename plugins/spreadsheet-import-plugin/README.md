# Spreadsheet Import Plugin

Cellfie-style bulk import for OntoCode (FR-20): turn spreadsheet rows into ontology data with transformation rules.

## What it will do

- Upload an `.xlsx`, `.xls` or `.csv` file and view its sheets as a grid
- Write transformation rules in Cellfie's MappingMaster syntax (`Individual: @A*`, `Types:`, `Facts:`, `@B*(xsd:decimal)`)
- Preview every axiom the rules generate, with errors reported by rule, row and cell
- Add everything to the ontology as one Change Assistant entry that one undo removes
- Save rule sets in the project and re-run them on the next file of the same shape

## Status

Plugin setup only: installs and shows a placeholder tab. The import screen and its backend
(`/api/ontology/spreadsheet-import/**` in `ontology-editor`) are added in the next step.

## Build

```bash
npm install
npm run build
```

Output: `dist/index.js` (UMD, global `SpreadsheetImportPlugin`). React, ReactDOM and lucide-react are provided by the host app.
