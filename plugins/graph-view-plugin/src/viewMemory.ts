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

export interface GraphUiPrefs {
  version: 1;
  showLegend: boolean;
  showPropertyPanel: boolean;
  /** Sessions since the toolbar restructure shipped; drives the one-time overflow hint. */
  toolbarHintSessions?: number;
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
