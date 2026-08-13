

export type DeploymentType = 'self-hosted' | 'cloud';

declare const __ONTOCODE_CONFIG__:
    | Record<string, string>
    | undefined;

const isViteDevServer = typeof window !== 'undefined' && window.location.port === '3001';
const isLocalhost = typeof window !== 'undefined' &&
    (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1');

const DEFAULTS = {
    CLOUD_GATEWAY_URL: 'https://ontocodeapi.selfresearch.org',
    CLOUD_EDITOR_URL: 'https://ontocodeapi.selfresearch.org',
    CLOUD_PLUGIN_URL: 'https://ontocodeapi.selfresearch.org:8087',
    SELF_HOSTED_GATEWAY_URL: 'http://localhost:80',
    SELF_HOSTED_EDITOR_URL: 'http://localhost:80',
    SELF_HOSTED_PLUGIN_URL: 'http://localhost:8087',
    DEFAULT_DEPLOYMENT_TYPE: 'cloud' as DeploymentType,
} as const;

function getConfig(): Record<string, string> | undefined {
    if (typeof __ONTOCODE_CONFIG__ !== 'undefined' && __ONTOCODE_CONFIG__) {
        return __ONTOCODE_CONFIG__;
    }
    return (window as any).__ONTOCODE_CONFIG__;
}

export function getStoredDeploymentType(): DeploymentType {

    if (typeof window !== 'undefined') {
        const hostname = window.location.hostname;
        if (hostname === 'ontocode.selfresearch.org' || hostname === 'ontocodeapi.selfresearch.org') {

            return 'cloud';
        }
    }

    try {
        const val = localStorage.getItem('deploymentType');
        if (val === 'self-hosted' || val === 'cloud') return val;
    } catch { /* SSR / non-browser */ }
    return DEFAULTS.DEFAULT_DEPLOYMENT_TYPE;
}

export function getGatewayUrl(type?: DeploymentType): string {

    if (typeof window !== 'undefined' && (window as any).__DESKTOP_API_URL__) {
        return (window as any).__DESKTOP_API_URL__;
    }
    const deploymentType = type ?? getStoredDeploymentType();
    const config = getConfig();
    if (deploymentType === 'cloud') {

        if (config?.CLOUD_GATEWAY_URL) return config.CLOUD_GATEWAY_URL;

        if (typeof window !== 'undefined') {
            const proto = window.location.protocol;
            const hostname = window.location.hostname;
           const isOfficialCloudHost =
    hostname === 'ontocode.selfresearch.org' || 
    hostname === 'ontocodeapi.selfresearch.org' ||
    hostname.includes('ontocodedev');
            if ((proto === 'http:' || proto === 'https:') && !isLocalhost && !isOfficialCloudHost) {
                return proto + '//' + hostname;
            }
        }
        return DEFAULTS.CLOUD_GATEWAY_URL;
    }
    return config?.SELF_HOSTED_GATEWAY_URL || DEFAULTS.SELF_HOSTED_GATEWAY_URL;
}

export function getEditorUrl(type?: DeploymentType): string {
    const deploymentType = type ?? getStoredDeploymentType();
    const config = getConfig();
    if (deploymentType === 'cloud') {
        return config?.CLOUD_EDITOR_URL || DEFAULTS.CLOUD_EDITOR_URL;
    }
    return config?.SELF_HOSTED_EDITOR_URL || DEFAULTS.SELF_HOSTED_EDITOR_URL;
}

export function getPluginUrl(type?: DeploymentType): string {
    const deploymentType = type ?? getStoredDeploymentType();
    const config = getConfig();
    if (deploymentType === 'cloud') {
        return config?.CLOUD_PLUGIN_URL || DEFAULTS.CLOUD_PLUGIN_URL;
    }
    return config?.SELF_HOSTED_PLUGIN_URL || DEFAULTS.SELF_HOSTED_PLUGIN_URL;
}

export function getApiBaseUrl(): string {
    return getGatewayUrl();
}

export function getCloudGatewayUrl(): string {
    return DEFAULTS.CLOUD_GATEWAY_URL;
}

export function isCloudDeployment(type?: DeploymentType): boolean {
    return (type ?? getStoredDeploymentType()) === 'cloud';
}

export function isSelfHostedDeployment(type?: DeploymentType): boolean {
    return (type ?? getStoredDeploymentType()) === 'self-hosted';
}

export { DEFAULTS as DEPLOYMENT_DEFAULTS };
