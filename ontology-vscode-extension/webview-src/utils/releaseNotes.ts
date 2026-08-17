export interface ReleaseNote {

  version: string;

  date: string;

  title?: string;

  highlights: string[];

  videoUrl?: string;

  videoPoster?: string;
}

export const RELEASE_NOTES: ReleaseNote[] = [
  {
    version: "1.1.0-beta.19",
    date: "August 14, 2026",
    title: "Individuals List, Resizable Panel, Editable IRI & Report Panel",
    highlights: [
      "TSRI-260: Individuals — Fixed the individuals list getting stuck at 50 after adding more or merging with more than 50 individuals; the full list now shows correctly.",
      "TSRI-253: Entities Panel — The left panel can now be resized by dragging its edge, for more room to view expanded classes.",
      "TSRI-243: Class Editor — You can now edit a class's IRI. Clicking edit lets you change the ID portion at the end of the IRI, similar to Protégé.",
      "TSRI-254: Report Panel — Switching to \"Feature Request\" now updates the panel's title, button, and confirmation message to match, instead of showing generic bug-report wording.",
    ],
    videoUrl: "",
  },
  {
    version: "1.1.0-beta.18",
    date: "August 8, 2026",
    title: "Graph View — Interaction & Layout Options",
    highlights: [
      "TSRI-130: Graph View — Clicking a node now selects/expands it in place instead of navigating to the editor. Use the hover card's \"Go to entity\" action or the right-click menu to open a node in the editor.",
      "TSRI-134: Graph View — Added Hierarchy (Tree), Radial, Layered, and Clustered layout options alongside the existing Network (force-directed) view, plus a Focus/Neighborhood mode centered on a selected node. Switch views from the Visualization dropdown in the toolbar.",
      "TSRI-131: Graph View — Large ontologies show a curated ~35-node slice of the hierarchy by default for performance. Click 🌳 Expand All in the toolbar to load the full class hierarchy.",
    ],
    videoUrl: "",
  },
  {
    version: "1.1.0-beta.17",
    date: "July 31, 2026",
    title: "Startup Reliability & Reasoner Fixes",
    highlights: [
      "TSRI-227: Desktop — Fixed the reasoner's ontology failing to load on startup, which could prevent the app from starting at all.",
    ],
    videoUrl: ""
  },
  {
    version: "1.1.0-beta.16",
    date: "July 27, 2026",
    title: "Rename, Save & Export Reliability",
    highlights: [
      "TSRI-223: SPARQL Query — Fixed a bug causing SPARQL queries to fail.",
      "TSRI-222: Renaming an entity and dragging to select text in the name field no longer closes the editor and cancels the rename if the drag ends outside the text field.",
      "TSRI-222: Saving or exporting an ontology as an OWL file no longer opens two consecutive save dialogs for a single action.",
      "TSRI-222: Added or edited ontology values (axioms, types, annotations) now display immediately after saving, instead of only appearing after selecting a different entity or panel and returning.",
    ],
    videoUrl: "",
  },
  {
    version: "1.1.0-beta.15",
    date: "July 23, 2026",
    title: "Reasoner Reliability & Clearer Error Messages",
    highlights: [
      "TSRI-196: Reasoner — When classification fails, the app now shows a specific, useful error message and a suggested next step (e.g. an ontology-specific problem, a missing component, or an invalid setting) instead of a generic \"Not Found\" message with no explanation.",
    ],
    videoUrl: "",
  },
  {
    version: "1.1.0-beta.14",
    date: "July 13, 2026",
    title: "Save Reliability · Graph View Overhaul",
    highlights: [
      "TSRI-189: Code View / Graph View / DLQuery — Fixed a caching bug where classes and edits made in Code View (or directly in Graph View) could silently fail to persist, or appear in one view (e.g. Graph View) but not others (Entities hierarchy tree, DLQuery). A stale hierarchy cache wasn't being invalidated after saves, and failed saves were silently falling back to a local cache instead of surfacing an error — saving now fails loudly with a clear dialog if it doesn't actually persist.",
    ],
    videoUrl: ""
  },
  {
    version: "1.1.0-beta.13",
    date: "June 29, 2026",
    title: "Label/ID Toggle · JSON-LD Code View",
    highlights: [
      "TSRI-141: Class Hierarchy — Added View → Rendering options to control how entities are displayed across the entire hierarchy. Render by label shows the rdfs:label value (e.g. \"Continuant\", \"Agent\"). Render by name shows the retained ontology ID from the IRI (e.g. BFO_0000001, CCO_0000012) — useful when cross-referencing entities by their coded identifier. Render by annotation property shows the value of any annotation property in the ontology (e.g. skos:prefLabel, dcterms:title), chosen from a submenu. Custom rendering lets you define a template combining label, ID, and annotation values in any format (e.g. {label} ({id})). All modes apply across Classes, Properties, and Individuals tabs simultaneously.",
      "TSRI-173: Code View — JSON-LD is now a first-class tab in OWL/RDF Code View, matching the formats available on export. The VSCode extension also now registers .jsonld as a recognised language.",
    ],
    videoUrl: "",
  },
  {
    version: "1.1.0-beta.12",
    date: "June 25, 2026",
    highlights: [
      "TSRI-166: Graph View — Fixed stale cache showing the wrong ontology after switching projects. Also added local graph view — click Local in the toolbar to see the neighbourhood of the selected node in place of the main canvas.",
      "Submit Issue — Fixed network error when submitting issue reports or feature requests from the desktop app.",
    ],
    videoUrl: "",
  },
  {
    version: "1.1.0-beta.11",
    date: "June 20, 2026",
    highlights: [
      "TSRI-161: Reasoner — Fixed the reasoner appearing stalled on startup. The UI now shows elapsed time and active progress while running. For large ontologies, ELK is recommended over HermiT — it uses the OWL EL profile and computes only the class hierarchy, making it significantly faster on 100 MB+ files where HermiT can take 15–40 minutes.",
    ],
    videoUrl: "",
  },
];
