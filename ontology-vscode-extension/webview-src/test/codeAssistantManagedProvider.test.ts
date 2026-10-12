import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  requestNextTurn,
  startAssistantConversation,
  type ManagedProviderCall,
  type ToolDefinition,
} from "../services/codeAssistantProviders";
import { AssistantApiError } from "../services/codeAssistantSession";
import * as llmInsights from "../services/LlmInsightsService";

const TOOL: ToolDefinition = {
  name: "read_context",
  description: "test tool",
  parameters: { type: "object", properties: {} },
};

const MANAGED: ManagedProviderCall = { apiBaseUrl: "http://api", token: "user-jwt", sessionId: "sess 1", model: "org-model" };

function response(status: number, body: unknown, headers: Record<string, string> = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    json: async () => body,
  };
}

function stubFetch(...responses: ReturnType<typeof response>[]) {
  const fetchMock = vi.fn();
  responses.forEach((r) => fetchMock.mockResolvedValueOnce(r));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.spyOn(llmInsights, "getStoredApiKey").mockReturnValue("");
  vi.spyOn(llmInsights, "getStoredModel").mockReturnValue("stored-model");
  vi.spyOn(llmInsights, "getStoredProvider").mockReturnValue("gemini");
});

describe("requestNextTurn — managed provider", () => {
  it("posts the provider-native body to the backend with the user's JWT and needs no API key", async () => {
    const fetchMock = stubFetch(response(200, { content: [{ type: "text", text: "managed answer" }], usage: { input_tokens: 5 } }));
    const conversation = await startAssistantConversation("system", "hi", [], "claude");

    const { turn, usage } = await requestNextTurn(conversation, [TOOL], undefined, undefined, MANAGED);

    expect(turn).toEqual({ kind: "answer", text: "managed answer" });
    expect(usage).toMatchObject({ provider: "claude", model: "org-model", inputTokens: 5 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api/api/v1/code-assistant/sessions/sess%201/provider-call");
    expect(init.method).toBe("POST");
    expect(init.headers).toEqual({ "Content-Type": "application/json", Authorization: "Bearer user-jwt" });
    const sent = JSON.parse(init.body);
    expect(Object.keys(sent)).toEqual(["request"]);
    expect(sent.request.model).toBe("org-model");
    expect(sent.request.messages).toEqual([{ role: "user", content: [{ type: "text", text: "hi", cache_control: { type: "ephemeral" } }] }]);
    expect(JSON.stringify(init.headers)).not.toMatch(/x-api-key|anthropic/i);
  });

  it("uses the managed provider's native format rather than the locally stored one", async () => {
    const fetchMock = stubFetch(response(200, { choices: [{ message: { content: "ok" } }] }));
    const conversation = await startAssistantConversation("system", "hi", [], "openai");
    await requestNextTurn(conversation, [TOOL], undefined, undefined, MANAGED);
    const sent = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(sent.request.messages[0]).toEqual({ role: "system", content: "system" });
    expect(sent.request.tools[0].type).toBe("function");
  });

  it("never calls the provider directly in managed mode, even when a key is stored", async () => {
    vi.spyOn(llmInsights, "getStoredApiKey").mockReturnValue("user-key");
    const fetchMock = stubFetch(response(200, { candidates: [{ content: { parts: [{ text: "hi" }] } }] }));
    const conversation = await startAssistantConversation("system", "hi", [], "gemini");
    await requestNextTurn(conversation, [TOOL], undefined, undefined, MANAGED);
    expect(fetchMock.mock.calls[0][0]).toBe("http://api/api/v1/code-assistant/sessions/sess%201/provider-call");
    expect(fetchMock.mock.calls[0][1].body).not.toContain("user-key");
  });

  it("still requires a key on the bring-your-own-key path", async () => {
    const fetchMock = stubFetch();
    const conversation = await startAssistantConversation("system", "hi");
    await expect(requestNextTurn(conversation, [TOOL])).rejects.toThrow("No API key configured");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("turns the backend's rate limit into a RATE_LIMITED error carrying Retry-After", async () => {
    stubFetch(response(429, { ok: false, errorCode: "RATE_LIMITED", message: "Too many provider calls", retryAfterSeconds: 12 }));
    const conversation = await startAssistantConversation("system", "hi", [], "claude");
    const error = await requestNextTurn(conversation, [TOOL], undefined, undefined, MANAGED).catch((e) => e);
    expect(error).toBeInstanceOf(AssistantApiError);
    expect(error.errorCode).toBe("RATE_LIMITED");
    expect(error.retryAfterSeconds).toBe(12);
  });

  it("maps backend failures to the assistant's own error codes", async () => {
    const cases: Array<[number, unknown, string]> = [
      [401, { ok: false, errorCode: "UNAUTHORIZED", message: "Missing or invalid Authorization header" }, "UNAUTHORIZED"],
      [404, { ok: false, errorCode: "SESSION_NOT_FOUND", message: "Session not found or no longer active" }, "SESSION_NOT_FOUND"],
      [423, { error: "locked", errorCode: "PROJECT_RECOVERY_LOCKED", recoveryLocked: true }, "PROJECT_RECOVERY_LOCKED"],
      [403, null, "FORBIDDEN"],
    ];
    for (const [status, body, code] of cases) {
      stubFetch(response(status, body));
      const conversation = await startAssistantConversation("system", "hi", [], "claude");
      const error = await requestNextTurn(conversation, [TOOL], undefined, undefined, MANAGED).catch((e) => e);
      expect(error, `HTTP ${status}`).toBeInstanceOf(AssistantApiError);
      expect(error.errorCode).toBe(code);
    }
  });

  it("reports the provider's own error body as a provider error", async () => {
    stubFetch(response(400, { type: "error", error: { type: "invalid_request_error", message: "bad tool schema" } }));
    const conversation = await startAssistantConversation("system", "hi", [], "claude");
    const error = await requestNextTurn(conversation, [TOOL], undefined, undefined, MANAGED).catch((e) => e);
    expect(error).not.toBeInstanceOf(AssistantApiError);
    expect(error.message).toBe("claude API error (HTTP 400).");
  });

  it("passes the provider's quota message through on an upstream 429 without Retry-After", async () => {
    stubFetch(response(429, { error: { message: "Quota exceeded for this org." } }));
    const conversation = await startAssistantConversation("system", "hi", [], "openai");
    await expect(requestNextTurn(conversation, [TOOL], undefined, undefined, MANAGED)).rejects.toThrow(
      "Rate limit reached: Quota exceeded for this org.",
    );
  });

  it("retries a 503 from the backend and succeeds", async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = stubFetch(
        response(503, { ok: false, errorCode: "PROVIDER_UNAVAILABLE", message: "The AI provider could not be reached" }),
        response(200, { content: [{ type: "text", text: "back" }] }),
      );
      const onRetry = vi.fn();
      const conversation = await startAssistantConversation("system", "hi", [], "claude");
      const pending = requestNextTurn(conversation, [TOOL], undefined, onRetry, MANAGED);
      await vi.advanceTimersByTimeAsync(1500);
      const { turn } = await pending;
      expect(turn).toEqual({ kind: "answer", text: "back" });
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(onRetry).toHaveBeenCalledWith(1, 2, 503);
    } finally {
      vi.useRealTimers();
    }
  });

  it("surfaces PROVIDER_UNAVAILABLE once the retries run out", async () => {
    vi.useFakeTimers();
    try {
      const unavailable = () => response(503, { ok: false, errorCode: "PROVIDER_UNAVAILABLE", message: "The AI provider could not be reached" });
      stubFetch(unavailable(), unavailable(), unavailable());
      const conversation = await startAssistantConversation("system", "hi", [], "claude");
      const pending = requestNextTurn(conversation, [TOOL], undefined, undefined, MANAGED).catch((e) => e);
      await vi.advanceTimersByTimeAsync(5000);
      const error = await pending;
      expect(error).toBeInstanceOf(AssistantApiError);
      expect(error.errorCode).toBe("PROVIDER_UNAVAILABLE");
      expect(error.message).toBe("The AI provider could not be reached");
    } finally {
      vi.useRealTimers();
    }
  });
});
