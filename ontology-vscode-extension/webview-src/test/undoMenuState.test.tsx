import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import undoRedoService from "../services/undoRedoService";
import { undoQueue } from "../services/undoQueue";
import { useOntologyUndoRedo } from "../hooks/useOntologyUndoRedo";
import UndoEditMenuItems from "../components/UndoEditMenuItems";
import { emitCommittedChange } from "../utils/committedChange";

const nothing: any = { undo: { available: false, direction: "UNDO", dependents: [] }, redo: { available: false, direction: "REDO", dependents: [] } };
const something: any = {
  undo: { available: true, direction: "UNDO", changeId: "c1", label: "Created class Pizza", dependents: [] },
  redo: { available: false, direction: "REDO", dependents: [] },
};

let root: Root;
let host: HTMLDivElement;
let latest: ReturnType<typeof useOntologyUndoRedo> | undefined;

function Probe({ projectId }: { projectId: string }) {
  latest = useOntologyUndoRedo({ projectId });
  return null;
}

const wait = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  latest = undefined;
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  undoQueue.reset();
  vi.restoreAllMocks();
});

describe("undo menu state", () => {
  it("starts with nothing to undo and shows the change as soon as an edit is committed", async () => {
    const fetchState = vi.spyOn(undoRedoService, "fetchState").mockResolvedValue(nothing);
    await act(async () => root.render(<Probe projectId="p1" />));
    await wait(30);
    expect(latest?.menu.canUndo).toBe(false);
    expect(latest?.menu.undoLabel).toBeUndefined();

    fetchState.mockResolvedValue(something);
    act(() => { emitCommittedChange({ projectId: "p1", direction: "APPLY", changeSetId: "cs-1", draft: false }); });
    await wait(450);
    expect(latest?.menu.canUndo).toBe(true);
    expect(latest?.menu.undoLabel).toBe("Created class Pizza");
  });

  it("ignores edits made in another project", async () => {
    const fetchState = vi.spyOn(undoRedoService, "fetchState").mockResolvedValue(nothing);
    await act(async () => root.render(<Probe projectId="p1" />));
    await wait(30);
    const before = fetchState.mock.calls.length;
    act(() => { emitCommittedChange({ projectId: "other", direction: "APPLY", changeSetId: "cs-2", draft: false }); });
    await wait(450);
    expect(fetchState.mock.calls.length).toBe(before);
  });

  it("refreshes after a save announced by Code View or another editor", async () => {
    const fetchState = vi.spyOn(undoRedoService, "fetchState").mockResolvedValue(nothing);
    await act(async () => root.render(<Probe projectId="p1" />));
    await wait(30);
    const before = fetchState.mock.calls.length;
    act(() => { window.dispatchEvent(new CustomEvent("ontologyChangesSaved", { detail: { projectId: "p1" } })); });
    await wait(30);
    expect(fetchState.mock.calls.length).toBeGreaterThan(before);
  });

  it("asks for fresh state every time the Edit menu opens", async () => {
    const refresh = vi.fn();
    const menu: any = { canUndo: false, canRedo: false, undo: vi.fn(), redo: vi.fn(), refresh };
    await act(async () => root.render(<UndoEditMenuItems menu={menu} onClose={() => undefined} />));
    expect(refresh).toHaveBeenCalledTimes(1);
  });
});
