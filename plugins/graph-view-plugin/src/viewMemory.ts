import { VisualizationType } from './types';

export type OntographLayoutType = 'vertical' | 'horizontal' | 'radial' | 'grid' | 'tree' | 'spring';

const VISUALIZATION_TYPES: VisualizationType[] = ['force', 'vowl', 'ontograph', 'spatial3d'];
const ONTOGRAPH_LAYOUT_TYPES: OntographLayoutType[] = ['vertical', 'horizontal', 'radial', 'grid', 'tree', 'spring'];

export interface LastGraphView {
  version: 1;
  visualizationType: VisualizationType;
  ontographLayoutType: OntographLayoutType;
  updatedAt: string;
}

export interface VowlDisplayOptions {
  /** Hide "(disjoint)" captions and property-characteristic suffixes. */
  compactNotation: boolean;
  /** Extra cap on label length, on top of the shape-fitted budget. */
  maxLabelChars: number;
  /** Hide nodes whose degree is below this value (0 = show all). */
  degreeCollapsing: number;
  /** Hide classes whose only relationship is a single subClassOf. */
  hideSolitarySubclasses: boolean;
  /** Tint external classes differently from internal ones. */
  colorExternals: boolean;
  /** Show owl:unionOf / intersectionOf / complementOf / oneOf operator nodes. */
  showSetOperators: boolean;
  /** Merge owl:equivalentClass into one double-border node with comma labels. */
  mergeEquivalents: boolean;
  /** Show owl:disjointWith axiom edges. */
  showDisjointness: boolean;
  /** Show property self-loops (domain === range on the same class). */
  showPropertyLoops: boolean;
  /** Multiplier on the base shape width (1 = default size). */
  nodeWidthScale: number;
  /** Multiplier on the base shape height (1 = default size). */
  nodeHeightScale: number;
  /** Label font size in px. Truncation budgets are derived from this, so labels
   *  always stay inside their shape regardless of the chosen size. */
  labelFontSize: number;
}

export const DEFAULT_VOWL_OPTIONS: VowlDisplayOptions = {
  compactNotation: false,
  // Room for "Document, CreativeWork"-style equivalent labels
  maxLabelChars: 36,
  degreeCollapsing: 0,
  hideSolitarySubclasses: false,
  colorExternals: true,
  showSetOperators: true,
  mergeEquivalents: true,
  showDisjointness: true,
  showPropertyLoops: true,
  // Closer to stock WebVOWL proportions (less bulky than 1.15)
  nodeWidthScale: 1.0,
  nodeHeightScale: 1.0,
  labelFontSize: 11
};

export interface GraphUiPrefs {
  version: 1;
  showLegend: boolean;
  showPropertyPanel: boolean;
  /** Sessions since the toolbar restructure shipped; drives the one-time overflow hint. */
  toolbarHintSessions?: number;
  /** Notation/density options — persisted per user (WebVOWL forgets these on reload). */
  vowlOptions?: VowlDisplayOptions;
}

const lastViewKey = (projectId: string) => `ontocode.graphView.lastView.${projectId}`;
const UI_PREFS_KEY = 'ontocode.graphView.uiPrefs';

// localStorage can be unavailable or throw in restricted webviews / privacy modes.
export function safeGetItem(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function safeSetItem(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Persistence is best-effort; degrading to "no memory" is harmless.
  }
}

export function loadLastView(projectId: string): LastGraphView | null {
  const raw = safeGetItem(lastViewKey(projectId));
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    if (
      parsed?.version === 1 &&
      VISUALIZATION_TYPES.includes(parsed.visualizationType) &&
      ONTOGRAPH_LAYOUT_TYPES.includes(parsed.ontographLayoutType)
    ) {
      return parsed as LastGraphView;
    }
  } catch {
    // fall through
  }
  return null;
}

export function saveLastView(
  projectId: string,
  view: { visualizationType: VisualizationType; ontographLayoutType: OntographLayoutType }
): void {
  const payload: LastGraphView = {
    version: 1,
    ...view,
    updatedAt: new Date().toISOString(),
  };
  safeSetItem(lastViewKey(projectId), JSON.stringify(payload));
}

export function loadUiPrefs(): GraphUiPrefs | null {
  const raw = safeGetItem(UI_PREFS_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    if (parsed?.version === 1) return parsed as GraphUiPrefs;
  } catch {
    // fall through
  }
  return null;
}

export function saveUiPrefs(prefs: Omit<GraphUiPrefs, 'version'>): void {
  safeSetItem(UI_PREFS_KEY, JSON.stringify({ version: 1, ...prefs }));
}
