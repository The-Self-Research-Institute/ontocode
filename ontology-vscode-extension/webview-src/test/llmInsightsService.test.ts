import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { resetApiKeyStoreForTests, setApiKey } from "../services/assistantKeyStore";
import {
  generateGraphInsights,
  getProviderModels,
  getStoredMaxResponseTokens,
  getStoredModel,
  getStoredSessionTokenBudget,
  isLikelyPaidOnlyModel,
  refreshAvailableModels,
  setStoredApiKey,
  setStoredMaxResponseTokens,
  setStoredSessionTokenBudget,
  LlmConfigError,
  LlmRequestError,
} from "../services/LlmInsightsService";
import { getApiKey } from "../services/assistantKeyStore";

const req = {
  ontologyName: "Pets",
  nodeCount: 4,
  clusterCount: 1,
  discourseLabel: "focused",
  focusScore: 80,
  topConcepts: ["Dog"],
  clusters: [{ topWords: ["dog", "cat"], size: 2 }],
  gaps: [],
};

let fetchMock: ReturnType<typeof vi.fn>;

function reply(status: number, body: unknown = {}) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  resetApiKeyStoreForTests(null);
  fetchMock = vi.fn(async () => reply(500));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetApiKeyStoreForTests(null);
});

describe("refreshAvailableModels", () => {
  it("orders Gemini flash, then pro, then budget, then other families", async () => {
    fetchMock.mockResolvedValueOnce(
      reply(200, {
        models: [
          { name: "models/gemini-2.5-flash-lite", displayName: "Lite", supportedGenerationMethods: ["generateContent"] },
          { name: "models/gemma-3-small", supportedGenerationMethods: ["generateContent"] },
          { name: "models/gemma-3", supportedGenerationMethods: ["generateContent"] },
          { name: "models/gemini-2.5-pro", displayName: "Pro", supportedGenerationMethods: ["generateContent"] },
          { name: "models/gemini-3.5-flash", displayName: "Flash", supportedGenerationMethods: ["generateContent"] },
          { name: "models/aqa", supportedGenerationMethods: ["embedContent"] },
          { supportedGenerationMethods: ["generateContent"] },
        ],
      }),
    );

    const result = await refreshAvailableModels("gemini", "k");

    expect(result.live).toBe(true);
    expect(result.models.map((m) => m.id)).toEqual([
      "gemini-3.5-flash",
      "gemini-2.5-pro",
      "gemini-2.5-flash-lite",
      "gemma-3",
      "gemma-3-small",
    ]);
    expect(result.models[3].label).toBe("gemma-3");
    expect(getProviderModels("gemini")).toEqual(result.models);
  });

  it("puts budget Claude models after the flagship ones", async () => {
    fetchMock.mockResolvedValueOnce(
      reply(200, { data: [{ id: "claude-mini-1" }, { id: "claude-opus-9", display_name: "Opus 9" }, { id: "" }] }),
    );

    const result = await refreshAvailableModels("claude", "k");

    expect(result.models).toEqual([
      { id: "claude-opus-9", label: "Opus 9" },
      { id: "claude-mini-1", label: "claude-mini-1" },
    ]);
  });

  it("keeps only OpenAI chat models", async () => {
    fetchMock.mockResolvedValueOnce(
      reply(200, {
        data: [
          { id: "gpt-5.6-nano" },
          { id: "gpt-5.6-sol" },
          { id: "gpt-4o-audio-preview" },
          { id: "text-embedding-3-large" },
          { id: "dall-e-3" },
          { id: "gpt-4o-realtime" },
        ],
      }),
    );

    const result = await refreshAvailableModels("openai", "sk");

    expect(fetchMock).toHaveBeenCalledWith("https://api.openai.com/v1/models", {
      headers: { Authorization: "Bearer sk" },
    });
    expect(result.models.map((m) => m.id)).toEqual(["gpt-5.6-sol", "gpt-5.6-nano"]);
  });

  it("falls back to the defaults on an HTTP error, bad JSON or a network failure", async () => {
    fetchMock.mockResolvedValueOnce(reply(401));
    expect(await refreshAvailableModels("claude", "k")).toEqual({ models: getProviderModels("claude"), live: false });

    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: async () => Promise.reject(new Error("bad")) });
    expect((await refreshAvailableModels("openai", "k")).live).toBe(false);

    fetchMock.mockRejectedValueOnce(new TypeError("offline"));
    expect((await refreshAvailableModels("gemini", "k")).live).toBe(false);
    expect(getProviderModels("gemini")[0].id).toBe("gemini-2.5-flash-lite");
  });

  it("ignores a stale model cache", async () => {
    localStorage.setItem(
      "ontocode_llm_models_cache",
      JSON.stringify({ claude: { fetchedAt: Date.now() - 13 * 60 * 60 * 1000, models: [{ id: "old", label: "Old" }] } }),
    );
    expect(getProviderModels("claude")[0].id).toBe("claude-haiku-4-5");
    localStorage.setItem("ontocode_llm_models_cache", "{not json");
    expect(getProviderModels("claude")[0].id).toBe("claude-haiku-4-5");
  });
});

