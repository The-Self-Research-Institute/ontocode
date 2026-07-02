/**
 * desktop.ts
 * Helpers for the OntoCode Electron desktop build.
 *
 * Desktop mode is detected by the presence of `window.electronAPI`, which is
 * exposed via the Electron preload contextBridge (and therefore reliably visible
 * in the renderer, unlike plain `window.__DESKTOP_*` globals set in the isolated
 * preload context).
 *
 * In desktop mode the app has no signup/signin and no plan/pricing UI: the user
 * identity (name, email, plan, validity) comes entirely from a signed license
 * file that the web app issues. The Electron main process verifies the signature
 * and exposes the verified payload via `electronAPI.getLicense()`.
 */

import apiClient from '../services/apiClient';

export interface DesktopLicense {
    version?: number;
    plan?: string;            // FREE | PRO | ENTERPRISE
    email?: string;
    name?: string;
    issuedAt?: string;
    expiresAt?: string | null; // null/absent => perpetual (FREE)
    features?: Record<string, unknown>;
    /** Set by the Electron main process: true if the signature verified. */
    signatureValid?: boolean;
}

/** True when running inside the Electron desktop shell. */
export function isDesktop(): boolean {
    return typeof window !== 'undefined' && !!(window as any).electronAPI;
}

/** Read the (already signature-verified) license from the Electron main process. */
export async function getDesktopLicense(): Promise<DesktopLicense | null> {
    try {
        const api = (window as any).electronAPI;
        if (api?.getLicense) {
            return await api.getLicense();
        }
    } catch (e) {
        console.warn('[desktop] getLicense failed', e);
    }
    return null;
}

/** Normalised plan name (uppercase). Defaults to FREE. */
export function licensePlan(license: DesktopLicense | null | undefined): string {
    return (license?.plan || 'FREE').toUpperCase();
}

/**
 * True when a (paid) license has an expiry date in the past. FREE/perpetual
 * licenses (no expiresAt) never expire.
 */
export function isLicenseExpired(license: DesktopLicense | null | undefined): boolean {
    if (!license || !license.expiresAt) return false;
    const exp = new Date(license.expiresAt).getTime();
    if (Number.isNaN(exp)) return false;
    return Date.now() >= exp;
}

/** Fired after a successful license import so the app can re-derive the user. */
export const DESKTOP_LICENSE_UPDATED_EVENT = 'desktop-license-updated';

export async function warmOntologyInMemory(
    projectId: string,
    options?: { timeoutMs?: number; onStatus?: (message: string) => void },
): Promise<{
    ready: boolean;
    sparqlFallback: boolean;
    pending?: boolean;
    classCount?: number;
    objectPropertyCount?: number;
    dataPropertyCount?: number;
    individualCount?: number;
    annotationPropertyCount?: number;
}> {
    const encoded = encodeURIComponent(projectId);
    const timeoutMs = options?.timeoutMs ?? 300_000;
    options?.onStatus?.('Opening ontology (fast path)…');
    try {
        const res: any = await apiClient.post(
            `/api/ontology/warm/${encoded}?timeoutMs=${timeoutMs}`,
            {},
            { timeout: timeoutMs + 15_000 },
        );
        const data = res?.data ?? res;
        const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);
        const ready = !!(data?.ready ?? data?.owlapiReady);
        const sparqlFallback = data?.sparqlFallback === true;
        const pending = data?.pending === true || (!ready && !sparqlFallback);
        return {
            ready,
            sparqlFallback,
            pending,
            classCount: num(data?.classCount),
            objectPropertyCount: num(data?.objectPropertyCount),
            dataPropertyCount: num(data?.dataPropertyCount),
            individualCount: num(data?.individualCount),
            annotationPropertyCount: num(data?.annotationPropertyCount),
        };
    } catch (e) {
        console.warn('[desktop] warmOntologyInMemory failed — will retry via cache-status', e);
        return { ready: false, sparqlFallback: false, pending: true };
    }
}

