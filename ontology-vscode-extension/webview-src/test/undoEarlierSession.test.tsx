import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import undoRedoService from "../services/undoRedoService";
import apiClient from "../services/apiClient";
import { undoQueue, createUndoQueue } from "../services/undoQueue";
import { EARLIER_SESSION_TEXT, type QueueApi } from "../services/undoQueueSupport";
import { useOntologyUndoRedo } from "../hooks/useOntologyUndoRedo";
import UndoEditMenuItems from "../components/UndoEditMenuItems";
import UndoRunControls from "../components/UndoRunControls";
import { noteServerTime, setSessionStartForTest } from "../utils/undoSession";
import type { QueueStep } from "../services/undoRedoService";

const TEXT = "This change was made in an earlier session. Undo it anyway?";
const available: any = {
  undo: { available: true, direction: "UNDO", changeId: "c1", label: "L", dependents: [] },
  redo: { available: false, direction: "REDO", dependents: [] },
};

function queueWith(steps: QueueStep[]) {
  const api: QueueApi = {
    fetchQueue: vi.fn(async (_p, count) => ({ steps: steps.slice(0, count), blocked: null })),
    send: vi.fn(async (_p, _k, step) => {
      const index = steps.findIndex((s) => s.changeId === step.changeId);
      if (index >= 0) steps.splice(index, 1);
      return { kind: "done" as const };
    }),
    announce: vi.fn(),
    refused: vi.fn(),
  };
  return { api, queue: createUndoQueue(api) };
}

describe("earlier-session confirmation in the queue", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("undoes a change from this session straight away", async () => {
    const { api, queue } = queueWith([{ changeId: "a", label: "A" }]);
    await queue.press("p", "undo");
    await vi.advanceTimersByTimeAsync(50);
    expect(api.send).toHaveBeenCalledTimes(1);
    expect(queue.getSnapshot().phase).toBe("finished");
  });

  it("asks first for an earlier-session change and undoes only after Undo is chosen", async () => {
    const { api, queue } = queueWith([{ changeId: "a", label: "A", earlierSession: true }]);
    await queue.press("p", "undo");
    await vi.advanceTimersByTimeAsync(50);
    expect(api.send).not.toHaveBeenCalled();
    expect(queue.getSnapshot().phase).toBe("paused");
    expect(queue.getSnapshot().reason).toBe("Earlier session");
    expect(queue.getSnapshot().message).toBe(TEXT);
    expect(EARLIER_SESSION_TEXT).toBe(TEXT);
    await queue.resume();
    await vi.advanceTimersByTimeAsync(50);
    expect(api.send).toHaveBeenCalledTimes(1);
    expect(queue.getSnapshot().phase).toBe("finished");
  });

  it("leaves everything unchanged when the prompt is cancelled", async () => {
    const { api, queue } = queueWith([{ changeId: "a", label: "A", earlierSession: true }]);
    await queue.press("p", "undo");
    await vi.advanceTimersByTimeAsync(50);
    queue.stopPending();
    await vi.advanceTimersByTimeAsync(50);
    expect(api.send).not.toHaveBeenCalled();
    expect(queue.getSnapshot().phase).toBe("idle");
    expect(api.announce).not.toHaveBeenCalled();
  });

  it("does not ask before a redo", async () => {
    const { api, queue } = queueWith([]);
    await queue.press("p", "redo", { available: true, direction: "REDO", changeId: "r", auditId: "au", dependents: [] });
    await vi.advanceTimersByTimeAsync(50);
    expect(api.send).toHaveBeenCalledTimes(1);
  });

  it("asks again for each earlier-session change in a run", async () => {
    const { api, queue } = queueWith([
      { changeId: "a", label: "A" },
      { changeId: "b", label: "B", earlierSession: true },
    ]);
    await queue.press("p", "undo");
    await queue.press("p", "undo");
    await vi.advanceTimersByTimeAsync(50);
    expect(api.send).toHaveBeenCalledTimes(1);
    expect(queue.getSnapshot().phase).toBe("paused");
    await queue.resume();
    await vi.advanceTimersByTimeAsync(50);
    expect(api.send).toHaveBeenCalledTimes(2);
  });
});

