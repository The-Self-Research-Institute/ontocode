import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const logout = vi.fn();

vi.mock("../custom-hook/useAuth", () => ({
  useAuth: () => ({ user: { token: "jwt" }, logout }),
}));

vi.mock("../hooks/useSubscription", () => ({
  useSubscription: () => ({ isFree: false, getUpgradeMessage: () => "" }),
}));

vi.mock("../services/LlmInsightsService", () => ({
  hasApiKey: () => true,
  setStoredApiKey: () => {},
}));

vi.mock("../components/CodeAssistantModelSwitcher", () => ({
  CodeAssistantModelSwitcher: () => null,
}));

vi.mock("../services/codeAssistantProviderConfig", () => ({
  getProviderConfig: async () => ({ managed: false }),
  getCachedProviderConfig: () => ({ managed: false }),
}));

vi.mock("../services/codeAssistantLoop", () => ({
  runAssistantLoop: vi.fn(),
}));

vi.mock("../services/codeAssistantSession", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../services/codeAssistantSession")>();
  return { ...actual, createAssistantSession: vi.fn(), applyEditGroup: vi.fn() };
});

import { CodeAssistantPanel } from "../components/CodeAssistantPanel";
import { runAssistantLoop } from "../services/codeAssistantLoop";
import { AssistantApiError, applyEditGroup, createAssistantSession } from "../services/codeAssistantSession";
import { UNSAVED_CODE_VIEW_MESSAGE } from "../components/codeAssistantPanelHelpers";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const loopMock = vi.mocked(runAssistantLoop);
const sessionMock = vi.mocked(createAssistantSession);
const applyMock = vi.mocked(applyEditGroup);

let container: HTMLDivElement;
let root: Root;

const session = {
  sessionId: "s1",
  snapshot: { projectId: "proj-1", documentPath: "a.ttl", revision: 3, actionType: "ask" as const },
  budget: { retrievalCallsRemaining: 10, maxRetrievalCalls: 10 },
  expiresAt: "2030-01-01T00:00:00Z",
};

function renderPanel(props: Partial<React.ComponentProps<typeof CodeAssistantPanel>> = {}) {
  act(() => {
    root.render(<CodeAssistantPanel projectId="proj-1" documentPath="a.ttl" {...props} />);
  });
}

async function flush() {
  for (let i = 0; i < 6; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
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

function buttons(text: string): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll("button")).filter((b) => b.textContent?.trim().startsWith(text)) as HTMLButtonElement[];
}

function group(id: string) {
  return {
    clientGroupId: `c-${id}`,
    serverGroupId: id,
    validation: { passed: true, checks: [] },
    diff: [{ targetPath: "a.ttl", before: `old ${id}`, after: `new ${id}` }],
  };
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  loopMock.mockReset();
  sessionMock.mockReset();
  applyMock.mockReset();
  logout.mockReset();
  sessionMock.mockResolvedValue(session);
  Element.prototype.scrollIntoView = () => {};
  window.localStorage.clear();
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("CodeAssistantPanel dead ends", () => {
  it("puts the prompt back and resubmits it with the latest version after REVISION_STALE", async () => {
    loopMock
      .mockResolvedValueOnce({ kind: "stopped", reason: "Project changed", errorCode: "REVISION_STALE" })
      .mockResolvedValueOnce({ kind: "answer", text: "A is a class." });
    renderPanel();
    await send("What is A?");

    expect(composer().value).toBe("What is A?");
    const retry = buttons("Ask again with the latest version");
    expect(retry).toHaveLength(1);

    act(() => retry[0].click());
    await flush();

    expect(loopMock).toHaveBeenCalledTimes(2);
    expect(loopMock.mock.calls[1][2]).toBe("What is A?");
    expect(loopMock.mock.calls[1][5]).toEqual([]);
    expect(sessionMock).toHaveBeenCalledTimes(2);
    expect(container.textContent).toContain("A is a class.");
    expect(composer().value).toBe("");
  });

  it("counts down before allowing a retry after RATE_LIMITED", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: false, toFake: ["setInterval", "clearInterval", "Date"] });
    loopMock.mockResolvedValueOnce({ kind: "stopped", reason: "Slow down", errorCode: "RATE_LIMITED", retryAfterSeconds: 3 } as never);
    renderPanel();
    await send("Explain B");

    let retry = buttons("Try again")[0];
    expect(retry.textContent).toContain("Try again in 3 s");
    expect(retry.disabled).toBe(true);

    await act(async () => {
      vi.advanceTimersByTime(3100);
    });
    retry = buttons("Try again")[0];
    expect(retry.textContent?.trim()).toBe("Try again");
    expect(retry.disabled).toBe(false);
  });

  it("offers to sign in again after UNAUTHORIZED from the backend", async () => {
    sessionMock.mockRejectedValueOnce(new AssistantApiError("Code assistant request failed (HTTP 401)."));
    renderPanel();
    await send("Hello");
    expect(composer().value).toBe("Hello");
    act(() => buttons("Sign in again")[0].click());
    expect(logout).toHaveBeenCalledWith(true);
    expect(loopMock).not.toHaveBeenCalled();
  });

  it("explains a permission problem without offering a retry", async () => {
    loopMock.mockResolvedValueOnce({ kind: "stopped", reason: "Forbidden", errorCode: "FORBIDDEN" });
    renderPanel();
    await send("Change C");
    expect(container.textContent).toContain("permission");
    expect(buttons("Ask again")).toHaveLength(0);
  });
});

