// utils/billingApi.ts
// Shared wrapper for /api/billing/* calls, used by BillingManagement and
// PlanDetailsModal so the timeout, same-origin fallback, and error wording
// cannot drift between the two billing surfaces.
import apiClient, { ApiError, getBaseUrl } from '../services/apiClient';
import { isDesktop } from './desktop';

// Billing operations are small JSON round trips; surface a timeout after 15s
// instead of inheriting apiClient's 10-minute default and leaving the user
// staring at a spinner.
const BILLING_TIMEOUT_MS = 15_000;

/**
 * Same-origin fallback base for deployments where the configured gateway
 * (e.g. the ontocodeapi subdomain) does not serve the billing routes but the
 * origin that served the page does. Returns null when a fallback makes no
 * sense: desktop (local proxy), VS Code webviews (non-http origin), or when
 * the gateway already is the page origin.
 */
export function billingOriginFallback(): string | null {
    if (typeof window === 'undefined' || isDesktop()) return null;
    const origin = window.location.origin;
    if (!origin || !origin.startsWith('http')) return null;
    if (origin === getBaseUrl()) return null;
    return origin;
}

async function withOriginFallback<T>(run: (config: { timeout: number; baseURL?: string }) => Promise<T>): Promise<T> {
    try {
        return await run({ timeout: BILLING_TIMEOUT_MS });
    } catch (err) {
        const fallback = billingOriginFallback();
        if (fallback && err instanceof ApiError && err.status === 404) {
            return run({ timeout: BILLING_TIMEOUT_MS, baseURL: fallback });
        }
        throw err;
    }
}

export function billingGet<T = any>(url: string, params?: any): Promise<T> {
    return withOriginFallback<T>((config) => apiClient.get<T>(url, params, config));
}

export function billingPost<T = any>(url: string, body?: any): Promise<T> {
    return withOriginFallback<T>((config) => apiClient.post<T>(url, body, config));
}

export function billingErrorMessage(err: any, fallback: string): string {
    if (err?.status === 401 || err?.status === 403) return 'Your session has expired. Please sign in again.';
    return err?.message || fallback;
}
