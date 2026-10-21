import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const get = vi.fn();
const post = vi.fn();
vi.mock("../services/apiClient", () => ({
  default: { get: (...args: unknown[]) => get(...args), post: (...args: unknown[]) => post(...args) },
}));

import { DraftPRPanel } from "../components/DraftPRPanel";

let root: Root;
let host: HTMLDivElement;
let pending = 1;
let status = "OPEN";

const wait = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });

function click(element: Element | undefined) {
  expect(element).toBeTruthy();
  act(() => { element!.dispatchEvent(new MouseEvent("click", { bubbles: true })); });
}

function button(text: string): HTMLButtonElement | undefined {
  return Array.from(host.querySelectorAll("button")).find((b) => b.textContent?.includes(text));
}

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  pending = 1;
  status = "OPEN";
  get.mockReset();
  post.mockReset();
  get.mockImplementation(async (url: string) => {
    if (url.endsWith("/draft-prs")) {
      return {
        prs: [{ id: "pr1", projectId: "p1", authorId: "u1", authorUsername: "Owner", title: "Merge other ontology",
          status, changeCount: 1, createdAt: "2026-10-20" }],
      };
    }
    if (url.endsWith("/drafts/stats")) {
      return { unappliedDrafts: pending, totalDrafts: pending };
    }
    return {};
  });
  post.mockImplementation(async (url: string) => {
    if (url.endsWith("/approve")) {
      status = "APPROVED";
      pending = 0;
    }
    return { success: true };
  });
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe("approving a pull request", () => {
  it("re-reads the draft count so the panel no longer offers to submit the merged change", async () => {
    const onApproved = vi.fn();
    await act(async () => root.render(
      <DraftPRPanel isOpen onClose={() => undefined} projectId="p1" userId="u1" username="Owner"
        canReview canRaisePR draftCount={1} onPRApproved={onApproved} />,
    ));
    await wait(30);

    click(Array.from(host.querySelectorAll("div")).find((d) => d.textContent === "Merge other ontology"));
    await wait(30);
    click(button("Review"));
    click(button("Approve & Merge"));
    await wait(50);

    expect(onApproved).toHaveBeenCalledTimes(1);
    const statsReads = get.mock.calls.filter(([url]) => String(url).endsWith("/drafts/stats"));
    expect(statsReads.length).toBe(2);
    const raise = button("Raise Pull Request");
    expect(raise?.disabled).toBe(true);
    expect(raise?.title).toBe("Make draft changes before raising a PR");
  });
});
