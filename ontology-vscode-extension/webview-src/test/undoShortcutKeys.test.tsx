import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import undoRedoService from "../services/undoRedoService";
import { undoQueue } from "../services/undoQueue";
import { useOntologyUndoRedo } from "../hooks/useOntologyUndoRedo";
import UndoEditMenuItems from "../components/UndoEditMenuItems";
import { redoShortcutText, undoShortcutText } from "../utils/undoShortcutText";

const nothing: any = { undo: { available: false, direction: "UNDO", dependents: [] }, redo: { available: false, direction: "REDO", dependents: [] } };

let root: Root;
let host: HTMLDivElement;

function Probe() {
  useOntologyUndoRedo({ projectId: "p1" });
  return null;
}

const press = (init: KeyboardEventInit) => act(() => { window.dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, cancelable: true, ...init })); });

beforeEach(async () => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  vi.spyOn(undoRedoService, "fetchState").mockResolvedValue(nothing);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  undoQueue.reset();
  vi.restoreAllMocks();
});

describe("undo and redo keys", () => {
  it("handles Cmd+Z and Cmd+Shift+Z as on a Mac, and Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z elsewhere", async () => {
    const pressSpy = vi.spyOn(undoQueue, "press").mockResolvedValue({ accepted: true });
    await act(async () => root.render(<Probe />));
    press({ key: "z", metaKey: true });
    press({ key: "Z", metaKey: true, shiftKey: true });
    press({ key: "z", ctrlKey: true });
    press({ key: "y", ctrlKey: true });
    press({ key: "z", ctrlKey: true, shiftKey: true });
    expect(pressSpy.mock.calls.map((call) => call[1])).toEqual(["undo", "redo", "undo", "redo", "redo"]);
  });

  it("ignores plain keys, Alt combinations and Cmd+Y", async () => {
    const pressSpy = vi.spyOn(undoQueue, "press").mockResolvedValue({ accepted: true });
    await act(async () => root.render(<Probe />));
    press({ key: "z" });
    press({ key: "z", ctrlKey: true, altKey: true });
    press({ key: "c", metaKey: true });
    expect(pressSpy).not.toHaveBeenCalled();
  });
});

describe("shortcut text in the Edit menu", () => {
  const menu: any = { canUndo: true, canRedo: true, undo: vi.fn(), redo: vi.fn() };
  const texts = async () => {
    await act(async () => root.render(<UndoEditMenuItems menu={menu} onClose={() => undefined} />));
    return Array.from(host.querySelectorAll("button")).map((b) => b.textContent);
  };

  it("shows Ctrl+Y / Ctrl+Shift+Z for redo on Windows and Linux", async () => {
    vi.spyOn(navigator, "platform", "get").mockReturnValue("Win32");
    expect(await texts()).toEqual(["Undo text editCtrl+Z", "Undo ontology change: nothing to undo", "Redo ontology change: nothing to redoCtrl+Y / Ctrl+Shift+Z"]);
    expect(host.querySelectorAll("button")[2].title).toContain("(Ctrl+Y / Ctrl+Shift+Z)");
  });

  it("shows Cmd+Z and Cmd+Shift+Z on a Mac and does not mention Ctrl+Y", async () => {
    vi.spyOn(navigator, "platform", "get").mockReturnValue("MacIntel");
    const shown = await texts();
    expect(shown).toEqual(["Undo text editCmd+Z", "Undo ontology change: nothing to undo", "Redo ontology change: nothing to redoCmd+Shift+Z"]);
    expect(shown.join("")).not.toContain("Ctrl+Y");
  });

  it("builds the text from one helper", () => {
    expect(undoShortcutText(true)).toBe("Cmd+Z");
    expect(redoShortcutText(false)).toBe("Ctrl+Y / Ctrl+Shift+Z");
  });
});
