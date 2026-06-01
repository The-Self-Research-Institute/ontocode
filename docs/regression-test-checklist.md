# OntoCo Regression Test Checklist

Run this after every deploy to verify nothing is broken.
Test file: **test-merge-target.owl** (or any ontology with GuideDog/MiniPony/Horse/Animal hierarchy)

---

## 1. Class Hierarchy (EntityHierarchy)

| # | Test | Expected |
|---|---|---|
| 1.1 | Load ontology — classes appear | Animal, Dog, Horse, Pony, Person, Company, Country, Vehicle all visible |
| 1.2 | Expand Dog | GuideDog, MiniPony show as children |
| 1.3 | Equivalent class annotation | GuideDog shows **≡ Mini Pony** label inline; MiniPony shows **≡ Guide dog** |
| 1.4 | Switch to Inferred mode | ≡ labels still appear (no regression from asserted mode fix) |
| 1.5 | Search classes | Filtering narrows tree correctly |
| 1.6 | Add a class | New class appears in tree after save |
| 1.7 | Rename a class | Label updates in tree |

---

## 2. Class Description Tab (ClassEditor)

| # | Test | Expected |
|---|---|---|
| 2.1 | Select GuideDog → Description tab | EquivalentTo section shows "Mini Pony" |
| 2.2 | Select MiniPony → Description tab | EquivalentTo section shows "Guide Dog" (symmetry fix) |
| 2.3 | Add SubClassOf axiom | Appears under SubClass Of section |
| 2.4 | Add EquivalentTo axiom (class expression) | Dialog opens; only appropriate tabs available |
| 2.5 | Add intersection (Horse ⊓ Animal) | Expression saves and displays correctly |
| 2.6 | Add Object restriction (someValuesFrom/allValuesFrom) | Appears in Description tab after save |
| 2.7 | Add Data restriction — **value** type | Saves correctly (was broken: `case 'value'` was missing) |
| 2.8 | Add Data restriction — min/max cardinality | Saves correctly |
| 2.9 | Delete an axiom | Axiom removed after confirm |

---

## 3. Annotations

| # | Test | Expected |
|---|---|---|
| 3.1 | Select a class → Annotations tab → click + | Create Annotation dialog opens (was silently blocked) |
| 3.2 | Add rdfs:comment to a class | Comment appears in Annotations tab |
| 3.3 | Add annotation when no item selected (ActiveOntology tab) | Dialog still opens (ontology-level annotation) |
| 3.4 | Edit an existing annotation | Edit dialog opens pre-populated |
| 3.5 | Delete an annotation | Annotation removed |
| 3.6 | Create Annotation dialog — click Refresh icon | Properties list refreshes (spinner visible) |
| 3.7 | Create Annotation dialog — click + icon | Inline "New Annotation Property" form appears |
| 3.8 | Create a new annotation property via inline form | Property appears in the list; can select and use it |
| 3.9 | Cancel inline create form with X | Form closes, no property created |

---

## 4. Object / Data Properties

| # | Test | Expected |
|---|---|---|
| 4.1 | Select an object property | Domain, Range, Characteristics populated |
| 4.2 | Add a property restriction via ClassEditor | Appears after 600ms reload |
| 4.3 | Create a new object property | Appears in hierarchy |
| 4.4 | Add sub-property relationship | Parent shown in hierarchy |

---

## 5. Individuals

| # | Test | Expected |
|---|---|---|
| 5.1 | View individuals list | Loads correctly |
| 5.2 | Add individual to a class | Appears under class |
| 5.3 | Add property assertion to individual | Saved and displayed |

---

## 6. File Import (Queue System)

| # | Test | Expected |
|---|---|---|
| 6.1 | Import a small .owl file (<10MB) | Import completes; classes appear in hierarchy |
| 6.2 | Import a second file while first is running | Second file queues; user sees position in queue |
| 6.3 | Check `ONTOCODE_IMPORT_MAX_CONCURRENT` default | Still 1 unless env var is set (no config regression) |

---

## 7. Collaboration / WebSocket

| # | Test | Expected |
|---|---|---|
| 7.1 | Two browsers open same project | Both see live updates when one edits |
| 7.2 | Add a class in browser A | Browser B sees new class appear |

---

## 8. Active Ontology Tab

| # | Test | Expected |
|---|---|---|
| 8.1 | View ontology IRI, prefixes | Displayed correctly |
| 8.2 | Add ontology-level annotation | Saved and shown |
| 8.3 | Edit ontology IRI | Updated |

---

## Change → Test Mapping (What to prioritize after each deploy)

| Change Made | Critical Tests |
|---|---|
| EquivalentClass UNION query fix | 2.1, 2.2, 1.3 |
| Data restriction `case 'value'` fix | 2.7 |
| Annotation dialog guard removed | 3.1, 3.3 |
| AddAnnotationDialog toolbar icons | 3.6, 3.7, 3.8, 3.9 |
| Equivalent class hierarchy display | 1.3, 1.4 |
| Gateway memory / codec config | 6.1, 6.2 |
| ImportQueueManager @Value | 6.2, 6.3 |
