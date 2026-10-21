import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import undoRedoService from "../services/undoRedoService";
import { undoQueue } from "../services/undoQueue";
import { useOntologyUndoRedo } from "../hooks/useOntologyUndoRedo";

const mergeToUndo: any = {
  undo: { available: true, direction: "UNDO", changeId: "m1", label: "Ontology merge", dependents: [] },
  redo: { available: false, direction: "REDO", dependents: [] },
};

let root: Root;
let host: HTMLDivElement;
let latest: ReturnType<typeof useOntologyUndoRedo> | undefined;

function Probe({ projectId, draft }: { projectId: string; draft: boolean }) {
  latest = useOntologyUndoRedo({ projectId, draft });
  return null;
}

const wait = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });

async function pauseOnAConflict() {
  vi.spyOn(undoRedoService, "fetchState").mockResolvedValue(mergeToUndo);
  vi.spyOn(undoRedoService, "fetchQueue").mockResolvedValue({ steps: [{ changeId: "m1", label: "Ontology merge" }], blocked: null });
  vi.spyOn(undoRedoService, "sendStep").mockResolvedValue({
    kind: "refused",
    message: "Nothing was undone: Ontology merge was changed since this edit",
  });
  vi.spyOn(undoRedoService, "notifyRefusal").mockImplementation(() => undefined);
  await act(async () => root.render(<Probe projectId="p1" draft />));
  await wait(30);
  await act(async () => { await latest?.menu.undo(); });
  await wait(30);
  expect(undoQueue.getSnapshot().phase).toBe("paused");
  expect(undoQueue.getSnapshot().reason).toBe("Conflict");
}

function mutated(projectId: string, ops: string[]) {
  act(() => { window.dispatchEvent(new CustomEvent("ontology:mutated", { detail: { projectId, ops } })); });
}

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

describe("paused undo run", () => {
  it("is cleared once the draft is approved as a pull request", async () => {
    await pauseOnAConflict();
    mutated("p1", ["draftPrApproved"]);
    await wait(30);
    expect(undoQueue.getSnapshot().phase).toBe("idle");
    expect(undoQueue.getSnapshot().message).toBeUndefined();
    expect(undoQueue.getSnapshot().pending).toBe(0);
  });

  it("is cleared once the draft is published", async () => {
    await pauseOnAConflict();
    mutated("p1", ["draftPublish"]);
    await wait(30);
    expect(undoQueue.getSnapshot().phase).toBe("idle");
  });

  it("is cleared when switching from draft to public", async () => {
    await pauseOnAConflict();
    await act(async () => root.render(<Probe projectId="p1" draft={false} />));
    await wait(30);
    expect(undoQueue.getSnapshot().phase).toBe("idle");
  });

  it("stays for an ordinary edit and for a publish in another project", async () => {
    await pauseOnAConflict();
    mutated("p1", ["createClass"]);
    mutated("other", ["draftPublish"]);
    await wait(30);
    expect(undoQueue.getSnapshot().phase).toBe("paused");
  });
});
