/**
 * Tracks which local ontology file is open in the desktop app so reopening
 * the same path only focuses the window (VS Code–style) instead of re-importing.
 */

export interface DesktopActiveFile {
  filePath: string;
  fileName: string;
  projectId?: string;
  projectName?: string;
  fileId?: string;
}

const STORAGE_KEY = "ontocode_desktopActiveFile";

export function normalizeDesktopPath(filePath: string): string {
  return filePath.replace(/\\/g, "/").toLowerCase();
}

export function pathsEqual(a?: string | null, b?: string | null): boolean {
  if (!a || !b) return false;
  return normalizeDesktopPath(a) === normalizeDesktopPath(b);
}

export function loadDesktopActiveFile(): DesktopActiveFile | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as DesktopActiveFile;
    if (!parsed?.filePath || !parsed?.fileName) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function saveDesktopActiveFile(info: DesktopActiveFile): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(info));
  } catch {
    /* ignore */
  }
}

export function clearDesktopActiveFile(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
}