describe("marking steps from the server time", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    setSessionStartForTest(undefined);
  });

  it("marks only changes older than the page load, using the server clock", async () => {
    setSessionStartForTest(undefined);
    const nowServer = Date.parse("2026-10-28T10:00:00Z");
    noteServerTime(new Date(nowServer).toISOString());
    const start = nowServer - performance.now();
    vi.spyOn(apiClient, "get").mockResolvedValue({
      steps: [
        { changeId: "new", timestamp: new Date(start + 5000).toISOString() },
        { changeId: "old", timestamp: new Date(start - 86400000).toISOString() },
        { changeId: "none" },
      ],
      serverTime: new Date(nowServer + 9000).toISOString(),
    } as any);
    const { steps } = await undoRedoService.fetchQueue("p", 3);
    expect(steps.map((s) => !!s.earlierSession)).toEqual([false, true, false]);
  });
});

describe("shortcut, Edit menu and desktop menu go through the same check", () => {
  let root: Root;
  let host: HTMLDivElement;
  let menuCallback: ((kind: string) => void) | undefined;
  let sendStep: ReturnType<typeof vi.spyOn>;

  function Probe() {
    const { menu } = useOntologyUndoRedo({ projectId: "p1" });
    return (
      <>
        <UndoRunControls menu={menu} />
        <UndoEditMenuItems menu={menu} onClose={() => undefined} />
      </>
    );
  }

  const settle = () =>
    act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 60));
    });

  beforeEach(() => {
    (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement("div");
    document.body.appendChild(host);
    root = createRoot(host);
    vi.spyOn(undoRedoService, "fetchState").mockResolvedValue(available);
    vi.spyOn(undoRedoService, "fetchQueue").mockResolvedValue({ steps: [{ changeId: "c1", label: "L", earlierSession: true }], blocked: null });
    sendStep = vi.spyOn(undoRedoService, "sendStep").mockResolvedValue({ kind: "done" });
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    undoQueue.reset();
    (window as any).electronAPI = undefined;
    vi.restoreAllMocks();
  });

  const dialog = () => host.querySelector('[role="dialog"]');
  const button = (label: string) => Array.from(host.querySelectorAll("button")).find((b) => b.textContent === label) as HTMLButtonElement;

  it("asks from the keyboard, and Cancel does nothing", async () => {
    await act(async () => root.render(<Probe />));
    act(() => {
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "z", ctrlKey: true, bubbles: true, cancelable: true }));
    });
    await settle();
    expect(dialog()?.textContent).toContain(TEXT);
    expect(sendStep).not.toHaveBeenCalled();
    act(() => button("Cancel").click());
    await settle();
    expect(dialog()).toBeNull();
    expect(sendStep).not.toHaveBeenCalled();
  });

  it("asks from the Edit menu and undoes after Undo is chosen", async () => {
    await act(async () => root.render(<Probe />));
    await settle();
    act(() => button("Undo ontology change: L").click());
    await settle();
    expect(dialog()?.textContent).toContain(TEXT);
    expect(sendStep).not.toHaveBeenCalled();
    act(() => button("Undo").click());
    await settle();
    expect(sendStep).toHaveBeenCalledTimes(1);
  });

  it("asks from the desktop menu message", async () => {
    (window as any).electronAPI = {
      onMenuUndoRedo: (cb: (kind: string) => void) => {
        menuCallback = cb;
      },
    };
    await act(async () => root.render(<Probe />));
    act(() => menuCallback?.("undo"));
    await settle();
    expect(dialog()?.textContent).toContain(TEXT);
    expect(sendStep).not.toHaveBeenCalled();
  });

  it("does not ask on redo", async () => {
    vi.spyOn(undoRedoService, "fetchState").mockResolvedValue({
      undo: available.undo,
      redo: { available: true, direction: "REDO", changeId: "r", auditId: "au", label: "R", dependents: [] },
    } as any);
    await act(async () => root.render(<Probe />));
    await settle();
    act(() => {
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "y", ctrlKey: true, bubbles: true, cancelable: true }));
    });
    await settle();
    expect(dialog()).toBeNull();
    expect(sendStep).toHaveBeenCalledTimes(1);
  });
});
