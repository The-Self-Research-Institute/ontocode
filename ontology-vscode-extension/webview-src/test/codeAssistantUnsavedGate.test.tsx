import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CodeHighlighter } from "../components/CodeHighlighter";
import { CodeAssistantReviewGroups } from "../components/CodeAssistantReviewGroups";
import { resolveApplyBlock, UNSAVED_CODE_VIEW_MESSAGE } from "../components/codeAssistantPanelHelpers";
import type { ProposedEditGroupResult } from "../services/codeAssistantSession";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.spyOn(console, "log").mockImplementation(() => {});
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

function buttonByText(text: string): HTMLButtonElement {
  const match = Array.from(container.querySelectorAll("button")).find((b) => b.textContent?.trim().startsWith(text));
  if (!match) throw new Error(`No button starting with "${text}"`);
  return match as HTMLButtonElement;
}

function typeInto(textarea: HTMLTextAreaElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set;
  setter?.call(textarea, value);
  textarea.dispatchEvent(new Event("input", { bubbles: true }));
}

const group = (id: string): ProposedEditGroupResult => ({
  clientGroupId: `c-${id}`,
  serverGroupId: id,
  validation: { passed: true, checks: [] },
  diff: [{ targetPath: "a.ttl", before: "old", after: "new" }],
});

describe("resolveApplyBlock", () => {
  it("blocks applying while Code View has unsaved changes", () => {
    expect(resolveApplyBlock({ hasUnsavedCodeViewChanges: true, recoveryLocked: false })?.message).toBe(UNSAVED_CODE_VIEW_MESSAGE);
    expect(resolveApplyBlock({ hasUnsavedCodeViewChanges: false, recoveryLocked: false })).toBeNull();
  });

  it("puts the recovery lock ahead of unsaved changes", () => {
    expect(resolveApplyBlock({ hasUnsavedCodeViewChanges: true, recoveryLocked: true })?.shortReason).toBe("the project is locked for recovery");
  });
});

describe("CodeHighlighter unsaved-changes reporting", () => {
  it("reports edits, a save, and unmounting to its parent", async () => {
    const onUnsaved = vi.fn();
    const onSave = vi.fn();
    act(() => {
      root.render(
        <CodeHighlighter
          content={"@prefix ex: <http://example.org/> .\nex:A a ex:B .\n"}
          format="turtle"
          onContentChange={() => {}}
          onSaveContent={onSave}
          onUnsavedChangesChange={onUnsaved}
        />,
      );
    });
    expect(onUnsaved).toHaveBeenLastCalledWith(false);

    act(() => buttonByText("Edit").click());
    const textarea = container.querySelector("textarea") as HTMLTextAreaElement;
    expect(textarea).not.toBeNull();
    act(() => typeInto(textarea, "@prefix ex: <http://example.org/> .\nex:A a ex:C .\n"));
    expect(onUnsaved).toHaveBeenLastCalledWith(true);

    act(() => buttonByText("Save").click());
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onUnsaved).toHaveBeenLastCalledWith(false);

    act(() => buttonByText("View").click());
    act(() => buttonByText("Edit").click());
    act(() => typeInto(container.querySelector("textarea") as HTMLTextAreaElement, "changed again"));
    expect(onUnsaved).toHaveBeenLastCalledWith(true);
    act(() => root.render(<div />));
    expect(onUnsaved).toHaveBeenLastCalledWith(false);
  });
});

describe("CodeAssistantReviewGroups apply gate", () => {
  it("disables Apply and Apply All and explains why while applying is blocked", () => {
    const onApply = vi.fn();
    const onApplyAll = vi.fn();
    act(() => {
      root.render(
        <CodeAssistantReviewGroups
          groups={[group("g1"), group("g2")]}
          decisions={{ g1: "pending", g2: "pending" }}
          onApply={onApply}
          onSkip={() => {}}
          onApplyAll={onApplyAll}
          applyBlockedReason={UNSAVED_CODE_VIEW_MESSAGE}
        />,
      );
    });
    expect(container.textContent).toContain(UNSAVED_CODE_VIEW_MESSAGE);
    const applyButtons = Array.from(container.querySelectorAll("button")).filter((b) => b.textContent?.trim() === "Apply");
    expect(applyButtons).toHaveLength(2);
    applyButtons.forEach((b) => expect(b.disabled).toBe(true));
    expect(buttonByText("Apply All").disabled).toBe(true);
    act(() => applyButtons[0].click());
    expect(onApply).not.toHaveBeenCalled();
  });

  it("enables applying again once the block clears", () => {
    act(() => {
      root.render(
        <CodeAssistantReviewGroups
          groups={[group("g1"), group("g2")]}
          decisions={{ g1: "pending", g2: "pending" }}
          onApply={() => {}}
          onSkip={() => {}}
          onApplyAll={() => {}}
          applyBlockedReason={null}
        />,
      );
    });
    expect(container.textContent).not.toContain(UNSAVED_CODE_VIEW_MESSAGE);
    expect(buttonByText("Apply All").disabled).toBe(false);
  });

  it("shows progress and a stop control during Apply All, and hides Apply for a group needing recovery", () => {
    const onCancel = vi.fn();
    act(() => {
      root.render(
        <CodeAssistantReviewGroups
          groups={[group("g1"), group("g2"), group("g3"), group("g4")]}
          decisions={{ g1: "applied", g2: "applying", g3: "pending", g4: "recovery" }}
          errors={{ g4: "The change did not finish cleanly." }}
          onApply={() => {}}
          onSkip={() => {}}
          onApplyAll={() => {}}
          onCancelApplyAll={onCancel}
          applyAllRun={{ running: true, position: 2, total: 4, cancelRequested: false }}
        />,
      );
    });
    expect(container.textContent).toContain("Applying 2 of 4");
    act(() => buttonByText("Stop after this group").click());
    expect(onCancel).toHaveBeenCalledTimes(1);
    const cards = Array.from(container.querySelectorAll("div.border-2"));
    expect(cards[3].textContent).toContain("The change did not finish cleanly.");
    expect(Array.from(cards[3].querySelectorAll("button")).some((b) => b.textContent?.trim() === "Apply")).toBe(false);
  });
});