/** Poll cache-status until the OWLAPI in-memory model is ready (desktop owlapi-first). */
export async function waitForDesktopOwlApiReady(
    projectId: string,
    options?: { timeoutMs?: number; pollMs?: number; signal?: AbortSignal },
): Promise<boolean> {
    if (!isDesktop()) return true;
    const timeoutMs = options?.timeoutMs ?? 120_000;
    const pollMs = options?.pollMs ?? 1500;
    const encoded = encodeURIComponent(projectId);
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
        if (options?.signal?.aborted) return false;
        try {
            const cs: any = await apiClient.get(`/api/ontology/cache-status/${encoded}`);
            if (cs?.owlapiReady ?? cs?.data?.owlapiReady) return true;
        } catch {
            /* retry */
        }
        await new Promise((r) => setTimeout(r, pollMs));
    }
    return false;
}

export function isOwlApiWarmingResponse(res: unknown): boolean {
    const r = res as Record<string, unknown> | null | undefined;
    return !!(r?.warming || (r?.data as Record<string, unknown> | undefined)?.warming);
}

/** True when the backend is temporarily unavailable (OWLAPI warming, lazy Fuseki, overload). */
export function isOwlApiRetryableError(e: unknown): boolean {
    const err = e as { status?: number; response?: { status?: number } } | null | undefined;
    const status = err?.status ?? err?.response?.status;
    return status === 503 || status === 502 || status === 504;
}

/**
 * GET with retries for desktop entity lists while OWLAPI is warming or Fuseki is deferred.
 * Returns null when aborted or all attempts exhausted without data.
 */
export async function getOntologyListWithRetry<T = unknown>(
    url: string,
    options?: { signal?: AbortSignal; maxAttempts?: number; delayMs?: number },
): Promise<T | null> {
    const maxAttempts = options?.maxAttempts ?? 20;
    const delayMs = options?.delayMs ?? 2000;
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
        if (options?.signal?.aborted) return null;
        try {
            const res = await apiClient.get<T>(url, undefined, { signal: options?.signal });
            if (isOwlApiWarmingResponse(res)) {
                if (attempt < maxAttempts - 1) {
                    await new Promise((r) => setTimeout(r, delayMs));
                    continue;
                }
                return null;
            }
            return res;
        } catch (e: any) {
            if (e?.name === 'AbortError' || e?.code === 'ERR_CANCELED') throw e;
            if (isOwlApiRetryableError(e) && attempt < maxAttempts - 1) {
                await new Promise((r) => setTimeout(r, delayMs));
                continue;
            }
            throw e;
        }
    }
    return null;
}

/**
 * Start Fuseki (if deferred) and sync the ontology for SPARQL/graph features.
 * Core editing uses OWLAPI only — this is only needed for Fuseki-dependent tabs.
 */
export async function ensureDesktopFusekiSync(projectId: string): Promise<{ synced: boolean; error?: string }> {
    if (!isDesktop()) return { synced: true };
    try {
        const api = (window as any).electronAPI;
        if (api?.ensureFuseki) {
            await api.ensureFuseki();
        }
        const encoded = encodeURIComponent(projectId);
        const res: any = await apiClient.post(`/api/desktop/sync-fuseki/${encoded}`, {});
        const data = res?.data ?? res;
        return { synced: !!data?.synced, error: data?.error };
    } catch (e: any) {
        console.warn('[desktop] ensureDesktopFusekiSync failed', e);
        return { synced: false, error: e?.message || 'Fuseki sync failed' };
    }
}

/**
 * Fire-and-forget: start Fuseki if needed and queue a background triple-store sync.
 * Does not block the editor — OWLAPI remains the source of truth for open/edit.
 */
export function scheduleSilentDesktopFusekiSync(projectId: string): void {
    if (!isDesktop() || !projectId) return;
    void (async () => {
        try {
            const api = (window as any).electronAPI;
            if (api?.ensureFuseki) {
                await api.ensureFuseki();
            }
            const encoded = encodeURIComponent(projectId);
            await apiClient.post(`/api/desktop/schedule-fuseki-sync/${encoded}`, {});
        } catch (e) {
            console.debug('[desktop] silent Fuseki sync schedule failed (will retry on mutation)', e);
        }
    })();
}
