import type { TreeNode } from "../types";

export const clearChildrenOf = (nodes: TreeNode[], parentId: string): TreeNode[] =>
  nodes.map((n) => {
    if (n.id === parentId) return { ...n, children: [] };
    if (n.children) return { ...n, children: clearChildrenOf(n.children, parentId) };
    return n;
  });

export const keepPendingChildren = <T extends { id: string }>(loaded: T[], pending: T[] | undefined): T[] => {
  if (!pending || pending.length === 0) return loaded;
  const known = new Set(loaded.map((c) => c.id));
  const missing = pending.filter((p) => !known.has(p.id));
  return missing.length === 0 ? loaded : [...loaded, ...missing];
};
