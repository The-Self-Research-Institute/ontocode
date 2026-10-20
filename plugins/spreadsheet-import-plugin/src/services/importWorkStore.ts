import type { ParsedSpreadsheet, TransformationRule } from "./spreadsheetImportService";

const DB_NAME = "ontocode-spreadsheet-import";
const STORE = "work";
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

export interface SavedImportWork {
  fileName: string;
  fileType: string;
  file: Blob;
  workbook: ParsedSpreadsheet;
  activeSheet: string | null;
  rules: TransformationRule[];
  ruleSet: { id: string; name: string; description?: string | null } | null;
  ruleSetSnapshot: string | null;
  savedAt: number;
}

let dbPromise: Promise<IDBDatabase | null> | null = null;

const openDb = (): Promise<IDBDatabase | null> => {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve(null);
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(STORE);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
      req.onblocked = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  return dbPromise;
};

const run = async <T>(mode: IDBTransactionMode, op: (s: IDBObjectStore) => IDBRequest<T>): Promise<T | null> => {
  const db = await openDb();
  if (!db) return null;
  return new Promise((resolve) => {
    try {
      const req = op(db.transaction(STORE, mode).objectStore(STORE));
      req.onsuccess = () => resolve(req.result ?? null);
      req.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
};

export const workKey = (userId: string | undefined, projectId: string) => `${userId || "anonymous"}|${projectId}`;

export const saveImportWork = async (key: string, work: SavedImportWork): Promise<void> => {
  await run("readwrite", (s) => s.put(work, key));
};

export const clearImportWork = async (key: string): Promise<void> => {
  await run("readwrite", (s) => s.delete(key));
};

export const loadImportWork = async (key: string): Promise<SavedImportWork | null> => {
  const work = await run<SavedImportWork>("readonly", (s) => s.get(key));
  if (!work || !work.file || !work.workbook) return null;
  if (Date.now() - (work.savedAt || 0) > MAX_AGE_MS) {
    await clearImportWork(key);
    return null;
  }
  return work;
};
