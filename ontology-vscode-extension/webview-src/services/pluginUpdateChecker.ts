

import { pluginLoader } from "./pluginLoader";
import { getApiBaseUrl } from "../config/deploymentConfig";

const CACHE_KEY = "ontocode:pluginUpdateCheck";
const CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24h

export interface PluginUpdateInfo {
  pluginId: string;
  name: string;
  installedVersion: string;
  latestVersion: string;
}

interface CachedCheck {
  checkedAt: number;
  updates: PluginUpdateInfo[];
}

function compareSemver(a: string, b: string): number {
  const ap = a.split(".").map((n) => parseInt(n, 10) || 0);
  const bp = b.split(".").map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(ap.length, bp.length); i++) {
    const av = ap[i] || 0;
    const bv = bp[i] || 0;
    if (av > bv) return 1;
    if (av < bv) return -1;
  }
  return 0;
}

function loadCache(): CachedCheck | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as CachedCheck;
    if (!parsed || typeof parsed.checkedAt !== "number") return null;
    return parsed;
  } catch {
    return null;
  }
}

function saveCache(updates: PluginUpdateInfo[]): void {
  try {
    const payload: CachedCheck = { checkedAt: Date.now(), updates };
    localStorage.setItem(CACHE_KEY, JSON.stringify(payload));
  } catch {
    /* ignore storage errors */
  }
}

export async function checkForPluginUpdates(force = false): Promise<PluginUpdateInfo[]> {
  if (!force) {
    const cached = loadCache();
    if (cached && Date.now() - cached.checkedAt < CACHE_TTL_MS) {
      return cached.updates;
    }
  }

  const installed = pluginLoader.getInstalledPlugins();
  if (installed.length === 0) {
    saveCache([]);
    return [];
  }

  const token = localStorage.getItem("authToken");
  const headers: HeadersInit = { "Content-Type": "application/json" };
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const apiBaseUrl = getApiBaseUrl();
  const updates: PluginUpdateInfo[] = [];

  await Promise.all(
    installed.map(async (plugin) => {
      try {
        const res = await fetch(`${apiBaseUrl}/api/plugins/${plugin.id}`, { headers });
        if (!res.ok) return;
        const data = await res.json();
        const latest: string | undefined = data?.latestVersion || data?.manifest?.version;
        const installedVersion = plugin.manifest?.version;
        if (latest && installedVersion && compareSemver(latest, installedVersion) > 0) {
          updates.push({
            pluginId: plugin.id,
            name: data?.name || plugin.manifest?.displayName || plugin.id,
            installedVersion,
            latestVersion: latest,
          });
        }
      } catch {
        /* ignore single-plugin errors */
      }
    }),
  );

  saveCache(updates);
  return updates;
}

export function clearPluginUpdateCache(): void {
  try {
    localStorage.removeItem(CACHE_KEY);
  } catch {
    /* ignore */
  }
}
