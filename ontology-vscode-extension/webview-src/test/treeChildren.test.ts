import { describe, expect, it } from "vitest";
import { clearChildrenOf, keepPendingChildren } from "../utils/treeChildren";
import type { TreeNode } from "../types";

const node = (id: string, children?: TreeNode[]): TreeNode => ({ id, label: id, children } as TreeNode);

describe("clearChildrenOf", () => {
  it("empties the children of the matching nested node only", () => {
    const tree = [node("a", [node("b", [node("c")]), node("d", [node("e")])])];
    const out = clearChildrenOf(tree, "b");
    expect(out[0].children![0].children).toEqual([]);
    expect(out[0].children![1].children).toHaveLength(1);
  });
});

describe("keepPendingChildren", () => {
  it("keeps a just-created child the server list does not include yet", () => {
    const out = keepPendingChildren([node("x")], [node("new")]);
    expect(out.map((c) => c.id)).toEqual(["x", "new"]);
  });

  it("does not duplicate a child the server already returned", () => {
    const out = keepPendingChildren([node("x"), node("new")], [node("new")]);
    expect(out.map((c) => c.id)).toEqual(["x", "new"]);
  });

  it("returns the loaded list when nothing is pending", () => {
    const loaded = [node("x")];
    expect(keepPendingChildren(loaded, undefined)).toBe(loaded);
  });
});
