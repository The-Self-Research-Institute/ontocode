import { isDesktop, isRealVSCode } from "../utils/desktop";

export type KeyPersistence = "secure" | "session";

interface KeyBackend {
  kind?: "session" | "external";
  load(): Promise<{ value: string; secure: boolean }>;
  save(key: string): Promise<{ secure: boolean }>;
}

const SESSION_KEY = "ontocode.assistant.sessionKey";
const VSCODE_REPLY_TIMEOUT_MS = 3_000;

let cachedKey = "";
let persistence: KeyPersistence = "session";
let initPromise: Promise<void> | null = null;
let backendOverride: KeyBackend | null = null;
const listeners = new Set<() => void>();

function sessionBackend(): KeyBackend {
  return {
    kind: "session",
    async load() {
      try {
        return { value: sessionStorage.getItem(SESSION_KEY) ?? "", secure: false };
      } catch {
        return { value: "", secure: false };
      }
    },
    async save(key) {
      try {
        if (key) sessionStorage.setItem(SESSION_KEY, key);
        else sessionStorage.removeItem(SESSION_KEY);
      } catch {
        return { secure: false };
      }
      return { secure: false };
    },
  };
}

function desktopBackend(): KeyBackend {
  const api = (window as any).electronAPI;
  return {
    load: async () => {
      const result = await api.getAssistantKey();
      return { value: String(result?.value ?? ""), secure: result?.secure === true };
    },
    save: async (key) => ({ secure: (await api.setAssistantKey(key))?.secure === true }),
  };
}

function vscodeRequest(message: Record<string, unknown>): Promise<{ value: string; secure: boolean }> {
  const requestId = `key-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      window.removeEventListener("message", onMessage);
      reject(new Error("VS Code did not answer the key request"));
    }, VSCODE_REPLY_TIMEOUT_MS);
    function onMessage(event: MessageEvent) {
      const data = event.data;
      if (!data || data.type !== "assistantKeyResult" || data.requestId !== requestId) return;
      clearTimeout(timer);
      window.removeEventListener("message", onMessage);
      resolve({ value: String(data.value ?? ""), secure: data.secure === true });
    }
    window.addEventListener("message", onMessage);
    (window as any).vscode.postMessage({ ...message, requestId });
  });
}

function vscodeBackend(): KeyBackend {
  return {
    load: () => vscodeRequest({ type: "assistantKeyGet" }),
    save: async (key) => ({ secure: (await vscodeRequest({ type: "assistantKeySet", value: key })).secure }),
  };
}

function pickBackend(): KeyBackend {
  if (backendOverride) return backendOverride;
  try {
    if (isDesktop() && typeof (window as any).electronAPI?.getAssistantKey === "function") return desktopBackend();
    if (isRealVSCode()) return vscodeBackend();
  } catch {
    return sessionBackend();
  }
  return sessionBackend();
}

function notify(): void {
  listeners.forEach((listener) => listener());
}

async function persist(backend: KeyBackend, key: string): Promise<void> {
  try {
    const { secure } = await backend.save(key);
    persistence = secure ? "secure" : "session";
    if (!secure && backend.kind !== "session") await sessionBackend().save(key);
  } catch {
    persistence = "session";
    await sessionBackend().save(key);
  }
}

function takeLegacyKey(legacyStorageKey: string): string {
  try {
    const legacy = localStorage.getItem(legacyStorageKey) ?? "";
    if (legacy) localStorage.removeItem(legacyStorageKey);
    return legacy.trim();
  } catch {
    return "";
  }
}

async function loadFrom(backend: KeyBackend): Promise<{ value: string; secure: boolean }> {
  try {
    const loaded = await backend.load();
    if (loaded.value || loaded.secure) return loaded;
  } catch {
    return sessionBackend().load();
  }
  const fallback = await sessionBackend().load();
  return { value: fallback.value, secure: false };
}

export function initApiKeyStore(legacyStorageKey: string): Promise<void> {
  if (initPromise) return initPromise;
  initPromise = (async () => {
    const backend = pickBackend();
    const legacy = takeLegacyKey(legacyStorageKey);
    const loaded = await loadFrom(backend);
    persistence = loaded.secure ? "secure" : "session";
    const key = loaded.value || legacy;
    if (legacy && !loaded.value) await persist(backend, legacy);
    if (!cachedKey) cachedKey = key;
    notify();
  })().catch(() => undefined);
  return initPromise;
}

export function getApiKey(): string {
  return cachedKey;
}

export function setApiKey(key: string): Promise<void> {
  cachedKey = key.trim();
  notify();
  return persist(pickBackend(), cachedKey);
}

export function apiKeyPersistence(): KeyPersistence {
  return persistence;
}

export function onApiKeyChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function resetApiKeyStoreForTests(backend: KeyBackend | null): void {
  cachedKey = "";
  persistence = "session";
  initPromise = null;
  backendOverride = backend;
  listeners.clear();
}
