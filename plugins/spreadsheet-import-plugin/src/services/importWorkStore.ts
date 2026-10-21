import type { ParsedSpreadsheet, TransformationRule } from "./spreadsheetImportService";

const DB_NAME = "ontocode-spreadsheet-import";
const STORE = "work";
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

export interface SavedImportFile {
  fileName: string;
  fileType: string;
  file: Blob;
  workbook: ParsedSpreadsheet;
  savedAt: number;
}

export interface SavedImportRules {
  activeSheet: string | null;
  rules: TransformationRule[];
  ruleSet: { id: string; name: string; description?: string | null } | null;
  ruleSetSnapshot: string | null;
  savedAt: number;
}

export type SavedImportWork = SavedImportFile & Omit<SavedImportRules, "savedAt">;

let dbPromise: Promise<IDBDatabase | null> | null = null;

const openDb = (): Promise<IDBDatabase | null> => {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve(null);
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(STORE);
      req.onsuccess = () => {
        const db = req.result;
        db.onversionchange = () => {
          db.close();
          dbPromise = null;
        };
        resolve(db);
      };
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

const fileKey = (key: string) => `${key}|file`;
const rulesKey = (key: string) => `${key}|rules`;

export const saveImportFile = async (key: string, file: SavedImportFile): Promise<void> => {
  await run("readwrite", (s) => s.put(file, fileKey(key)));
};

export const saveImportRules = async (key: string, rules: SavedImportRules): Promise<void> => {
  await run("readwrite", (s) => s.put(rules, rulesKey(key)));
};

export const clearImportWork = async (key: string): Promise<void> => {
  await run("readwrite", (s) => s.delete(fileKey(key)));
  await run("readwrite", (s) => s.delete(rulesKey(key)));
  await run("readwrite", (s) => s.delete(key));
};

export const loadImportWork = async (key: string): Promise<SavedImportWork | null> => {
  const file = await run<SavedImportFile>("readonly", (s) => s.get(fileKey(key)));
  if (!file || !file.file || !file.workbook) return null;
  const rules = await run<SavedImportRules>("readonly", (s) => s.get(rulesKey(key)));
  const savedAt = Math.max(file.savedAt || 0, rules?.savedAt || 0);
  if (Date.now() - savedAt > MAX_AGE_MS) {
    await clearImportWork(key);
    return null;
  }
  return {
    ...file,
    activeSheet: rules?.activeSheet ?? null,
    rules: rules?.rules ?? [],
    ruleSet: rules?.ruleSet ?? null,
    ruleSetSnapshot: rules?.ruleSetSnapshot ?? null,
    savedAt,
  };
};
