const AI_PROVIDER_ORIGINS = [
    'https://api.openai.com',
    'https://api.anthropic.com',
    'https://generativelanguage.googleapis.com'
];
const STRIPE_SCRIPT_ORIGIN = 'https://js.stripe.com';
const STRIPE_CONNECT_ORIGINS = ['https://api.stripe.com', 'https://m.stripe.network'];
const STRIPE_FRAME_ORIGINS = ['https://js.stripe.com', 'https://hooks.stripe.com'];

export function originOf(raw: string): string | null {
    try {
        const parsed = new URL(raw);
        return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.origin : null;
    } catch {
        return null;
    }
}

export function socketOriginOf(origin: string): string {
    return origin.replace(/^https:/, 'wss:').replace(/^http:/, 'ws:');
}

export function buildConnectSources(serviceUrls: string[]): string[] {
    const origins = serviceUrls.map(originOf).filter((origin): origin is string => origin !== null);
    const sockets = origins.map(socketOriginOf);
    return Array.from(new Set([...origins, ...sockets, ...AI_PROVIDER_ORIGINS, ...STRIPE_CONNECT_ORIGINS]));
}

export function buildWebviewCsp(options: { cspSource: string; nonce: string; serviceUrls: string[] }): string {
    const { cspSource, nonce, serviceUrls } = options;
    const connect = ["'self'", ...buildConnectSources(serviceUrls)];
    const imageOrigins = serviceUrls.map(originOf).filter((origin): origin is string => origin !== null);
    return [
        "default-src 'none'",
        `img-src ${[cspSource, 'data:', 'blob:', ...imageOrigins].join(' ')}`,
        `script-src 'nonce-${nonce}' ${cspSource} ${STRIPE_SCRIPT_ORIGIN}`,
        `style-src ${cspSource} 'unsafe-inline'`,
        `font-src ${cspSource} data:`,
        `connect-src ${connect.join(' ')}`,
        `frame-src ${STRIPE_FRAME_ORIGINS.join(' ')}`
    ].join('; ');
}
