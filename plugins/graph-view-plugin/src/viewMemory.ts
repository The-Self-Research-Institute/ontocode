import { VisualizationType } from './types';

export type OntographLayoutType = 'vertical' | 'horizontal' | 'radial' | 'grid' | 'tree' | 'spring' | 'cluster';

const VISUALIZATION_TYPES: VisualizationType[] = ['vowl', 'ontograph'];
const ONTOGRAPH_LAYOUT_TYPES: OntographLayoutType[] = ['vertical', 'horizontal', 'radial', 'grid', 'tree', 'spring', 'cluster'];

export interface LastGraphView {
  version: 1;
  visualizationType: VisualizationType;
  ontographLayoutType: OntographLayoutType;
  updatedAt: string;
}

export interface VowlDisplayOptions {

  compactNotation: boolean;

  maxLabelChars: number;

  degreeCollapsing: number;

  hideSolitarySubclasses: boolean;

  colorExternals: boolean;

  showSetOperators: boolean;

  mergeEquivalents: boolean;

  showDisjointness: boolean;

  showPropertyLoops: boolean;

  isolateOnSelect: boolean;

  nodeWidthScale: number;

  nodeHeightScale: number;

  labelFontSize: number;
}

export const DEFAULT_VOWL_OPTIONS: VowlDisplayOptions = {
  compactNotation: false,

  maxLabelChars: 36,
  degreeCollapsing: 0,
  hideSolitarySubclasses: false,
  colorExternals: true,
  showSetOperators: true,
  mergeEquivalents: true,
  showDisjointness: true,
  showPropertyLoops: true,
  isolateOnSelect: false,

  nodeWidthScale: 1.0,
  nodeHeightScale: 1.0,
  labelFontSize: 11
};

export interface GraphUiPrefs {
  version: 1;
  showLegend: boolean;
  showPropertyPanel: boolean;

  toolbarHintSessions?: number;

  vowlOptions?: VowlDisplayOptions;
}

const lastViewKey = (projectId: string) => `ontocode.graphView.lastView.${projectId}`;
const UI_PREFS_KEY = 'ontocode.graphView.uiPrefs';

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
