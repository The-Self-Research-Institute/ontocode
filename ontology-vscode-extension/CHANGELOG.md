# Changelog

All notable changes to OntoCode Studio are listed here, newest first. The same notes appear in the app under What's new.

## [1.2.6] - 2026-10-16

**Undo and Redo**

- ONTOCODE-194: Undo and Redo — Edit > Undo and Edit > Redo now work, with Ctrl+Z and Ctrl+Shift+Z. They reverse creating and deleting entities, adding and removing axioms, drag-and-drop in the class hierarchy, annotation and axiom-annotation edits, characteristic changes, prefix changes, renames, and ontology IRI changes.
- ONTOCODE-194: Undo and Redo — An operation that changes several things at once, such as Change IRI or an AI edit, is undone as a single unit. Undo restores the exact previous triples.
- ONTOCODE-194: Undo and Redo — In shared projects, Undo only reverses your own changes. If someone else has since changed or built on the same item, Undo tells you why it can't proceed instead of silently reverting their work.
- ONTOCODE-194: Undo and Redo — Every undo and redo is recorded in the Change Assistant and is safe if the connection drops or the server restarts partway through. Repeating the same request never applies it twice.
- ONTOCODE-194: Undo and Redo — Also covers datatype definitions, fuzzy and SWRL rules, citations, and, on desktop, ontologies edited offline.
- ONTOCODE-194: Change Assistant — The number of changes on a card, on the Undo all button, and in the undo preview now always agree, and times are shown in your local time zone so an undo appears after the edit it undid.

## [1.2.5] - 2026-10-07

**Code View AI Assistant**

- Code View AI Assistant — Ask questions about your ontology, or have the AI propose edits directly in Code View (Turtle, RDF/XML, OWL/XML, Manchester, and Functional syntax). Review each proposed change before applying it, and undo or roll back applied changes individually or as a group.
- Code View AI Assistant — The assistant can read specific ranges, identifiers, or statements from your document, run SPARQL queries, check consistency and explain inconsistencies, add and run SWRL rules, and add fuzzy class memberships, all as part of the conversation.
- Code View AI Assistant — Works with your own OpenAI, Gemini, or Claude API key, or your organization's managed provider. A live status indicator shows what the assistant is doing, and a configurable session budget controls how much it reads before answering.
- Code View AI Assistant — Supports Draft Mode: AI-assisted edits apply privately to your own draft copy instead of the shared project, and class/individual counts update immediately once a change is applied.
- ONTOCODE-89: Every change the assistant applies is recorded in the Change Assistant's history alongside manual edits, so you can see exactly what changed and when.
- ONTOCODE-93: Ontology Prefixes — Draft Mode now applies to prefixes too: adding, editing, or deleting a prefix while in Draft Mode stays private to your draft, and shows up in the Change Assistant's history like any other edit.

## [1.2.4] - 2026-09-25

- ONTOCODE-93: Ontology Prefixes — Fixed prefixes from a file's namespace declarations not showing in the Ontology Prefixes panel and fixed the prefix count increasing on export.
- ONTOCODE-89: Changes made in Code View are now reflected in the Change Assistant. Implemented the grouped rollback option to allow related changes to be rolled back together.

## [1.2.3] - 2026-09-24

- ONTOCODE-92: Active Ontology — Fixed Metrics panel showing wrong counts for Annotation Properties and Datatypes compared to the Entities tab.

## [1.2.2] - 2026-09-08

- TSRI-348: Reasoner — "Explain Inconsistency" now finds the real cause of each inconsistency, instead of only recognizing a fixed set of common cases. Added Regular and Laconic explanation styles and an option to view all causes or just the first few.

## [1.2.1] - 2026-09-04

- TSRI-356: Share with Members — Fixed sharing a project with multiple members at once failing with a false "user not found" error.

## [1.2.0] - 2026-08-20

- TSRI-293: VOWL — Fixed Collapse followed by Fit to Screen hanging, requiring an extra click before the full graph appears.
- TSRI-151: Class Hierarchy — Fixed sibling creation often selecting the wrong class instead of the intended one.

## [1.1.0-beta.19] - 2026-08-14

**Individuals List, Resizable Panel, Editable IRI & Report Panel**

