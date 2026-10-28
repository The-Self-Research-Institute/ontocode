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

const wait = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });

function mountWith(pr: Record<string, unknown>) {
  get.mockImplementation(async (url: string) => {
    if (url.endsWith("/draft-prs")) {
      return { prs: [{ id: "pr1", projectId: "p1", authorId: "author1", authorUsername: "Author", title: "T",
        status: "OPEN", changeCount: 2, submittedChangeIds: ["d1", "d2"], createdAt: "2026-10-20", ...pr }] };
    }
    if (url.endsWith("/drafts")) return { drafts: [] };
    return {};
  });
  return act(async () => root.render(
    <DraftPRPanel isOpen onClose={() => undefined} projectId="p1" userId="author1" username="Author"
      canReview={false} canRaisePR draftCount={2} />,
  ));
}

const updateButtons = () => Array.from(host.querySelectorAll("button")).filter((b) => b.textContent?.includes("Update this PR"));

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  get.mockReset();
  post.mockReset();
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe("Update this PR to my current draft", () => {
  const cases: Array<[string, Record<string, unknown>, boolean]> = [
    ["has a saved copy and the draft is unchanged", { snapshotKey: "pr-snapshot-pr1", draftChanged: false }, false],
    ["has no saved copy and the draft is unchanged", { draftChanged: false }, false],
    ["has a saved copy and the draft has newer changes", { snapshotKey: "pr-snapshot-pr1", draftChanged: true, currentChangeCount: 3 }, true],
    ["has no saved copy and the draft has newer changes", { draftChanged: true, currentChangeCount: 3 }, true],
    ["is an old one that still needs its first saved copy", { needsCopy: true, draftChanged: false }, true],
  ];

  it.each(cases)("PR that %s", async (_name, pr, shown) => {
    await mountWith(pr);
    await wait(20);
    expect(updateButtons().length > 0).toBe(shown);
  });
});