describe("CodeAssistantPanel Apply All", () => {
  it("stops at the first failed group and summarises the run", async () => {
    loopMock.mockResolvedValueOnce({
      kind: "propose",
      result: { ok: true, groups: [group("g1"), group("g2"), group("g3")] },
    });
    applyMock
      .mockResolvedValueOnce({ ok: true, applied: true, newRevision: 4, remappedPendingGroups: [{ serverGroupId: "g2", remapped: true }] })
      .mockRejectedValueOnce(new AssistantApiError("The reimport failed.", "RECOVERY_REQUIRED"));
    renderPanel();
    await send("Rename things");

    act(() => buttons("Apply All")[0].click());
    await flush();

    expect(applyMock).toHaveBeenCalledTimes(2);
    expect(applyMock.mock.calls.map((c) => c[3])).toEqual(["g1", "g2"]);
    expect(container.textContent).toContain(
      "Applied 1 · Failed: group 2 (it failed partway and the project needs checking) · Not attempted: 1",
    );
    const cards = Array.from(container.querySelectorAll("div.border-2"));
    expect(cards[1].textContent).toContain("The reimport failed.");
    expect(Array.from(cards[1].querySelectorAll("button")).some((b) => b.textContent?.trim() === "Apply")).toBe(false);
  });

  it("keeps Apply disabled while Code View has unsaved changes", async () => {
    loopMock.mockResolvedValueOnce({ kind: "propose", result: { ok: true, groups: [group("g1"), group("g2")] } });
    renderPanel({ hasUnsavedCodeViewChanges: true });
    await send("Edit");
    expect(container.textContent).toContain(UNSAVED_CODE_VIEW_MESSAGE);
    expect(buttons("Apply All")[0].disabled).toBe(true);
    act(() => buttons("Apply All")[0].click());
    await flush();
    expect(applyMock).not.toHaveBeenCalled();

    renderPanel({ hasUnsavedCodeViewChanges: false });
    expect(buttons("Apply All")[0].disabled).toBe(false);
  });
});

describe("CodeAssistantPanel usage", () => {
  it("hands the loop an onUsage handler and shows each turn's usage under the answer", async () => {
    loopMock.mockImplementationOnce(async (_ctx, _system, _text, _onStage, _signal, _history, _onContext, onUsage) => {
      onUsage?.({ provider: "claude", model: "m", latencyMs: 700, inputTokens: 1000, outputTokens: 20, cacheReadTokens: 800 });
      onUsage?.({ provider: "claude", model: "m", latencyMs: 400, inputTokens: 1100, outputTokens: 60, cacheWriteTokens: 50 });
      return { kind: "answer", text: "A is a class." };
    });
    renderPanel();
    await send("What is A?");

    expect(typeof loopMock.mock.calls[0][7]).toBe("function");
    expect(container.querySelector("[data-usage-total]")?.textContent).toBe(
      "· 2,100 in · 80 out · 800 cache read · 50 cache write · 1.1 s",
    );
    act(() => buttons("Usage")[0].click());
    const rows = Array.from(container.querySelectorAll("[data-usage-turns] li")).map((li) => li.textContent);
    expect(rows).toEqual(["Turn 1: 1,000 in · 20 out · 800 cache read · 700 ms", "Turn 2: 1,100 in · 60 out · 50 cache write · 400 ms"]);
  });

  it("keeps usage on a review entry too", async () => {
    loopMock.mockImplementationOnce(async (_ctx, _system, _text, _onStage, _signal, _history, _onContext, onUsage) => {
      onUsage?.({ provider: "openai", model: "m", latencyMs: 1500, inputTokens: 10, outputTokens: 5 });
      return { kind: "propose", result: { ok: true, groups: [group("g1")] } };
    });
    renderPanel();
    await send("Rename A");
    expect(container.querySelector("[data-usage-total]")?.textContent).toBe("· 10 in · 5 out · 1.5 s");
  });
});
