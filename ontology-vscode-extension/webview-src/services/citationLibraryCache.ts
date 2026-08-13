

const DB_NAME = "ontocodeCitationStore";
const DB_VERSION = 1;
const STORE = "zoteroLibrary";

export interface CachedCitationBlob {
  items: unknown[];
  totalResults: number;
  updatedAt: number;
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onerror = () => reject(req.error);
    req.onsuccess = () => resolve(req.result);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE);
      }
    };
  });
}

const CACHE_KEY = "default";

export async function loadCitationLibraryCache(): Promise<CachedCitationBlob | null> {
  try {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readonly");
      const store = tx.objectStore(STORE);
      const req = store.get(CACHE_KEY);
      req.onerror = () => reject(req.error);
      req.onsuccess = () => {
        const v = req.result as CachedCitationBlob | undefined;
        resolve(v?.items?.length ? v : null);
      };
    });
  } catch {
    return null;
  }
}

export async function saveCitationLibraryCache(payload: CachedCitationBlob): Promise<void> {
  try {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      const store = tx.objectStore(STORE);
      const req = store.put(payload, CACHE_KEY);
      req.onerror = () => reject(req.error);
      req.onsuccess = () => resolve();
    });
  } catch {
    /* ignore persistence failures — search still works in-memory */
  }
}
