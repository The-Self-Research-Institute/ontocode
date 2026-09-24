import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ProviderConfig } from "../services/codeAssistantProviderConfig";

const state = vi.hoisted(() => ({ config: { managed: false } as ProviderConfig }));

vi.mock("../services/codeAssistantProviderConfig", () => ({
  getProviderConfig: vi.fn(async () => state.config),
  getCachedProviderConfig: () => null,
}));

import LLMSettingsPanel from "../components/LLMSettingsPanel";
import { getProviderConfig } from "../services/codeAssistantProviderConfig";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

async function renderPanel() {
  act(() => {
    root.render(<LLMSettingsPanel />);
  });
  for (let i = 0; i < 6; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}

beforeEach(() => {
  state.config = { managed: false };
  window.localStorage.clear();
  vi.stubGlobal("fetch", vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) })));
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("LLMSettingsPanel managed mode", () => {
  it("says the provider is managed and offers no key entry", async () => {
    state.config = { managed: true, provider: "claude", model: "org-model" };
    await renderPanel();

    const managed = container.querySelector("[data-managed-provider]");
    expect(managed?.textContent).toContain("Managed by your organization");
    expect(managed?.textContent).toContain("org-model");
    expect(container.querySelector('input[type="password"]')).toBeNull();
    expect(container.textContent).not.toContain("API Key");
    expect(container.textContent).not.toContain("Save Settings");
  });

  it("keeps the bring-your-own-key form when the provider isn't managed", async () => {
    await renderPanel();
    expect(vi.mocked(getProviderConfig)).toHaveBeenCalled();
    expect(container.querySelector("[data-managed-provider]")).toBeNull();
    expect(container.querySelector('input[type="password"]')).not.toBeNull();
    expect(container.textContent).toContain("Save Settings");
  });
});
