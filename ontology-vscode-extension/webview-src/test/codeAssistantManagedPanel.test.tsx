import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ProviderConfig } from "../services/codeAssistantProviderConfig";

const providerState = vi.hoisted(() => ({
  config: { managed: false } as ProviderConfig,
  hasKey: false,
}));

vi.mock("../custom-hook/useAuth", () => ({
  useAuth: () => ({ user: { token: "jwt" }, logout: () => {} }),
}));

vi.mock("../hooks/useSubscription", () => ({
  useSubscription: () => ({ isFree: false, getUpgradeMessage: () => "" }),
}));

vi.mock("../services/LlmInsightsService", () => ({
  hasApiKey: () => providerState.hasKey,
  setStoredApiKey: () => {},
}));

vi.mock("../components/CodeAssistantModelSwitcher", () => ({
  CodeAssistantModelSwitcher: () => <div data-model-switcher>switcher</div>,
}));

vi.mock("../services/codeAssistantProviderConfig", () => ({
  getProviderConfig: vi.fn(async () => providerState.config),
  getCachedProviderConfig: () => null,
}));

vi.mock("../services/codeAssistantRecovery", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../services/codeAssistantRecovery")>();
  return { ...actual, fetchRecoveryState: vi.fn(async () => ({ locked: false, canRestore: false })) };
});

vi.mock("../services/codeAssistantLoop", () => ({
  runAssistantLoop: vi.fn(),
}));

vi.mock("../services/codeAssistantSession", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../services/codeAssistantSession")>();
  return { ...actual, createAssistantSession: vi.fn() };
});

import { CodeAssistantPanel } from "../components/CodeAssistantPanel";
import { runAssistantLoop } from "../services/codeAssistantLoop";
import { createAssistantSession } from "../services/codeAssistantSession";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const loopMock = vi.mocked(runAssistantLoop);
const sessionMock = vi.mocked(createAssistantSession);

const session = {
  sessionId: "s1",
  snapshot: { projectId: "proj-1", documentPath: "a.ttl", revision: 3, actionType: "ask" as const },
  budget: { retrievalCallsRemaining: 10, maxRetrievalCalls: 10 },
  expiresAt: "2030-01-01T00:00:00Z",
};

let container: HTMLDivElement;
let root: Root;

async function flush() {
  for (let i = 0; i < 8; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}

async function renderPanel() {
  act(() => {
    root.render(<CodeAssistantPanel projectId="proj-1" documentPath="a.ttl" />);
  });
  await flush();
}

function composer(): HTMLTextAreaElement {
  return container.querySelector("textarea") as HTMLTextAreaElement;
}

function type(value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set;
  act(() => {
    setter?.call(composer(), value);
    composer().dispatchEvent(new Event("input", { bubbles: true }));
  });
}

async function send(value: string) {
  type(value);
  act(() => {
    composer().dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  });
  await flush();
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  loopMock.mockReset();
  sessionMock.mockReset();
  sessionMock.mockResolvedValue(session);
  loopMock.mockResolvedValue({ kind: "answer", text: "Managed answer." });
  providerState.config = { managed: false };
  providerState.hasKey = false;
  Element.prototype.scrollIntoView = () => {};
  window.localStorage.clear();
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

describe("CodeAssistantPanel managed provider", () => {
  it("lets the user ask without an API key and names the organization's model", async () => {
    providerState.config = { managed: true, provider: "claude", model: "org-model" };
    await renderPanel();

    expect(container.querySelector("[data-managed-provider]")?.textContent).toBe("Managed by your organization · claude · org-model");
    expect(container.querySelector("[data-model-switcher]")).toBeNull();
    expect(composer().disabled).toBe(false);
    expect(container.textContent).not.toContain("add your API key");

    await send("What is A?");

    expect(sessionMock).toHaveBeenCalledTimes(1);
    expect(sessionMock.mock.calls[0][2]).toMatchObject({ projectId: "proj-1", provider: "claude", model: "org-model" });
    expect(loopMock.mock.calls[0][0].providerConfig).toEqual({ managed: true, provider: "claude", model: "org-model" });
    expect(container.textContent).toContain("Managed answer.");
  });

  it("hides the /logout command that removes a saved key", async () => {
    providerState.config = { managed: true, provider: "openai", model: "gpt-org" };
    await renderPanel();
    type("/");
    const commands = Array.from(container.querySelectorAll("button")).map((b) => b.textContent ?? "");
    expect(commands.some((t) => t.includes("/clear"))).toBe(true);
    expect(commands.some((t) => t.includes("/logout"))).toBe(false);
  });

  it("keeps the key picker and needs a key when the provider isn't managed", async () => {
    await renderPanel();
    expect(container.querySelector("[data-model-switcher]")).not.toBeNull();
    expect(container.querySelector("[data-managed-provider]")).toBeNull();
    expect(composer().disabled).toBe(true);
    expect(composer().placeholder).toContain("Add an API key");
  });

  it("sends no provider override for bring-your-own-key sessions", async () => {
    providerState.hasKey = true;
    await renderPanel();
    await send("What is A?");
    const input = sessionMock.mock.calls[0][2];
    expect(input).not.toHaveProperty("provider");
    expect(input).not.toHaveProperty("model");
    expect(loopMock.mock.calls[0][0].providerConfig).toEqual({ managed: false });
  });
});
