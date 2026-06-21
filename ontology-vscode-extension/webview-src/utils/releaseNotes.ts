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
    version: "1.1.0-beta.11",
    date: "June 20, 2026",
    highlights: [
      "TSRI-161: Reasoner now shows elapsed time and active-progress feedback while running — large ontologies (100MB+) can take 15–40 minutes with HermiT; the UI now makes this clear instead of appearing stalled.",
    ],
    videoUrl: "",
  },
];
