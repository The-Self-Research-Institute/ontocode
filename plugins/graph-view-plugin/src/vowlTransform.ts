// vowlTransform.ts
//
// Restructures the filtered graph into real WebVOWL topology before rendering.
// WebVOWL is not just a styling convention — its graph model differs from ours:
//
//  1. subClassOf edges to owl:Thing are never drawn (everything is a subclass of
//     Thing); orphan classes float freely instead of star-wiring into a hub.
//  2. owl:Thing is split into one small dashed node PER EDGE that touches it.
//  3. Datatype nodes (Literal, string, …) are split into one node PER datatype
//     property edge — each green property points at its own yellow Literal box.
//  4. Equivalent classes are merged into a single node drawn with a double border.
//
// Without 1–3 every shared node becomes a high-degree hub and the force layout
// collapses into the "hairball" look; WebVOWL's even spacing falls out of this
// topology naturally.

import type { OntologyNode, OntologyEdge } from './types';

export const isThingIri = (id: string | undefined | null): boolean =>
  !!id && (id === 'owl:Thing' || id.includes('owl#Thing'));

/** Suffix marker for split-out clone nodes; `metadata.cloneOf` holds the original id. */
export const VOWL_CLONE_SEPARATOR = '__vowl__';

export const vowlOriginalNodeId = (id: string): string => {
  const idx = id.indexOf(VOWL_CLONE_SEPARATOR);
  return idx === -1 ? id : id.substring(0, idx);
};

export interface VowlGraph {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
}

export function applyVowlTransform(inputNodes: OntologyNode[], inputEdges: OntologyEdge[]): VowlGraph {
  // Shallow-clone so simulation-side mutation never leaks back into React state
  let nodes: OntologyNode[] = inputNodes.map(n => ({ ...n }));
  let edges: OntologyEdge[] = inputEdges.map(e => ({ ...e }));
  const nodeById = new Map(nodes.map(n => [n.id, n]));

  // ── 1. Merge equivalent classes (union-find over equivalentClass edges) ──
  const parent = new Map<string, string>();
  const find = (x: string): string => {
    let root = x;
    while (parent.get(root) !== undefined && parent.get(root) !== root) {
      root = parent.get(root)!;
    }
    parent.set(x, root);
    return root;
  };
  const union = (a: string, b: string) => {
    if (!parent.has(a)) parent.set(a, a);
    if (!parent.has(b)) parent.set(b, b);
    const ra = find(a);
    const rb = find(b);
    if (ra !== rb) parent.set(rb, ra);
  };

  for (const e of edges) {
    if (e.type !== 'equivalentClass') continue;
    const a = nodeById.get(e.from);
    const b = nodeById.get(e.to);
    // Only merge class↔class equivalences (equivalentProperty reuses this edge type)
    if (a?.type === 'class' && b?.type === 'class' && !isThingIri(a.id) && !isThingIri(b.id)) {
      union(a.id, b.id);
    }
  }

  const groups = new Map<string, string[]>();
  parent.forEach((_v, id) => {
    const root = find(id);
    if (!groups.has(root)) groups.set(root, []);
    groups.get(root)!.push(id);
  });

  const mergedInto = new Map<string, string>();
  groups.forEach(members => {
    if (members.length < 2) return;
    const rep = members[0];
    const repNode = nodeById.get(rep);
    if (!repNode) return;
    const labels = members
      .map(m => nodeById.get(m)?.label)
      .filter((l): l is string => !!l);
    repNode.metadata = {
      ...(repNode.metadata || {}),
      vowlEquivalent: true,
      equivalentLabels: labels
    };
    for (const m of members.slice(1)) mergedInto.set(m, rep);
  });

  if (mergedInto.size > 0) {
    nodes = nodes.filter(n => !mergedInto.has(n.id));
    edges = edges
      .filter(e => {
        // Drop the equivalence edge inside a merged group — it's now one node
        if (e.type !== 'equivalentClass') return true;
        const from = mergedInto.get(e.from) ?? e.from;
        const to = mergedInto.get(e.to) ?? e.to;
        return from !== to;
      })
      .map(e => ({
        ...e,
        from: mergedInto.get(e.from) ?? e.from,
        to: mergedInto.get(e.to) ?? e.to
      }))
      // Merging can create degenerate self-loops from formerly-parallel edges
      .filter(e => !(e.from === e.to && e.type === 'subClassOf'));
  }

  // ── 2. Drop subClassOf → Thing (asserted or synthetic orphan adoption) ──
  edges = edges.filter(e => !(e.type === 'subClassOf' && isThingIri(e.to)));

  // ── 3. Split owl:Thing into one clone per touching edge ──
  const thingNodes = nodes.filter(n => isThingIri(n.id));
  for (const thing of thingNodes) {
    const clones: OntologyNode[] = [];
    for (const e of edges) {
      if (e.from !== thing.id && e.to !== thing.id) continue;
      const cloneId = `${thing.id}${VOWL_CLONE_SEPARATOR}${e.id}`;
      clones.push({
        ...thing,
        id: cloneId,
        // WebVOWL draws its per-neighborhood Thing smaller than regular classes
        size: Math.max(8, Math.round((thing.size ?? 20) * 0.62)),
        metadata: { ...(thing.metadata || {}), cloneOf: thing.id }
      });
      if (e.from === thing.id) e.from = cloneId;
      if (e.to === thing.id) e.to = cloneId;
    }
    nodes.push(...clones);
    // Original Thing is either fully replaced by clones or isolated — WebVOWL
    // shows no free-floating Thing node either way.
    nodes = nodes.filter(n => n.id !== thing.id);
  }

  // ── 4. Split datatype nodes into one clone per touching edge ──
  const datatypeIds = new Set(nodes.filter(n => n.type === 'datatype').map(n => n.id));
  if (datatypeIds.size > 0) {
    const splitOriginals = new Set<string>();
    const dtClones: OntologyNode[] = [];
    for (const e of edges) {
      const fromIsDt = datatypeIds.has(e.from);
      const toIsDt = datatypeIds.has(e.to);
      if (!fromIsDt && !toIsDt) continue;
      const originalId = toIsDt ? e.to : e.from;
      const original = nodeById.get(originalId);
      if (!original) continue;
      const cloneId = `${originalId}${VOWL_CLONE_SEPARATOR}${e.id}`;
      dtClones.push({
        ...original,
        id: cloneId,
        metadata: { ...(original.metadata || {}), cloneOf: originalId }
      });
      if (toIsDt) e.to = cloneId;
      else e.from = cloneId;
      splitOriginals.add(originalId);
    }
    if (splitOriginals.size > 0) {
      nodes = nodes.filter(n => !splitOriginals.has(n.id));
      nodes.push(...dtClones);
    }
  }

  return { nodes, edges };
}
