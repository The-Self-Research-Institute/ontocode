export interface SecretsLike {
    get(key: string): PromiseLike<string | undefined>;
    store(key: string, value: string): PromiseLike<void>;
    delete(key: string): PromiseLike<void>;
}

export interface LegacySettingLike {
    read(): string;
    clear(): PromiseLike<void>;
}

export const ZOTERO_KEY_SECRET = 'ontocode.zoteroApiKey';

export class ZoteroKeyStore {
    private cached = '';

    constructor(private readonly secrets: SecretsLike, private readonly legacy: LegacySettingLike) {}

    async load(): Promise<void> {
        const stored = (await this.secrets.get(ZOTERO_KEY_SECRET)) || '';
        const legacyValue = this.legacy.read().trim();
        if (legacyValue) {
            if (!stored) {
                await this.secrets.store(ZOTERO_KEY_SECRET, legacyValue);
                this.cached = legacyValue;
            } else {
                this.cached = stored;
            }
            await this.legacy.clear();
            return;
        }
        this.cached = stored;
    }

    get(): string {
        return this.cached;
    }

    async set(apiKey: string): Promise<void> {
        const trimmed = apiKey.trim();
        if (!trimmed) {
            await this.clear();
            return;
        }
        await this.secrets.store(ZOTERO_KEY_SECRET, trimmed);
        this.cached = trimmed;
        await this.legacy.clear();
    }

    async clear(): Promise<void> {
        await this.secrets.delete(ZOTERO_KEY_SECRET);
        this.cached = '';
        await this.legacy.clear();
    }
}
