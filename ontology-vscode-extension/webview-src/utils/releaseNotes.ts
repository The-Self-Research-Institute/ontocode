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
    version: "1.1.0-beta.10",
    date: "June 19, 2026",
    title: "Editing reliability, graph analytics & OWL display improvements",
    highlights: [
      "Saving changes in Draft mode now works reliably — fixed a bug where edits were silently rejected instead of being stored.",
      "Draft / Live mode now remembers your choice across page refreshes — no more unexpectedly reverting to Draft after a reload.",
      "Switching to Live mode now shows a clear error if pending drafts couldn't be applied, instead of falsely reporting success.",
      "Graph Analytics panel rebuilt — richer metrics, cleaner layout, and faster rendering for large ontologies.",
      "Property DisjointWith and EquivalentProperty now show in both directions (e.g. if A disjoint B, B also shows disjoint A) — matching standard Protégé behavior.",
      "Parent class EquivalentTo restrictions now correctly appear in the Anonymous Ancestor section of all child classes.",
      "Ontologies using URN-based identifiers no longer cause background metadata errors.",
      "Reasoner statistics now load correctly after classification — previously showed a 404 error in the background.",
      "TSRI-161: Reasoner now shows elapsed time and active-progress feedback while running — large ontologies (100MB+) can take 15–40 minutes with HermiT; the UI now makes this clear instead of appearing stalled.",
      "ELK reasoner option is now visible in the Reasoner panel (was missing from the dropdown).",
    ],
    videoUrl: "",
  },
];