describe("model helpers", () => {
  it("flags Gemini pro models as paid-only", () => {
    expect(isLikelyPaidOnlyModel("gemini", "gemini-2.5-pro")).toBe(true);
    expect(isLikelyPaidOnlyModel("gemini", "gemini-2.5-pro-preview")).toBe(true);
    expect(isLikelyPaidOnlyModel("gemini", "gemini-2.5-flash")).toBe(false);
    expect(isLikelyPaidOnlyModel("claude", "claude-pro")).toBe(false);
  });

  it("clamps and rounds the max response tokens", () => {
    expect(getStoredMaxResponseTokens()).toBe(8192);
    setStoredMaxResponseTokens(100);
    expect(getStoredMaxResponseTokens()).toBe(512);
    setStoredMaxResponseTokens(1_000_000);
    expect(getStoredMaxResponseTokens()).toBe(32768);
    setStoredMaxResponseTokens(2048.4);
    expect(getStoredMaxResponseTokens()).toBe(2048);
    localStorage.setItem("ontocode_llm_max_response_tokens", "1000.5");
    expect(getStoredMaxResponseTokens()).toBe(8192);
  });

  it("clamps and rounds the session token budget", () => {
    expect(getStoredSessionTokenBudget()).toBe(8000);
    setStoredSessionTokenBudget(100);
    expect(getStoredSessionTokenBudget()).toBe(2000);
    setStoredSessionTokenBudget(1_000_000);
    expect(getStoredSessionTokenBudget()).toBe(20000);
    setStoredSessionTokenBudget(5000.4);
    expect(getStoredSessionTokenBudget()).toBe(5000);
    localStorage.setItem("ontocode_llm_session_token_budget", "1000.5");
    expect(getStoredSessionTokenBudget()).toBe(8000);
  });

  it("writes the key through to the key store", async () => {
    setStoredApiKey("  sk-new ");
    expect(getApiKey()).toBe("sk-new");
  });
});

describe("generateGraphInsights", () => {
  it("needs a key", async () => {
    await expect(generateGraphInsights(req)).rejects.toBeInstanceOf(LlmConfigError);
  });

  it("sends the prompt to Gemini and returns the trimmed text", async () => {
    await setApiKey("gk");
    fetchMock.mockResolvedValueOnce(reply(200, { candidates: [{ content: { parts: [{ text: " a" }, { text: "b " }] } }] }));

    expect(await generateGraphInsights(req)).toBe("ab");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent",
    );
    expect(init.headers["x-goog-api-key"]).toBe("gk");
    expect(JSON.parse(init.body).contents[0].parts[0].text).toContain("Ontology: Pets");
  });

  it("maps Gemini status codes to readable errors", async () => {
    await setApiKey("gk");
    fetchMock.mockResolvedValueOnce(reply(403));
    await expect(generateGraphInsights(req)).rejects.toThrow("Invalid or unauthorized API key. Check your Gemini key.");
    fetchMock.mockResolvedValueOnce(reply(429));
    await expect(generateGraphInsights(req)).rejects.toThrow("Rate limit reached");
    fetchMock.mockResolvedValueOnce(reply(502));
    await expect(generateGraphInsights(req)).rejects.toThrow("Gemini API error (HTTP 502).");
    fetchMock.mockResolvedValueOnce(reply(200, { candidates: [] }));
    await expect(generateGraphInsights(req)).rejects.toThrow("empty response");
  });

  it("switches to the next known model when the stored one is retired", async () => {
    await setApiKey("gk");
    fetchMock
      .mockResolvedValueOnce(reply(404))
      .mockResolvedValueOnce(reply(200, { candidates: [{ content: { parts: [{ text: "ok" }] } }] }));

    const text = await generateGraphInsights(req);

    expect(text).toContain('switched to Google Gemini\'s "gemini-2.5-pro"');
    expect(text.endsWith("ok")).toBe(true);
    expect(getStoredModel()).toBe("gemini-2.5-pro");
  });

  it("gives up after every known and live model 404s", async () => {
    await setApiKey("gk");
    fetchMock.mockImplementation(async (url: string) => (url.endsWith("/models") ? reply(500) : reply(404)));

    const error = await generateGraphInsights(req).catch((e) => e);

    expect(error).toBeInstanceOf(LlmRequestError);
    expect(error.message).toContain("None of Google Gemini's known models worked");
  });

  it("calls Claude and maps its errors", async () => {
    localStorage.setItem("ontocode_llm_provider", "claude");
    await setApiKey("ck");
    fetchMock.mockResolvedValueOnce(reply(200, { content: [{ text: "hi" }] }));
    expect(await generateGraphInsights(req)).toBe("hi");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("https://api.anthropic.com/v1/messages");
    expect(init.headers["x-api-key"]).toBe("ck");
    expect(JSON.parse(init.body).model).toBe("claude-sonnet-5");

    fetchMock.mockResolvedValueOnce(reply(401));
    await expect(generateGraphInsights(req)).rejects.toThrow("Check your Claude key.");
    fetchMock.mockRejectedValueOnce(new TypeError("offline"));
    await expect(generateGraphInsights(req)).rejects.toThrow("Could not reach the AI provider");
  });
});
