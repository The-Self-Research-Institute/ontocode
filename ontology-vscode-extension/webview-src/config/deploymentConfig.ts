/**
 * Centralized Deployment Configuration
 * 
 * Single source of truth for deployment type, gateway URLs, and environment URLs.
 * Change the defaults here and all files will pick up the changes dynamically.
 */

// ─── Deployment Types ────────────────────────────────────────────────────────
export type DeploymentType = 'self-hosted' | 'cloud';

// ─── Detect where we're running ─────────────────────────────────────────────
const isViteDevServer = typeof window !== 'undefined' && window.location.port === '3001';
const isLocalhost = typeof window !== 'undefined' &&
    (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1');

// ─── Default URLs ────────────────────────────────────────────────────────────
// Cloud URLs point to ontocodeapi.selfresearch.org (API subdomain)
// Self-hosted uses localhost with specific ports
const DEFAULTS = {
    CLOUD_GATEWAY_URL: 'https://ontocodeapi.selfresearch.org',
    CLOUD_EDITOR_URL: 'https://ontocodeapi.selfresearch.org',
    CLOUD_PLUGIN_URL: 'https://ontocodeapi.selfresearch.org:8087',
    SELF_HOSTED_GATEWAY_URL: isViteDevServer ? '' : 'http://localhost:80',
    SELF_HOSTED_EDITOR_URL: 'http://localhost:80',
    SELF_HOSTED_PLUGIN_URL: 'http://localhost:8087',
    DEFAULT_DEPLOYMENT_TYPE: 'cloud' as DeploymentType,
} as const;

// ─── Read the __ONTOCODE_CONFIG__ injected by extension / vite ───────────────
function getConfig(): Record<string, string> | undefined {
    return (window as any).__ONTOCODE_CONFIG__;
}

// ─── Current Deployment Type ─────────────────────────────────────────────────
export function getStoredDeploymentType(): DeploymentType {
    // Auto-detect based on hostname - overrides localStorage for cloud domain
    if (typeof window !== 'undefined') {
        const hostname = window.location.hostname;
        if (hostname === 'ontocode.selfresearch.org' || hostname === 'ontocodeapi.selfresearch.org') {
            // Force cloud mode when accessing from cloud domain
            return 'cloud';
        }
    }
    
    try {
        const val = localStorage.getItem('deploymentType');
        if (val === 'self-hosted' || val === 'cloud') return val;
    } catch { /* SSR / non-browser */ }
    return DEFAULTS.DEFAULT_DEPLOYMENT_TYPE;
}

// ─── Gateway URL ─────────────────────────────────────────────────────────────
export function getGatewayUrl(type?: DeploymentType): string {
    const deploymentType = type ?? getStoredDeploymentType();
    const config = getConfig();
    if (deploymentType === 'cloud') {
        return config?.CLOUD_GATEWAY_URL || DEFAULTS.CLOUD_GATEWAY_URL;
    }
    return config?.SELF_HOSTED_GATEWAY_URL || DEFAULTS.SELF_HOSTED_GATEWAY_URL;
}

// ─── Editor URL ──────────────────────────────────────────────────────────────
export function getEditorUrl(type?: DeploymentType): string {
    const deploymentType = type ?? getStoredDeploymentType();
    const config = getConfig();
    if (deploymentType === 'cloud') {
        return config?.CLOUD_EDITOR_URL || DEFAULTS.CLOUD_EDITOR_URL;
    }
    return config?.SELF_HOSTED_EDITOR_URL || DEFAULTS.SELF_HOSTED_EDITOR_URL;
}

// ─── Plugin Service URL ─────────────────────────────────────────────────────
export function getPluginUrl(type?: DeploymentType): string {
    const deploymentType = type ?? getStoredDeploymentType();
    const config = getConfig();
    if (deploymentType === 'cloud') {
        return config?.CLOUD_PLUGIN_URL || DEFAULTS.CLOUD_PLUGIN_URL;
    }
    return config?.SELF_HOSTED_PLUGIN_URL || DEFAULTS.SELF_HOSTED_PLUGIN_URL;
}

// ─── Resolve API Base URL (always computes fresh from localStorage) ──────────
export function getApiBaseUrl(): string {
    return getGatewayUrl();
}

// ─── Boolean helpers ─────────────────────────────────────────────────────────
export function isCloudDeployment(type?: DeploymentType): boolean {
    return (type ?? getStoredDeploymentType()) === 'cloud';
}

export function isSelfHostedDeployment(type?: DeploymentType): boolean {
    return (type ?? getStoredDeploymentType()) === 'self-hosted';
}

// Re-export defaults so extension.ts or tests can reference them
export { DEFAULTS as DEPLOYMENT_DEFAULTS };
