import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { useCodeAssistantRollbackSync } from "../hooks/useCodeAssistantRollbackSync";

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

function makeChat(entries: any[]) {
  const chat: any = {
    entriesRef: { current: entries },
    updateReviewEntry: (id: string, updater: (e: any) => any) => {
      chat.entriesRef.current = chat.entriesRef.current.map((e: any) => (e.id === id ? updater(e) : e));
    },
  };
  return chat;
}

const review = (id: string, groupId: string, undone?: boolean, projectId = "p1") => ({
  id,
  role: "assistant",
  kind: "review",
  projectId,
  groups: [{ serverGroupId: groupId }],
  undo: undone === undefined ? undefined : { [groupId]: { undone } },
});

const emit = (detail: Record<string, unknown>) =>
  act(() => {
    window.dispatchEvent(new CustomEvent("ontologyRollback", { detail }));
  });

describe("useCodeAssistantRollbackSync", () => {
  let root: Root;
  let host: HTMLElement;
  let chat: any;

  function Probe() {
    useCodeAssistantRollbackSync(chat);
    return null;
  }

  beforeEach(() => {
    host = document.createElement("div");
    root = createRoot(host);
  });

  afterEach(() => act(() => root.unmount()));

  const mount = (entries: any[]) => {
    chat = makeChat(entries);
    act(() => root.render(<Probe />));
  };
  const undoneOf = (id: string, groupId: string) =>
    chat.entriesRef.current.find((e: any) => e.id === id).undo?.[groupId]?.undone;

  it("marks a card undone when the set is undone elsewhere", () => {
    mount([review("r1", "g1")]);
    emit({ projectId: "p1", changeSetId: "g1", direction: "UNDO" });
    expect(undoneOf("r1", "g1")).toBe(true);
  });

  it("flips it back on redo", () => {
    mount([review("r1", "g1", true)]);
    emit({ projectId: "p1", changeSetId: "g1", direction: "REDO" });
    expect(undoneOf("r1", "g1")).toBe(false);
  });

  it("ignores other sets, other projects and events without a set", () => {
    mount([review("r1", "g1"), review("r2", "g1", undefined, "p2")]);
    emit({ projectId: "p1", changeSetId: "other", direction: "UNDO" });
    emit({ projectId: "p1", changeId: "c1" });
    emit({ projectId: "p1", changeSetId: "g1", direction: "SIDEWAYS" });
    expect(undoneOf("r1", "g1")).toBeUndefined();
    emit({ projectId: "p1", changeSetId: "g1", direction: "UNDO" });
    expect(undoneOf("r1", "g1")).toBe(true);
    expect(undoneOf("r2", "g1")).toBeUndefined();
  });
});
