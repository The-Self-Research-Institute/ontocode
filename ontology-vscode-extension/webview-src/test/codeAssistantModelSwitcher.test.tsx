import React, { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CodeAssistantModelSwitcher } from "../components/CodeAssistantModelSwitcher";
import { getApiKey, resetApiKeyStoreForTests, setApiKey } from "../services/assistantKeyStore";
import { getStoredModel, getStoredProvider, getStoredRetrievalAttempts, getStoredSessionTokenBudget } from "../services/LlmInsightsService";
import { buttonByText, click, flush, mount, typeInto } from "./hookHarness";

const geminiModels = {
  models: [
    { name: "models/gemini-3.5-flash", displayName: "Gemini 3.5 Flash", supportedGenerationMethods: ["generateContent"] },
    { name: "models/gemini-2.5-pro", displayName: "Gemini 2.5 Pro", supportedGenerationMethods: ["generateContent"] },
    { name: "models/text-embedding-004", displayName: "Embedding", supportedGenerationMethods: ["embedContent"] },
  ],
};

const claudeModels = {
  data: [
    { id: "claude-haiku-4-5", display_name: "Haiku live" },
    { id: "claude-opus-4-8", display_name: "Opus live" },
  ],
};

let fetchMock: ReturnType<typeof vi.fn>;
let ui: ReturnType<typeof mount>;
let onChange: ReturnType<typeof vi.fn>;

function respondWith(body: unknown, ok = true) {
  fetchMock.mockImplementation(async () => ({ ok, status: ok ? 200 : 500, json: async () => body }));
}

async function renderSwitcher() {
  ui.render(<CodeAssistantModelSwitcher onChange={onChange} />);
  await flush();
}

function trigger() {
  return ui.container.querySelector('button[title="Switch provider or model"]') as HTMLButtonElement;
}

async function open() {
  click(trigger());
  await flush();
}

function keyInput() {
  return ui.container.querySelector('input[type="password"]') as HTMLInputElement | null;
}

async function storeKey(provider: string, key: string, model?: string) {
  localStorage.setItem("ontocode_llm_provider", provider);
  if (model) localStorage.setItem("ontocode_llm_model", model);
  await setApiKey(key);
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  resetApiKeyStoreForTests(null);
  fetchMock = vi.fn();
  respondWith({}, false);
  vi.stubGlobal("fetch", fetchMock);
  onChange = vi.fn();
  ui = mount();
});

afterEach(() => {
  ui.unmount();
  vi.unstubAllGlobals();
  resetApiKeyStoreForTests(null);
});

