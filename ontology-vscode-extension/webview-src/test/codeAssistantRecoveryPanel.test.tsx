import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ token: "jwt" as string | undefined }));

vi.mock("../custom-hook/useAuth", () => ({
  useAuth: () => ({ user: auth.token ? { token: auth.token } : null, logout: () => {} }),
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

vi.mock("../services/codeAssistantRecovery", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../services/codeAssistantRecovery")>();
  return { ...actual, fetchRecoveryState: vi.fn(), restorePreviousVersion: vi.fn(), clearRecoveryLock: vi.fn() };
});

import { CodeAssistantPanel } from "../components/CodeAssistantPanel";
import { runAssistantLoop } from "../services/codeAssistantLoop";
import { AssistantApiError, applyEditGroup, createAssistantSession } from "../services/codeAssistantSession";
import {
  clearRecoveryLock,
  fetchRecoveryState,
  RecoveryApiError,
  restorePreviousVersion,
  type RecoveryState,
} from "../services/codeAssistantRecovery";
import { RECOVERY_LOCKED_APPLY_MESSAGE } from "../components/codeAssistantPanelHelpers";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const loopMock = vi.mocked(runAssistantLoop);
const sessionMock = vi.mocked(createAssistantSession);
const applyMock = vi.mocked(applyEditGroup);
const stateMock = vi.mocked(fetchRecoveryState);
const restoreMock = vi.mocked(restorePreviousVersion);
const clearMock = vi.mocked(clearRecoveryLock);

const LOCKED: RecoveryState = {
  locked: true,
  reason: "reimport failed",
  lockedAt: "2026-09-24T10:00:00Z",
  operationId: "op-1",
  canRestore: true,
};
const UNLOCKED: RecoveryState = { locked: false, canRestore: false };

const session = {
  sessionId: "s1",
  snapshot: { projectId: "proj-1", documentPath: "a.ttl", revision: 3, actionType: "ask" as const },
  budget: { retrievalCallsRemaining: 10, maxRetrievalCalls: 10 },
  expiresAt: "2030-01-01T00:00:00Z",
};

let container: HTMLDivElement;
let root: Root;

function renderPanel(props: Partial<React.ComponentProps<typeof CodeAssistantPanel>> = {}) {
  act(() => {
    root.render(<CodeAssistantPanel projectId="proj-1" documentPath="a.ttl" {...props} />);
  });
}

async function flush() {
  for (let i = 0; i < 8; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}

function composer(): HTMLTextAreaElement {
  return container.querySelector("textarea") as HTMLTextAreaElement;
}

function sendButton(): HTMLButtonElement {
  return container.querySelector('button[title="Send"]') as HTMLButtonElement;
}

function banner(): HTMLElement | null {
  return container.querySelector("[data-recovery-banner]");
}

function buttons(text: string): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll("button")).filter((b) => b.textContent?.trim().startsWith(text)) as HTMLButtonElement[];
}

function click(text: string) {
  const [button] = buttons(text);
  expect(button, `button "${text}"`).toBeDefined();
  act(() => button.click());
}

async function send(value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set;
  act(() => {
    setter?.call(composer(), value);
    composer().dispatchEvent(new Event("input", { bubbles: true }));
  });
  act(() => {
    composer().dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  });
  await flush();
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  loopMock.mockReset();
  sessionMock.mockReset();
  applyMock.mockReset();
  stateMock.mockReset();
  restoreMock.mockReset();
  clearMock.mockReset();
  sessionMock.mockResolvedValue(session);
  stateMock.mockResolvedValue(UNLOCKED);
  auth.token = "jwt";
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

describe("CodeAssistantPanel recovery lock", () => {
  it("checks the project's recovery state when the panel opens", async () => {
    renderPanel();
    await flush();
    expect(stateMock).toHaveBeenCalledWith(expect.any(String), "jwt", "proj-1");
    expect(banner()).toBeNull();
    expect(composer().disabled).toBe(false);
  });

  it("shows a persistent banner and pauses Ask while the project is locked", async () => {
    stateMock.mockResolvedValue(LOCKED);
    renderPanel();
    await flush();

    expect(banner()?.textContent).toContain("This project may be inconsistent");
    expect(banner()?.textContent).toContain("Reason: reimport failed");
    expect(composer().disabled).toBe(true);
    expect(composer().placeholder).toContain("Paused");
    expect(sendButton().disabled).toBe(true);

    await send("What is A?");
    expect(sessionMock).not.toHaveBeenCalled();
  });

  it("only restores after the user confirms, then unlocks and tells the parent", async () => {
    stateMock.mockResolvedValueOnce(LOCKED).mockResolvedValue(UNLOCKED);
    restoreMock.mockResolvedValue(undefined);
    const onProjectRestored = vi.fn();
    renderPanel({ onProjectRestored });
    await flush();

    click("Restore previous version");
    expect(restoreMock).not.toHaveBeenCalled();
    expect(banner()?.textContent).toContain("Anything changed since then will be replaced");
    click("Cancel");
    expect(restoreMock).not.toHaveBeenCalled();

    click("Restore previous version");
    click("Yes, restore it");
    await flush();

    expect(restoreMock).toHaveBeenCalledWith(expect.any(String), "jwt", "proj-1");
    expect(onProjectRestored).toHaveBeenCalledTimes(1);
    expect(banner()).toBeNull();
    expect(composer().disabled).toBe(false);
  });

  it("hides the restore option when the server says there is nothing to restore", async () => {
    stateMock.mockResolvedValue({ ...LOCKED, canRestore: false });
    renderPanel();
    await flush();
    expect(buttons("Restore previous version")).toHaveLength(0);
    expect(buttons("I've checked it")).toHaveLength(1);
  });

  it("keeps the lock and explains why when the restore is refused", async () => {
    stateMock.mockResolvedValue(LOCKED);
    restoreMock.mockRejectedValue(new RecoveryApiError("The snapshot is gone.", 409, "RECOVERY_REQUIRED"));
    const onProjectRestored = vi.fn();
    renderPanel({ onProjectRestored });
    await flush();

    click("Restore previous version");
    click("Yes, restore it");
    await flush();

    expect(banner()?.textContent).toContain("Couldn't restore the previous version: The snapshot is gone.");
    expect(onProjectRestored).not.toHaveBeenCalled();
    expect(composer().disabled).toBe(true);
  });

  it("unlocks without restoring only after the second confirmation", async () => {
    stateMock.mockResolvedValueOnce(LOCKED).mockResolvedValue(UNLOCKED);
    clearMock.mockResolvedValue(undefined);
    const onProjectRestored = vi.fn();
    renderPanel({ onProjectRestored });
    await flush();

    click("I've checked it");
    expect(clearMock).not.toHaveBeenCalled();
    click("Yes, unlock");
    await flush();

    expect(clearMock).toHaveBeenCalledWith(expect.any(String), "jwt", "proj-1");
    expect(restoreMock).not.toHaveBeenCalled();
    expect(onProjectRestored).not.toHaveBeenCalled();
    expect(banner()).toBeNull();
  });

  it("re-checks the state and locks the panel when the backend answers 423", async () => {
    sessionMock.mockRejectedValueOnce(
      new AssistantApiError("Project is locked", "PROJECT_RECOVERY_LOCKED", undefined, { status: 423, recoveryLocked: true }),
    );
    renderPanel();
    await flush();
    stateMock.mockResolvedValue(LOCKED);

    await send("Rename A");

    expect(stateMock).toHaveBeenCalledTimes(2);
    expect(banner()).not.toBeNull();
    expect(buttons("Restore previous version")).toHaveLength(1);
    expect(composer().disabled).toBe(true);
  });

  it("locks the panel straight away even if the re-check can't reach the server", async () => {
    loopMock.mockResolvedValueOnce({ kind: "stopped", reason: "Locked", errorCode: "PROJECT_RECOVERY_LOCKED" });
    renderPanel();
    await flush();
    stateMock.mockRejectedValue(new TypeError("Failed to fetch"));

    await send("Rename A");

    expect(banner()).not.toBeNull();
    expect(composer().disabled).toBe(true);
  });

  it("disables Apply after an apply comes back RECOVERY_REQUIRED", async () => {
    loopMock.mockResolvedValueOnce({
      kind: "propose",
      result: {
        ok: true,
        groups: ["g1", "g2"].map((id) => ({
          clientGroupId: `c-${id}`,
          serverGroupId: id,
          validation: { passed: true, checks: [] },
          diff: [{ targetPath: "a.ttl", before: "old", after: "new" }],
        })),
      },
    });
    applyMock.mockRejectedValueOnce(new AssistantApiError("The reimport failed.", "RECOVERY_REQUIRED", undefined, { status: 409 }));
    renderPanel();
    await flush();
    await send("Rename things");
    stateMock.mockResolvedValue(LOCKED);

    const applyButtons = buttons("Apply").filter((b) => b.textContent?.trim() === "Apply");
    act(() => applyButtons[0].click());
    await flush();

    expect(banner()).not.toBeNull();
    expect(container.textContent).toContain(RECOVERY_LOCKED_APPLY_MESSAGE);
    const remaining = buttons("Apply").filter((b) => b.textContent?.trim() === "Apply");
    expect(remaining.length).toBeGreaterThan(0);
    expect(remaining.every((b) => b.disabled)).toBe(true);
    act(() => remaining[0].click());
    await flush();
    expect(applyMock).toHaveBeenCalledTimes(1);
  });

  it("drops the previous project's banner as soon as the project changes", async () => {
    stateMock.mockResolvedValueOnce(LOCKED);
    renderPanel();
    await flush();
    expect(banner()).not.toBeNull();

    let resolveNext: (state: RecoveryState) => void = () => {};
    stateMock.mockImplementationOnce(() => new Promise<RecoveryState>((resolve) => (resolveNext = resolve)));
    renderPanel({ projectId: "proj-2" });
    expect(banner()).toBeNull();
    expect(stateMock).toHaveBeenLastCalledWith(expect.any(String), "jwt", "proj-2");

    await act(async () => resolveNext(UNLOCKED));
    await flush();
    expect(banner()).toBeNull();
  });

  it("ignores a late answer for the project the user already left", async () => {
    let resolveFirst: (state: RecoveryState) => void = () => {};
    stateMock.mockImplementationOnce(() => new Promise<RecoveryState>((resolve) => (resolveFirst = resolve)));
    renderPanel();
    renderPanel({ projectId: "proj-2" });
    await flush();

    await act(async () => resolveFirst(LOCKED));
    await flush();
    expect(banner()).toBeNull();
  });

  it("checks again once the sign-in token is available", async () => {
    auth.token = undefined;
    stateMock.mockRejectedValueOnce(new RecoveryApiError("Missing token", 401)).mockResolvedValue(LOCKED);
    renderPanel();
    await flush();
    expect(banner()).toBeNull();

    auth.token = "late-jwt";
    renderPanel();
    await flush();

    expect(stateMock).toHaveBeenLastCalledWith(expect.any(String), "late-jwt", "proj-1");
    expect(banner()).not.toBeNull();
  });

  it("re-checks when the window regains focus while locked", async () => {
    stateMock.mockResolvedValueOnce(LOCKED).mockResolvedValue(UNLOCKED);
    renderPanel();
    await flush();
    expect(banner()).not.toBeNull();

    act(() => {
      window.dispatchEvent(new Event("focus"));
    });
    await flush();

    expect(stateMock).toHaveBeenCalledTimes(2);
    expect(banner()).toBeNull();
    expect(composer().disabled).toBe(false);
  });
});
