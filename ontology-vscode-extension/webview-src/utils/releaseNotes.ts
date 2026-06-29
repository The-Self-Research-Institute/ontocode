export interface ReleaseNote {
  /** Semantic version this entry documents, e.g. "1.1.0-beta.9". */
  version: string;
  /** Human-readable release date, e.g. "June 2026". */
  date: string;
  /** Short headline for the release. */
  title?: string;
  /** Bullet-point highlights shown to the user. */
  highlights: string[];
  /**
   * Optional walkthrough video. Accepts a direct media URL (.mp4/.webm/.ogg)
   * rendered in an HTML5 player. Leave empty/undefined to hide the video block.
   */
  videoUrl?: string;
  /** Optional poster image shown before the video plays. */
  videoPoster?: string;
}

/**
 * Release notes shown from Help → Version. Add a new entry to the top of this
 * array for each release. To attach a walkthrough video, set `videoUrl` to a
 * direct link to an .mp4/.webm/.ogg file (CDN, GridFS download URL, etc.).
 */
export const RELEASE_NOTES: ReleaseNote[] = [
  {
    version: "1.1.0-beta.13",
    date: "June 29, 2026",
    title: "Label/ID Toggle · JSON-LD Code View · Billing Interval Fix · Release Notes Auto-Open",
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
      "TSRI-166: Graph View — Fixed stale cache showing the wrong ontology after switching projects. Also added Obsidian-style local graph view — click Local in the toolbar to see the neighbourhood of the selected node in place of the main canvas.",
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
