import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getCachedProviderConfig,
  getProviderConfig,
  normalizeProviderConfig,
  resetProviderConfigCache,
} from "../services/codeAssistantProviderConfig";

function okResponse(body: unknown) {
  return { ok: true, status: 200, json: async () => body };
}

beforeEach(() => {
  resetProviderConfigCache();
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("normalizeProviderConfig", () => {
  it("accepts a managed config with a known provider and a model", () => {
    expect(normalizeProviderConfig({ managed: true, provider: "Claude", model: " claude-sonnet " })).toEqual({
      managed: true,
      provider: "claude",
      model: "claude-sonnet",
    });
  });

  it("treats an unmanaged or unusable config as bring-your-own-key", () => {
    expect(normalizeProviderConfig({ managed: false, provider: "claude", model: "m" })).toEqual({ managed: false });
    expect(normalizeProviderConfig({ managed: true, provider: "mistral", model: "m" })).toEqual({ managed: false });
    expect(normalizeProviderConfig({ managed: true, provider: "openai" })).toEqual({ managed: false });
    expect(normalizeProviderConfig(null)).toEqual({ managed: false });
  });
});

describe("getProviderConfig", () => {
  it("asks the server once per page load and sends the JWT", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse({ managed: true, provider: "openai", model: "gpt-x" }));
    vi.stubGlobal("fetch", fetchMock);

    const [first, second] = await Promise.all([getProviderConfig("http://api", "jwt"), getProviderConfig("http://api", "jwt")]);
    const third = await getProviderConfig("http://api", "other");

    expect(first).toEqual({ managed: true, provider: "openai", model: "gpt-x" });
    expect(second).toBe(first);
    expect(third).toBe(first);
    expect(getCachedProviderConfig()).toBe(first);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api/api/v1/code-assistant/provider-config");
    expect(init.method).toBe("GET");
    expect(init.headers).toEqual({ Authorization: "Bearer jwt" });
  });

  it("caches an unmanaged answer too", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse({ managed: false }));
    vi.stubGlobal("fetch", fetchMock);
    await getProviderConfig("http://api");
    await getProviderConfig("http://api");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1].headers).toEqual({});
  });

  it("falls back to bring-your-own-key on failure and tries again next time", async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({}) })
      .mockResolvedValueOnce(okResponse({ managed: true, provider: "gemini", model: "gemini-pro" }));
    vi.stubGlobal("fetch", fetchMock);

    expect(await getProviderConfig("http://api", "jwt")).toEqual({ managed: false });
    expect(getCachedProviderConfig()).toBeNull();
    expect(await getProviderConfig("http://api", "jwt")).toEqual({ managed: false });
    expect(await getProviderConfig("http://api", "jwt")).toEqual({ managed: true, provider: "gemini", model: "gemini-pro" });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
});