- TSRI-260: Individuals — Fixed the individuals list getting stuck at 50 after adding more or merging with more than 50 individuals; the full list now shows correctly.
- TSRI-253: Entities Panel — The left panel can now be resized by dragging its edge, for more room to view expanded classes.
- TSRI-243: Class Editor — You can now edit a class's IRI. Clicking edit lets you change the ID portion at the end of the IRI, similar to Protégé.
- TSRI-254: Report Panel — Switching to "Feature Request" now updates the panel's title, button, and confirmation message to match, instead of showing generic bug-report wording.

## [1.1.0-beta.18] - 2026-08-08

**Graph View — Interaction & Layout Options**

- TSRI-130: Graph View — Clicking a node now selects/expands it in place instead of navigating to the editor. Use the hover card's "Go to entity" action or the right-click menu to open a node in the editor.
- TSRI-134: Graph View — Added Hierarchy (Tree), Radial, Layered, and Clustered layout options alongside the existing Network (force-directed) view, plus a Focus/Neighborhood mode centered on a selected node. Switch views from the Visualization dropdown in the toolbar.
- TSRI-131: Graph View — Large ontologies show a curated ~35-node slice of the hierarchy by default for performance. Click 🌳 Expand All in the toolbar to load the full class hierarchy.

## [1.1.0-beta.17] - 2026-07-31

**Startup Reliability & Reasoner Fixes**

- TSRI-227: Desktop — Fixed the reasoner's ontology failing to load on startup, which could prevent the app from starting at all.

## [1.1.0-beta.16] - 2026-07-27

**Rename, Save & Export Reliability**

- TSRI-223: SPARQL Query — Fixed a bug causing SPARQL queries to fail.
- TSRI-222: Renaming an entity and dragging to select text in the name field no longer closes the editor and cancels the rename if the drag ends outside the text field.
- TSRI-222: Saving or exporting an ontology as an OWL file no longer opens two consecutive save dialogs for a single action.
- TSRI-222: Added or edited ontology values (axioms, types, annotations) now display immediately after saving, instead of only appearing after selecting a different entity or panel and returning.

## [1.1.0-beta.15] - 2026-07-23

**Reasoner Reliability & Clearer Error Messages**

- TSRI-196: Reasoner — When classification fails, the app now shows a specific, useful error message and a suggested next step (e.g. an ontology-specific problem, a missing component, or an invalid setting) instead of a generic "Not Found" message with no explanation.

## [1.1.0-beta.14] - 2026-07-13

**Save Reliability · Graph View Overhaul**

- TSRI-189: Code View / Graph View / DLQuery — Fixed a caching bug where classes and edits made in Code View (or directly in Graph View) could silently fail to persist, or appear in one view (e.g. Graph View) but not others (Entities hierarchy tree, DLQuery). A stale hierarchy cache wasn't being invalidated after saves, and failed saves were silently falling back to a local cache instead of surfacing an error — saving now fails loudly with a clear dialog if it doesn't actually persist.

## [1.1.0-beta.13] - 2026-06-29

**Label/ID Toggle · JSON-LD Code View**

- TSRI-141: Class Hierarchy — Added View → Rendering options to control how entities are displayed across the entire hierarchy. Render by label shows the rdfs:label value (e.g. "Continuant", "Agent"). Render by name shows the retained ontology ID from the IRI (e.g. BFO_0000001, CCO_0000012) — useful when cross-referencing entities by their coded identifier. Render by annotation property shows the value of any annotation property in the ontology (e.g. skos:prefLabel, dcterms:title), chosen from a submenu. Custom rendering lets you define a template combining label, ID, and annotation values in any format (e.g. {label} ({id})). All modes apply across Classes, Properties, and Individuals tabs simultaneously.
- TSRI-173: Code View — JSON-LD is now a first-class tab in OWL/RDF Code View, matching the formats available on export. The VSCode extension also now registers .jsonld as a recognised language.

## [1.1.0-beta.12] - 2026-06-25

- TSRI-166: Graph View — Fixed stale cache showing the wrong ontology after switching projects. Also added local graph view — click Local in the toolbar to see the neighbourhood of the selected node in place of the main canvas.
- Submit Issue — Fixed network error when submitting issue reports or feature requests from the desktop app.

## [1.1.0-beta.11] - 2026-06-20

- TSRI-161: Reasoner — Fixed the reasoner appearing stalled on startup. The UI now shows elapsed time and active progress while running. For large ontologies, ELK is recommended over HermiT — it uses the OWL EL profile and computes only the class hierarchy, making it significantly faster on 100 MB+ files where HermiT can take 15–40 minutes.
