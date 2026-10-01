import type { LlmProvider } from "./LlmInsightsService";

export type ProviderConfig = { managed: false } | { managed: true; provider: LlmProvider; model: string };

export const UNMANAGED_PROVIDER_CONFIG: ProviderConfig = { managed: false };

const KNOWN_PROVIDERS: ReadonlySet<string> = new Set<LlmProvider>(["claude", "openai", "gemini"]);

export const PROVIDER_CONFIG_PATH = "/api/v1/code-assistant/provider-config";

let cached: ProviderConfig | null = null;
let pending: Promise<ProviderConfig> | null = null;

export function normalizeProviderConfig(raw: unknown): ProviderConfig {
  if (!raw || typeof raw !== "object") return UNMANAGED_PROVIDER_CONFIG;
  const rec = raw as Record<string, unknown>;
  if (rec.managed !== true) return UNMANAGED_PROVIDER_CONFIG;
  const provider = typeof rec.provider === "string" ? rec.provider.trim().toLowerCase() : "";
  const model = typeof rec.model === "string" ? rec.model.trim() : "";
  if (!KNOWN_PROVIDERS.has(provider) || !model) {
    console.warn("[Ask AI] Ignoring a managed provider config the assistant can't use", rec);
    return UNMANAGED_PROVIDER_CONFIG;
  }
  return { managed: true, provider: provider as LlmProvider, model };
}

export function getCachedProviderConfig(): ProviderConfig | null {
  return cached;
}

async function loadProviderConfig(apiBaseUrl: string, token: string | undefined): Promise<ProviderConfig> {
  const res = await fetch(`${apiBaseUrl}${PROVIDER_CONFIG_PATH}`, {
    method: "GET",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`Provider config request failed (HTTP ${res.status}).`);
  return normalizeProviderConfig(await res.json());
}

export function getProviderConfig(apiBaseUrl: string, token?: string): Promise<ProviderConfig> {
  if (cached) return Promise.resolve(cached);
  if (!pending) {
    const request = loadProviderConfig(apiBaseUrl, token).then(
      (config) => {
        cached = config;
        return config;
      },
      () => UNMANAGED_PROVIDER_CONFIG,
    );
    pending = request;
    void request.finally(() => {
      if (pending === request) pending = null;
    });
  }
  return pending;
}

export function resetProviderConfigCache(): void {
  cached = null;
  pending = null;
}