describe("CodeAssistantModelSwitcher", () => {
  it("asks for a key, verifies it against the provider and picks the first live model", async () => {
    respondWith(geminiModels);
    await renderSwitcher();
    expect(trigger().textContent).toContain("Add API key");

    await open();
    expect(ui.container.textContent).toContain("Select a model");
    const input = keyInput()!;
    expect(input.placeholder).toBe("Paste your Google Gemini API key");
    const save = buttonByText(ui.container, "Save key")!;
    expect(save.disabled).toBe(true);
    expect(ui.container.querySelector("a")?.getAttribute("href")).toBe("https://ai.google.dev/pricing");

    typeInto(input, "  sk-gem  ");
    expect(save.disabled).toBe(false);
    click(save);
    await flush();

    expect(fetchMock).toHaveBeenCalledWith(
      "https://generativelanguage.googleapis.com/v1beta/models",
      expect.objectContaining({ headers: { "x-goog-api-key": "sk-gem" } }),
    );
    expect(getApiKey()).toBe("sk-gem");
    expect(getStoredProvider()).toBe("gemini");
    expect(getStoredModel()).toBe("gemini-3.5-flash");
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(keyInput()).toBeNull();
    const labels = Array.from(ui.container.querySelectorAll("button span")).map((s) => s.textContent);
    expect(labels).toContain("Gemini 3.5 Flash");
    expect(labels).toContain("Gemini 2.5 Pro");
    expect(labels).not.toContain("Embedding");
    expect(trigger().textContent).toContain("Google Gemini · Gemini 3.5 Flash");
  });

  it("saves the key with the default models when the provider can't be reached", async () => {
    await renderSwitcher();
    await open();
    typeInto(keyInput()!, "sk-offline");
    await act(async () => {
      keyInput()!.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
    });
    await flush();

    expect(getApiKey()).toBe("sk-offline");
    expect(getStoredModel()).toBe("gemini-2.5-flash-lite");
    expect(ui.container.textContent).toContain("Could not verify the key with the provider — saved anyway.");
    expect(ui.container.textContent).toContain("Gemini 2.5 Flash Lite (fast, free)");
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it("does nothing when saving a blank key", async () => {
    await renderSwitcher();
    await open();
    typeInto(keyInput()!, "   ");
    await act(async () => {
      keyInput()!.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
    });
    await flush();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("refreshes the model list with the stored key and lets the user pick a model", async () => {
    await storeKey("claude", "sk-ant");
    respondWith(claudeModels);
    await renderSwitcher();
    expect(trigger().textContent).toContain("Anthropic Claude · Claude Sonnet 5 (balanced)");

    await open();
    expect(fetchMock).toHaveBeenCalledWith(
      "https://api.anthropic.com/v1/models",
      expect.objectContaining({ headers: expect.objectContaining({ "x-api-key": "sk-ant" }) }),
    );
    const opus = buttonByText(ui.container, "Opus live")!;
    expect(buttonByText(ui.container, "Haiku live")).toBeDefined();
    expect(getStoredModel()).toBe("claude-sonnet-5");

    click(opus);
    await flush();

    expect(getStoredModel()).toBe("claude-opus-4-8");
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(ui.container.textContent).not.toContain("Select a model");
    expect(trigger().textContent).toContain("Anthropic Claude · Opus live");
  });

  it("marks the current model with a check", async () => {
    await storeKey("claude", "sk-ant", "claude-haiku-4-5");
    await renderSwitcher();
    await open();
    const haiku = buttonByText(ui.container, "Claude Haiku 4.5")!;
    const opus = buttonByText(ui.container, "Claude Opus 4.8")!;
    expect(haiku.querySelector("svg")).not.toBeNull();
    expect(opus.querySelector("svg")).toBeNull();
  });

  it("warns when the refresh can't reach the provider and keeps the defaults", async () => {
    await storeKey("openai", "sk-oai");
    await renderSwitcher();
    await open();
    expect(ui.container.textContent).toContain("Could not reach the provider — showing default models.");
    expect(buttonByText(ui.container, "GPT-5.6 Terra (balanced)")).toBeDefined();
  });

  it("moves off a paid-only Gemini model to the first live one", async () => {
    await storeKey("gemini", "sk-gem", "gemini-2.5-pro");
    respondWith(geminiModels);
    await renderSwitcher();
    await open();

    expect(getStoredModel()).toBe("gemini-3.5-flash");
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it("lets the user change the key and cancel back to the model list", async () => {
    await storeKey("claude", "sk-ant");
    await renderSwitcher();
    await open();

    click(buttonByText(ui.container, "Change key"));
    expect(keyInput()).not.toBeNull();
    click(buttonByText(ui.container, "Cancel"));
    expect(keyInput()).toBeNull();
    expect(buttonByText(ui.container, "Claude Sonnet 5")).toBeDefined();
  });

  it("clears the key and goes back to key entry", async () => {
    await storeKey("claude", "sk-ant");
    await renderSwitcher();
    await open();

    click(buttonByText(ui.container, "Clear key"));
    await flush();

    expect(getApiKey()).toBe("");
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(keyInput()).not.toBeNull();
    expect(buttonByText(ui.container, "Cancel")).toBeUndefined();
    expect(trigger().textContent).toContain("Add API key");
  });

  it("asks for a new key when switching to a provider without one", async () => {
    await storeKey("gemini", "sk-gem");
    await renderSwitcher();
    await open();
    fetchMock.mockClear();

    click(buttonByText(ui.container, "OpenAI"));
    await flush();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(keyInput()!.placeholder).toBe("Paste your OpenAI API key");
    expect(ui.container.querySelector("a")?.getAttribute("href")).toBe("https://platform.openai.com/account/api-keys");
    expect(buttonByText(ui.container, "OpenAI")!.className).toContain("bg-purple-600");
  });

  it("closes on a click outside and stays open for clicks inside", async () => {
    await renderSwitcher();
    await open();
    act(() => {
      keyInput()!.dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
    });
    expect(ui.container.textContent).toContain("Select a model");
    act(() => {
      document.body.dispatchEvent(new MouseEvent("mousedown", { bubbles: true }));
    });
    expect(ui.container.textContent).not.toContain("Select a model");
  });

  it("toggles closed from the trigger button", async () => {
    await renderSwitcher();
    await open();
    await open();
    expect(ui.container.textContent).not.toContain("Select a model");
  });

  it("lets you clear the retrieval attempts field and retype a new value instead of snapping back", async () => {
    await renderSwitcher();
    await open();
    const input = ui.container.querySelector("#code-assistant-retrieval-attempts") as HTMLInputElement;
    expect(input.value).toBe("8");

    typeInto(input, "");
    expect(input.value).toBe("");

    typeInto(input, "12");
    expect(input.value).toBe("12");
    expect(getStoredRetrievalAttempts()).toBe(12);
  });

  it("resyncs the retrieval attempts field to the last valid value if left empty on blur", async () => {
    await renderSwitcher();
    await open();
    const input = ui.container.querySelector("#code-assistant-retrieval-attempts") as HTMLInputElement;

    typeInto(input, "");
    act(() => input.dispatchEvent(new FocusEvent("focusout", { bubbles: true })));

    expect(input.value).toBe("8");
    expect(getStoredRetrievalAttempts()).toBe(8);
  });

  it("lets you clear the session budget field and retype a new value instead of snapping back", async () => {
    await renderSwitcher();
    await open();
    const input = ui.container.querySelector("#code-assistant-session-budget") as HTMLInputElement;
    expect(input.value).toBe("8000");

    typeInto(input, "");
    expect(input.value).toBe("");

    typeInto(input, "9500");
    expect(input.value).toBe("9500");
    expect(getStoredSessionTokenBudget()).toBe(9500);
  });
});
