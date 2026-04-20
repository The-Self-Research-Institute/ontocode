# Citation Plugin - Testing Document

**Plugin:** citation-plugin (under development)  
**Categories:** Ontology, Documentation, Bibliography  
**Last Updated:** April 2026

---

## Table of Contents
1. [Overview](#overview)
2. [Test Environment Setup](#test-environment-setup)
3. [Citation CRUD Tests](#citation-crud-tests)
4. [Citation Type Tests](#citation-type-tests)
5. [BibTeX Import Tests](#bibtex-import-tests)
6. [Citation Linking Tests](#citation-linking-tests)
7. [Bibliography Generation Tests](#bibliography-generation-tests)
8. [Citation Network Tests](#citation-network-tests)
9. [Error Handling Tests](#error-handling-tests)

---

## Overview

The Citation Plugin manages citations for ontologies — attaching references (research papers, clinical guidelines, standards) to ontology entities. Supports BibTeX import, multiple citation formats, citation networks, and bibliography generation.

### Citation Types Supported
| Type | Description | Example |
|------|-------------|---------|
| `clinical-guideline` | Medical/clinical guidelines | AHA Blood Pressure Guidelines |
| `clinical-standard` | Clinical protocols/standards | Task Force measurement standards |
| `research-paper` | Journal articles with DOI | Published research papers |
| `clinical-documentation` | Clinical documentation standards | Clinical practice documentation |

### API Endpoints (Anticipated)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/citations/{projectId}` | Add citation |
| GET | `/api/citations/{projectId}/{entityId}` | Get entity citations |
| POST | `/api/citations/{projectId}/import-bibtex` | Bulk BibTeX import |
| GET | `/api/citations/{projectId}` | List all citations |
| DELETE | `/api/citations/{projectId}/{citationId}` | Delete citation |
| PUT | `/api/citations/{projectId}/{citationId}` | Update citation |

---

## Test Environment Setup

### Prerequisites
- OntoCode platform running
- GraphDB with loaded test ontology
- Sample BibTeX files for import testing

### Test Domain: Cardiovascular Measurement Ontology
**Classes:** BloodPressure, SystolicBP, DiastolicBP, HeartRate, ECG, BloodPressureMeasurement  
**Properties:** hasValue, hasUnit, measuredBy, referenceSource  
**Annotation Property:** `:referenceSource` — links entities to citations

### Sample Citations
| ID | Type | Title |
|----|------|-------|
| cite-001 | clinical-guideline | 2017 AHA/ACC Blood Pressure Guidelines |
| cite-002 | research-paper | Ambulatory Blood Pressure Monitoring Study |
| cite-003 | clinical-standard | ESC/ESH Hypertension Task Force Standard |

---

## Citation CRUD Tests

### TC-CT-001: Add Citation
**Objective:** Verify creating a new citation  
**Steps:**
1. Open Citation plugin
2. Click "Add Citation"
3. Fill in fields:
   - Type: `research-paper`
   - Title: "Blood Pressure Measurement Accuracy Study"
   - Authors: ["Smith, J.", "Jones, A."]
   - Year: 2023
   - Journal: "Journal of Hypertension"
   - Volume: 41, Issue: 3, Pages: "145-152"
   - DOI: "10.1097/HJH.0000000001234"
4. Save

**Expected Results:**
- Citation created with unique ID
- All fields stored correctly
- Citation visible in citation list
- Success notification

---

### TC-CT-002: View Citation Details
**Objective:** Verify viewing citation full details  
**Steps:**
1. Select a citation from the list
2. View detail panel

**Expected Results:**
- All metadata displayed:
  - id, type, title, authors, year
  - journal, volume, issue, pages
  - url, doi, pmid
  - organization (for guidelines)
  - accessDate
- Formatted display (not raw JSON)

---

### TC-CT-003: Update Citation
**Objective:** Verify editing an existing citation  
**Steps:**
1. Select citation
2. Edit title and add PMID
3. Save changes

**Expected Results:**
- Changes persisted
- Updated fields reflected in list
- Linked entities not affected

---

### TC-CT-004: Delete Citation
**Objective:** Verify deleting a citation  
**Steps:**
1. Select citation
2. Click "Delete"
3. Confirm

**Expected Results:**
- Citation removed from list
- Linked entity annotations cleaned up (or warning shown)
- Confirmation prompt before deletion

---

## Citation Type Tests

### TC-CT-005: Clinical Guideline Citation
**Objective:** Verify clinical guideline citation with organization field  
**Citation Data:**
```json
{
  "type": "clinical-guideline",
  "title": "2017 AHA/ACC Guideline for Blood Pressure",
  "organization": "American Heart Association",
  "year": 2017,
  "url": "https://www.ahajournals.org/doi/10.1161/HYP.0000000000000065"
}
```
**Expected Results:**
- Organization field displayed prominently
- Year shown
- URL clickable/linkable
- Type badge: "Clinical Guideline"

---

### TC-CT-006: Research Paper Citation with DOI/PMID
**Objective:** Verify research paper with full bibliographic metadata  
**Citation Data:**
```json
{
  "type": "research-paper",
  "title": "Ambulatory Blood Pressure Monitoring",
  "authors": ["White, WB.", "Berson, AS."],
  "year": 2011,
  "journal": "Blood Pressure Monitoring",
  "volume": "16",
  "issue": "1",
  "pages": "13-18",
  "doi": "10.1097/MBP.0b013e3283447b23",
  "pmid": "21358750"
}
```
**Expected Results:**
- All bibliographic fields displayed
- DOI formatted as link
- PMID formatted as PubMed link
- Author list properly formatted

---

### TC-CT-007: Clinical Standard Citation
**Objective:** Verify clinical standard type  
**Citation Data:**
```json
{
  "type": "clinical-standard",
  "title": "ESC/ESH Guidelines for the Management of Arterial Hypertension",
  "organization": "European Society of Cardiology",
  "year": 2018
}
```
**Expected Results:**
- Standard type icon/badge
- Organization displayed
- Year displayed

---

## BibTeX Import Tests

### TC-CT-008: Single BibTeX Entry Import
**Objective:** Verify importing a single BibTeX entry  
**BibTeX:**
```bibtex
@article{white2011,
  title = {Ambulatory Blood Pressure Monitoring},
  author = {White, WB and Berson, AS},
  journal = {Blood Pressure Monitoring},
  year = {2011},
  volume = {16},
  number = {1},
  pages = {13--18},
  doi = {10.1097/MBP.0b013e3283447b23}
}
```
**Steps:**
1. Click "Import BibTeX"
2. Paste or upload BibTeX content
3. Click Import

**Expected Results:**
- Citation created from BibTeX data
- Title, authors, journal, year mapped correctly
- DOI extracted
- Volume, issue (number), pages mapped

---

### TC-CT-009: Bulk BibTeX Import (Multiple Entries)
**Objective:** Verify importing multiple BibTeX entries at once  
**Steps:**
1. Upload BibTeX file with 10+ entries
2. Import all

**Expected Results:**
- All entries imported
- Count of imported citations displayed
- Duplicate detection (if entry already exists)
- Import summary with success/failure count

---

### TC-CT-010: BibTeX Import — Various Entry Types
**Objective:** Verify handling of different BibTeX types  
**Entry Types:**
- `@article` → research-paper
- `@inproceedings` → research-paper
- `@book` → research-paper
- `@misc` → clinical-documentation
- `@techreport` → clinical-standard

**Expected Results:**
- Each BibTeX type mapped to appropriate citation type
- All relevant fields extracted
- No data loss during conversion

---

### TC-CT-011: BibTeX Import — Malformed Input
**Objective:** Verify error handling for invalid BibTeX  
**Steps:**
1. Import BibTeX with missing closing braces
2. Import with invalid encoding
3. Import empty file

**Expected Results:**
- Parse error message with line indication
- Partial import: valid entries imported, invalid skipped
- Empty file: "No entries found" message

---

## Citation Linking Tests

### TC-CT-012: Link Citation to Ontology Class
**Objective:** Verify attaching citation to a class via annotation  
**Steps:**
1. Select ontology class `:BloodPressure`
2. Click "Add Citation" → select cite-001
3. Citation linked via `:referenceSource` annotation

**Expected Results:**
- Citation annotation added to class
- Citation visible when viewing class details
- Multiple citations can be linked to same class
- Citation badge/count shown on class

---

### TC-CT-013: Link Citation to Property
**Objective:** Verify attaching citation to a property  
**Steps:**
1. Select property `:hasValue`
2. Link citation

**Expected Results:**
- Citation annotation added to property
- Visible in property detail panel

---

### TC-CT-014: Link Citation to Individual
**Objective:** Verify attaching citation to an individual  
**Steps:**
1. Select an individual
2. Link citation

**Expected Results:**
- Citation stored as annotation on individual
- Retrievable and displayable

---

### TC-CT-015: Multiple Citations on Same Entity
**Objective:** Verify multiple citations can be linked to one entity  
**Steps:**
1. Link cite-001, cite-002, cite-003 to `:BloodPressure`
2. View entity citations

**Expected Results:**
- All 3 citations listed
- Ordered by year or relevance
- Each removable independently

---

### TC-CT-016: Remove Citation Link
**Objective:** Verify unlinking a citation from an entity  
**Steps:**
1. Select entity with linked citations
2. Remove one citation link
3. Verify remaining links intact

**Expected Results:**
- Selected citation removed from entity
- Other citations unchanged
- Citation itself not deleted (only link removed)

---

## Bibliography Generation Tests

### TC-CT-017: Generate Full Bibliography
**Objective:** Verify generating bibliography for all citations in ontology  
**Steps:**
1. Add multiple citations of various types
2. Click "Generate Bibliography"

**Expected Results:**
- Complete bibliography generated
- Citations formatted in standard academic style
- Sorted alphabetically by first author
- DOI/URL included where available

---

### TC-CT-018: Generate Entity-Specific Bibliography
**Objective:** Verify generating bibliography for a specific entity's citations  
**Steps:**
1. Select entity with 3+ citations
2. Generate bibliography for that entity

**Expected Results:**
- Only linked citations included
- Properly formatted
- Entity context shown (which class/property)

---

### TC-CT-019: Export Bibliography Formats
**Objective:** Verify bibliography export in multiple formats  
**Formats:**
- BibTeX export
- Plain text (APA style)
- HTML formatted

**Expected Results:**
- BibTeX: Valid .bib file with all entries
- Plain text: Proper APA formatting
- HTML: Styled bibliography with links

---

## Citation Network Tests

### TC-CT-020: Citation Network Visualization
**Objective:** Verify citation network/graph display  
**Steps:**
1. Add citations that reference each other
2. Open Citation Network view

**Expected Results:**
- Nodes represent citations
- Edges represent citation relationships
- Clusters of related citations visible
- Interactive (click for details)

---

### TC-CT-021: Entity-Citation Network
**Objective:** Verify network showing entities linked to citations  
**Steps:**
1. Link multiple entities to overlapping citations
2. View Entity-Citation Network

**Expected Results:**
- Entity nodes connected to citation nodes
- Shared citations create clusters
- Bipartite graph structure visible

---

## Error Handling Tests

### TC-CT-022: Duplicate Citation Detection
**Objective:** Verify duplicate citations are flagged  
**Steps:**
1. Add citation with DOI "10.1097/xxx"
2. Attempt to add another citation with same DOI

**Expected Results:**
- Warning: "Citation with this DOI already exists"
- Option to update existing or create duplicate
- No silent data loss

---

### TC-CT-023: Required Fields Validation
**Objective:** Verify mandatory fields are enforced  
**Steps:**
1. Try creating citation without title
2. Try creating citation without type
3. Try creating citation without year

**Expected Results:**
- Validation error for missing required fields
- Clear indication of which fields are required
- Form not submitted until valid

---

### TC-CT-024: Invalid DOI Format
**Objective:** Verify DOI format validation  
**Steps:**
1. Enter DOI: "not-a-doi"
2. Enter DOI: "10.1097/valid-format"

**Expected Results:**
- Invalid DOI: warning shown
- Valid DOI: accepted
- DOI format: `10.xxxx/yyyy`

---

### TC-CT-025: API Unavailable
**Objective:** Verify handling when citation service is down  
**Steps:**
1. Disconnect citation backend
2. Attempt CRUD operations

**Expected Results:**
- Connection error messages
- Local data preserved
- Retry available when reconnected

---

## Version Tracking Tests

### TC-CT-026: Citation Version History
**Objective:** Verify tracking citation updates over time  
**Steps:**
1. Create citation
2. Update citation 3 times
3. View version history

**Expected Results:**
- All versions tracked with timestamps
- Diff between versions viewable
- Revert to previous version option

---

## Test Summary Matrix

| Test ID | Category | Priority | Status |
|---------|----------|----------|--------|
| TC-CT-001 | CRUD | P0 | ☐ |
| TC-CT-002 | CRUD | P0 | ☐ |
| TC-CT-003 | CRUD | P1 | ☐ |
| TC-CT-004 | CRUD | P0 | ☐ |
| TC-CT-005 | Citation Types | P1 | ☐ |
| TC-CT-006 | Citation Types | P0 | ☐ |
| TC-CT-007 | Citation Types | P1 | ☐ |
| TC-CT-008 | BibTeX Import | P0 | ☐ |
| TC-CT-009 | BibTeX Import | P0 | ☐ |
| TC-CT-010 | BibTeX Import | P1 | ☐ |
| TC-CT-011 | BibTeX Import | P1 | ☐ |
| TC-CT-012 | Linking | P0 | ☐ |
| TC-CT-013 | Linking | P1 | ☐ |
| TC-CT-014 | Linking | P1 | ☐ |
| TC-CT-015 | Linking | P1 | ☐ |
| TC-CT-016 | Linking | P0 | ☐ |
| TC-CT-017 | Bibliography | P1 | ☐ |
| TC-CT-018 | Bibliography | P2 | ☐ |
| TC-CT-019 | Bibliography | P1 | ☐ |
| TC-CT-020 | Network | P2 | ☐ |
| TC-CT-021 | Network | P2 | ☐ |
| TC-CT-022 | Error Handling | P1 | ☐ |
| TC-CT-023 | Error Handling | P0 | ☐ |
| TC-CT-024 | Error Handling | P2 | ☐ |
| TC-CT-025 | Error Handling | P0 | ☐ |
| TC-CT-026 | Versioning | P2 | ☐ |
