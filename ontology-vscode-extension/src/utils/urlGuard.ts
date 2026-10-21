const EXTERNAL_PROTOCOLS = ['https:', 'http:', 'mailto:'];
const API_PREFIX = '/api/';

function parseUrl(raw: unknown, base?: string): URL | null {
    if (typeof raw !== 'string' || raw.length === 0) {
        return null;
    }
    try {
        return base === undefined ? new URL(raw) : new URL(raw, base);
    } catch {
        return null;
    }
}

export function isSafeExternalUrl(raw: unknown): raw is string {
    const parsed = parseUrl(raw);
    return parsed !== null && EXTERNAL_PROTOCOLS.includes(parsed.protocol);
}

export function isValidBaseUrl(raw: unknown): boolean {
    const parsed = parseUrl(raw);
    return parsed !== null && (parsed.protocol === 'https:' || parsed.protocol === 'http:') && !parsed.username && !parsed.password;
}

export function isGatewayApiPath(path: unknown): path is string {
    if (typeof path !== 'string' || !path.startsWith(API_PREFIX)) {
        return false;
    }
    if (path.startsWith('//') || path.includes('\\') || /[\u0000-\u001f]/.test(path)) {
        return false;
    }
    const pathOnly = path.split(/[?#]/)[0];
    let decoded: string;
    try {
        decoded = decodeURIComponent(pathOnly);
    } catch {
        return false;
    }
    return !decoded.split('/').includes('..') && !pathOnly.split('/').includes('..') && !decoded.includes('://');
}

export function resolveGatewayApiUrl(path: unknown, gatewayUrl: string): string | null {
    if (!isGatewayApiPath(path)) {
        return null;
    }
    const gateway = parseUrl(gatewayUrl);
    if (!gateway) {
        return null;
    }
    const resolved = parseUrl(gateway.origin + path);
    if (!resolved || resolved.origin !== gateway.origin) {
        return null;
    }
    return `${gatewayUrl.replace(/\/+$/, '')}${path}`;
}

export function resolveAllowedUrl(raw: unknown, gatewayUrl: string, allowedBases: string[]): string | null {
    if (typeof raw !== 'string' || raw.length === 0 || /[\u0000-\u001f\\]/.test(raw)) {
        return null;
    }
    const gateway = parseUrl(gatewayUrl);
    if (!gateway) {
        return null;
    }
    const resolved = parseUrl(raw, gateway.origin + '/');
    if (!resolved || (resolved.protocol !== 'https:' && resolved.protocol !== 'http:')) {
        return null;
    }
    const allowedOrigins = allowedBases.map((base) => parseUrl(base)?.origin).filter((origin): origin is string => !!origin);
    return allowedOrigins.includes(resolved.origin) ? resolved.toString() : null;
}

const PUBLIC_EXACT_PATHS = [
    '/api/auth/login',
    '/api/auth/signup',
    '/api/auth/verify',
    '/api/auth/resend-verification',
    '/api/auth/forgot-password',
    '/api/auth/reset-password'
];
const PUBLIC_PATH_PREFIXES = ['/api/invitations/details/', '/api/invitations/request-resend/'];

export function isPublicGatewayPath(path: unknown): boolean {
    if (!isGatewayApiPath(path)) {
        return false;
    }
    const pathOnly = path.split(/[?#]/)[0].replace(/\/+$/, '');
    return PUBLIC_EXACT_PATHS.includes(pathOnly) || PUBLIC_PATH_PREFIXES.some((prefix) => pathOnly.startsWith(prefix));
}

export class PickedFileRegistry {
    private readonly picked = new Set<string>();

    add(uriString: string): void {
        this.picked.add(uriString);
    }

    has(uriString: unknown): boolean {
        return typeof uriString === 'string' && this.picked.has(uriString);
    }

    clear(): void {
        this.picked.clear();
    }
}
