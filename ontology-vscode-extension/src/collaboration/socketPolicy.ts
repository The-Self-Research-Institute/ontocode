const RETRY_BASE_MS = 1000;
const RETRY_CAP_MS = 30_000;

export function backoffDelay(attempt: number, random: () => number = Math.random): number {
    const ceiling = Math.min(RETRY_CAP_MS, RETRY_BASE_MS * 2 ** Math.max(0, attempt));
    return Math.round(ceiling / 2 + (random() * ceiling) / 2);
}

export type StompErrorAction = 'refresh' | 'stop' | 'retry';

export function stompErrorAction(code: string | undefined, refreshTried: boolean): StompErrorAction {
    switch ((code || '').trim()) {
        case 'AUTH_MISSING':
            return 'stop';
        case 'AUTH_EXPIRED':
        case 'AUTH_INVALID':
            return refreshTried ? 'stop' : 'refresh';
        default:
            return 'retry';
    }
}

export function createShareDeduper(limit = 100): (n: any) => boolean {
    const seen: string[] = [];
    return (n) => {
        const key = `${n?.id ?? n?.shareId ?? n?.projectId ?? ''}|${n?.sharedWithEmail ?? ''}|${n?.permission ?? ''}|${n?.timestamp ?? ''}`;
        if (seen.includes(key)) {
            return false;
        }
        seen.push(key);
        if (seen.length > limit) {
            seen.shift();
        }
        return true;
    };
}
