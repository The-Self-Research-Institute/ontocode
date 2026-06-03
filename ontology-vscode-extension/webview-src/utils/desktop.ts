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

/**
 * Desktop: load ontology into OWLAPI memory (Protégé-style) before SPARQL-heavy UI fetch.
 * Returns true when the in-memory model is ready for instant hierarchy/details.
 */
export async function warmOntologyInMemory(
    projectId: string,
    options?: { timeoutMs?: number; onStatus?: (message: string) => void },
): Promise<{
    ready: boolean;
    sparqlFallback: boolean;
    classCount?: number;
    objectPropertyCount?: number;
    dataPropertyCount?: number;
    individualCount?: number;
    annotationPropertyCount?: number;
}> {
    if (!isDesktop()) {
        return { ready: false, sparqlFallback: true };
    }
    const encoded = encodeURIComponent(projectId);
    const timeoutMs = options?.timeoutMs ?? 300_000;
    options?.onStatus?.('Loading ontology into memory (Protégé-style)…');
    try {
        const res: any = await apiClient.post(
            `/api/ontology/warm/${encoded}?timeoutMs=${timeoutMs}`,
            {},
            { timeout: timeoutMs + 15_000 },
        );
        const data = res?.data ?? res;
        const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);
        return {
            ready: !!(data?.ready ?? data?.owlapiReady),
            sparqlFallback: !!(data?.sparqlFallback ?? !data?.ready),
            classCount: num(data?.classCount),
            objectPropertyCount: num(data?.objectPropertyCount),
            dataPropertyCount: num(data?.dataPropertyCount),
            individualCount: num(data?.individualCount),
            annotationPropertyCount: num(data?.annotationPropertyCount),
        };
    } catch (e) {
        console.warn('[desktop] warmOntologyInMemory failed, using SPARQL fallback', e);
        return { ready: false, sparqlFallback: true };
    }
}
