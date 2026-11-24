import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import EntityHierarchy from "../components/EntityHierarchy";

vi.mock("../custom-hook/useAuth", () => ({ useAuth: () => ({ user: { userId: "u1", username: "tester", email: "t@example.org" } }) }));

vi.mock("../contexts/CollaborationContext", () => ({
  useCollaboration: () => ({
    state: { locks: new Map(), activeUsers: new Map(), currentProjectId: null },
    publishCursor: () => undefined,
    requestLock: () => undefined,
    releaseLock: () => undefined,
  }),
}));


const SHARED = "http://example.org/shared";

function multiParentTree(parents: number) {
  return Array.from({ length: parents }, (_, i) => ({
    id: `http://example.org/parent${i}`,
    label: `Parent ${i}`,
    hasChildren: true,
    children: [{ id: SHARED, label: "Shared child", hasChildren: false }],
  }));
}

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.restoreAllMocks();
});

describe("class tree with a class that has several parents", () => {
  it("renders every row of the window without React duplicate key warnings", () => {
    const errors: string[] = [];
    vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => { errors.push(args.map(String).join(" ")); });
    const data: any[] = multiParentTree(150);
    act(() => {
      root.render(
        <EntityHierarchy
          entitiesTab="Classes"
          filteredData={data}
          selectedItem={null}
          expandedNodes={data.map((n) => n.id)}
          searchQuery=""
          onSearchQueryChange={() => undefined}
          onSelectItem={() => undefined}
          onToggleNode={() => undefined}
          onAddItem={() => undefined}
          onDeleteItem={() => undefined}
          viewMode="asserted"
        />,
      );
    });
    const duplicateKeyWarnings = errors.filter((e) => /same key|unique "key"/i.test(e));
    expect(duplicateKeyWarnings).toEqual([]);
    expect(host.textContent).toContain("Shared child");
    expect(host.textContent?.match(/Shared child/g)?.length).toBeGreaterThan(1);
  });
});
