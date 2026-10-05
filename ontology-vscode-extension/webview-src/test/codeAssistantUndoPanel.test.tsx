import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../custom-hook/useAuth", () => ({
  useAuth: () => ({ user: { token: "jwt" }, logout: vi.fn() }),
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

vi.mock("../services/changeTrackingService", () => ({
  changeTrackingService: { undoChangeSet: vi.fn(), redoChangeSet: vi.fn() },
}));

import { CodeAssistantPanel } from "../components/CodeAssistantPanel";
import { runAssistantLoop } from "../services/codeAssistantLoop";
import { applyEditGroup, createAssistantSession } from "../services/codeAssistantSession";
import { changeTrackingService } from "../services/changeTrackingService";
import type { ChangeSetResult } from "../services/changeSetService";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const loopMock = vi.mocked(runAssistantLoop);
const applyMock = vi.mocked(applyEditGroup);
const undoMock = vi.mocked(changeTrackingService.undoChangeSet);
const redoMock = vi.mocked(changeTrackingService.redoChangeSet);

let container: HTMLDivElement;
let root: Root;

const session = {
  sessionId: "s1",
  snapshot: { projectId: "proj-1", documentPath: "a.ttl", revision: 3, actionType: "ask" as const },
  budget: { retrievalCallsRemaining: 10, maxRetrievalCalls: 10 },
  expiresAt: "2030-01-01T00:00:00Z",
};

const group = (id: string) => ({
  clientGroupId: `c-${id}`,
  serverGroupId: id,
  validation: { passed: true, checks: [] },
  diff: [{ targetPath: "a.ttl", before: `old ${id}`, after: `new ${id}` }],
});

function changeSet(overrides: Partial<ChangeSetResult>): ChangeSetResult {
  return { success: true, alreadyReverted: false, dryRun: true, direction: "UNDO", applied: [], skipped: [], status: 200, ...overrides };
}

const applied = (n: number) => Array.from({ length: n }, (_, i) => ({ changeId: `h${i}` }));

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

async function send(value: string) {
  const textarea = container.querySelector("textarea") as HTMLTextAreaElement;
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set;
  act(() => {
    setter?.call(textarea, value);
    textarea.dispatchEvent(new Event("input", { bubbles: true }));
  });
  act(() => {
    textarea.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  });
  await flush();
}

function button(text: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll("button")).find((b) => b.textContent?.trim() === text) as HTMLButtonElement | undefined;
}

async function click(text: string) {
  const target = button(text);
  expect(target, `button "${text}"`).toBeTruthy();
  act(() => target!.click());
  await flush();
}

async function proposeAndApply(onApplySuccess = vi.fn()) {
  loopMock.mockResolvedValueOnce({ kind: "propose", result: { ok: true, groups: [group("g1")] }, explanation: "Rename A to B.\nDetails" });
  applyMock.mockResolvedValueOnce({ ok: true, applied: true, newRevision: 4, remappedPendingGroups: [] });
  renderPanel({ onApplySuccess });
  await send("Rename A");
  expect(button("Undo")).toBeUndefined();
  await click("Apply");
  return onApplySuccess;
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  vi.spyOn(console, "log").mockImplementation(() => {});
  loopMock.mockReset();
  applyMock.mockReset();
  undoMock.mockReset();
  redoMock.mockReset();
  vi.mocked(createAssistantSession).mockReset().mockResolvedValue(session);
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

describe("CodeAssistantPanel undo", () => {
  it("sends the explanation as the change summary", async () => {
    await proposeAndApply();
    expect(applyMock.mock.calls[0][5]).toEqual({ summary: "Rename A to B." });
  });

  it("previews, confirms, marks the group undone and redoes it", async () => {
    const onApplySuccess = await proposeAndApply();
    onApplySuccess.mockClear();
    const events: CustomEvent[] = [];
    const listener = (e: Event) => events.push(e as CustomEvent);
    window.addEventListener("ontologyRollback", listener);

    undoMock
      .mockResolvedValueOnce(changeSet({ applied: applied(2), skipped: [{ changeId: "h9", reason: "It was changed since this edit" }] }))
      .mockResolvedValueOnce(changeSet({ dryRun: false, applied: applied(2), auditId: "a1" }));
    await click("Undo");

    expect(undoMock).toHaveBeenCalledWith("proj-1", "g1", { dryRun: true });
    expect(container.textContent).toContain("This undoes 2 changes.");
    expect(container.textContent).toContain("1 skipped: It was changed since this edit");
    await click("Undo 2 changes");

    expect(undoMock).toHaveBeenLastCalledWith("proj-1", "g1", { dryRun: false });
    expect(container.textContent).toContain("Undone");
    expect(container.textContent).not.toContain("Applied");
    expect(container.querySelector("[data-undone='true']")).not.toBeNull();
    expect(onApplySuccess).toHaveBeenCalledWith(["old g1"], []);
    expect(events).toHaveLength(1);
    expect(events[0].detail).toMatchObject({ projectId: "proj-1", changeSetId: "g1", direction: "UNDO", success: true });

    redoMock
      .mockResolvedValueOnce(changeSet({ direction: "REDO", applied: applied(1) }))
      .mockResolvedValueOnce(changeSet({ direction: "REDO", dryRun: false, applied: applied(1) }));
    await click("Redo");
    expect(container.textContent).toContain("This redoes 1 change.");
    await click("Redo 1 change");

    expect(redoMock).toHaveBeenCalledTimes(2);
    expect(container.textContent).toContain("Applied");
    expect(button("Undo")).toBeTruthy();
    expect(events).toHaveLength(2);
    window.removeEventListener("ontologyRollback", listener);
  });

  it("cancels the preview without undoing", async () => {
    await proposeAndApply();
    undoMock.mockResolvedValueOnce(changeSet({ applied: applied(1) }));
    await click("Undo");
    await click("Cancel");

    expect(undoMock).toHaveBeenCalledTimes(1);
    expect(button("Undo")).toBeTruthy();
    expect(container.textContent).not.toContain("This undoes");
  });

  it("shows a short inline message when the undo is refused", async () => {
    await proposeAndApply();
    undoMock
      .mockResolvedValueOnce(changeSet({ applied: applied(1) }))
      .mockResolvedValueOnce(
        changeSet({ success: false, status: 409, dryRun: false, skipped: [{ changeId: "h1", reason: "It was changed since this edit" }] }),
      );
    await click("Undo");
    await click("Undo 1 change");

    expect(container.textContent).toContain("Nothing can be undone. 1 skipped: It was changed since this edit.");
    expect(container.textContent).toContain("Applied");
    expect(button("Undo")?.disabled).toBe(false);
  });

  it("disables undo while Code View has unsaved edits", async () => {
    await proposeAndApply();
    renderPanel({ hasUnsavedCodeViewChanges: true });
    await flush();

    expect(button("Undo")?.disabled).toBe(true);
    expect(button("Undo")?.title).toBeTruthy();
  });
});
