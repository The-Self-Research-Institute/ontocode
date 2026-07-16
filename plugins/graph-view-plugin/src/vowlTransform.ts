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

export interface VowlTransformOptions {
  /** Merge owl:equivalentClass into one double-border node (WebVOWL default). */
  mergeEquivalents?: boolean;
}

const localNameFromIri = (iri: string): string => {
  const hash = iri.lastIndexOf('#');
  const slash = iri.lastIndexOf('/');
  const cut = Math.max(hash, slash);
  return cut >= 0 ? iri.slice(cut + 1) : iri;
};

export interface VowlNeighborhood {
  hubId: string;
  memberIds: string[];
}

/**
 * Partition the VOWL graph into WebVOWL-style neighborhoods.
 * Hubs = real classes that carry properties (or are roots). Pure subclass
 * leaves attach to their parent's neighborhood so Agent/Person/Document stay
 * separate without Organization/OnlineChatAccount fighting them via minSep.
 */
export function buildVowlNeighborhoods(
  nodes: OntologyNode[],
  edges: OntologyEdge[]
): VowlNeighborhood[] {
  const adj = new Map<string, string[]>();
  const ensure = (id: string) => {
    if (!adj.has(id)) adj.set(id, []);
  };
  for (const n of nodes) ensure(n.id);
  for (const e of edges) {
    ensure(e.from);
    ensure(e.to);
    adj.get(e.from)!.push(e.to);
    adj.get(e.to)!.push(e.from);
  }

  const degree = (id: string) => (adj.get(id)?.length ?? 0);
  const isRealClass = (n: OntologyNode) =>
    n.type === 'class' && !isThingIri(n.id) && n.label !== 'Thing' && !(n as any).metadata?.cloneOf;

  // Property/axiom degree (excludes subClassOf) — this is what makes a "visual hub"
  const propDegree = new Map<string, number>();
  const parents = new Map<string, string[]>(); // child → superclasses
  for (const e of edges) {
    if (e.type === 'subClassOf') {
      if (!parents.has(e.from)) parents.set(e.from, []);
      parents.get(e.from)!.push(e.to);
      continue;
    }
    propDegree.set(e.from, (propDegree.get(e.from) ?? 0) + 1);
    propDegree.set(e.to, (propDegree.get(e.to) ?? 0) + 1);
  }

  const nodeById = new Map(nodes.map(n => [n.id, n]));
  const realClasses = nodes.filter(isRealClass);

  // A hub is a class that owns properties, or has no superclass in-graph (root)
  const isHubClass = (id: string) => {
    const pd = propDegree.get(id) ?? 0;
    if (pd >= 1) return true;
    const supers = (parents.get(id) || []).filter(p => {
      const n = nodeById.get(p);
      return n && isRealClass(n);
    });
    return supers.length === 0;
  };

  const hubs = realClasses
    .filter(n => isHubClass(n.id))
    .sort((a, b) => (propDegree.get(b.id) ?? 0) - (propDegree.get(a.id) ?? 0));

  const hubIds = hubs.map(h => h.id);
  const assigned = new Map<string, string>(); // nodeId → hubId
  for (const h of hubIds) assigned.set(h, h);

  // Attach pure subclass leaves upward to the nearest hub ancestor
  for (const n of realClasses) {
    if (assigned.has(n.id)) continue;
    let cur: string | undefined = n.id;
    const seen = new Set<string>();
    while (cur && !seen.has(cur)) {
      seen.add(cur);
      if (assigned.has(cur) && hubIds.includes(assigned.get(cur)!)) {
        assigned.set(n.id, assigned.get(cur)!);
        break;
      }
      if (hubIds.includes(cur)) {
        assigned.set(n.id, cur);
        break;
      }
      cur = (parents.get(cur) || [])[0];
    }
    if (!assigned.has(n.id)) {
      // Fallback: treat as its own hub
      hubIds.push(n.id);
      assigned.set(n.id, n.id);
    }
  }

  // Attach Literals / Thing-clones / individuals only (never walk into other hubs)
  const isAttachable = (id: string) => {
    const n = nodeById.get(id);
    if (!n) return false;
    if (n.type === 'datatype' || n.type === 'individual' || n.type === 'setOperator') return true;
    if (isThingIri(n.id) || n.label === 'Thing' || n.metadata?.cloneOf) return true;
    return false;
  };

  const queue = [...hubIds];
  while (queue.length > 0) {
    const cur = queue.shift()!;
    const hub = assigned.get(cur)!;
    for (const nb of adj.get(cur) || []) {
      if (assigned.has(nb)) continue;
      if (!isAttachable(nb)) continue;
      assigned.set(nb, hub);
      queue.push(nb);
    }
  }

  // Leftover islands (Thing→Literal stars): own neighborhoods
  const leftovers = nodes.map(n => n.id).filter(id => !assigned.has(id));
  const seen = new Set<string>();
  for (const start of leftovers) {
    if (seen.has(start)) continue;
    const comp: string[] = [];
    const q = [start];
    seen.add(start);
    while (q.length) {
      const c = q.shift()!;
      comp.push(c);
      for (const nb of adj.get(c) || []) {
        if (seen.has(nb) || assigned.has(nb)) continue;
        seen.add(nb);
        q.push(nb);
      }
    }
    const localHub = comp.slice().sort((a, b) => degree(b) - degree(a))[0];
    for (const id of comp) assigned.set(id, localHub);
    if (!hubIds.includes(localHub)) hubIds.push(localHub);
  }

  const byHub = new Map<string, string[]>();
  assigned.forEach((hub, id) => {
    if (!byHub.has(hub)) byHub.set(hub, []);
    byHub.get(hub)!.push(id);
  });

  return hubIds
    .filter(h => byHub.has(h))
    .map(h => ({ hubId: h, memberIds: byHub.get(h)! }));
}

/**
 * Place neighborhood centroids on a wide ring/grid so clusters start SEPARATE
 * (WebVOWL look). Class hubs sit on an inner ring; Thing/Literal-only stars
 * park on an outer ring — that's the clean "peripheral clusters" look.
 */
export function placeVowlNeighborhoods(
  neighborhoods: VowlNeighborhood[],
  width: number,
  height: number
): Map<string, { x: number; y: number }> {
  const positions = new Map<string, { x: number; y: number }>();
  const cx = width / 2;
  const cy = height / 2;

  const isPeripheralHub = (hubId: string) =>
    isThingIri(hubId) || hubId.includes(VOWL_CLONE_SEPARATOR);

  const classNbs = neighborhoods.filter(nb => !isPeripheralHub(nb.hubId));
  const periNbs = neighborhoods.filter(nb => isPeripheralHub(nb.hubId));

  const placeOnRing = (
    list: VowlNeighborhood[],
    ringR: number,
    startAngle = -Math.PI / 2
  ) => {
    const n = Math.max(1, list.length);
    list.forEach((nb, i) => {
      let hx: number;
      let hy: number;
      if (n === 1) {
        hx = cx + (list === periNbs ? -ringR * 0.85 : 0);
        hy = cy + (list === periNbs ? -ringR * 0.15 : 0);
      } else {
        const angle = startAngle + (i / n) * Math.PI * 2;
        hx = cx + Math.cos(angle) * ringR;
        hy = cy + Math.sin(angle) * ringR;
      }
      nb.memberIds.forEach((id, mi) => {
        if (id === nb.hubId) {
          positions.set(id, { x: hx, y: hy });
          return;
        }
        const a = (mi / Math.max(1, nb.memberIds.length)) * Math.PI * 2;
        const r = 48 + (mi % 3) * 22;
        positions.set(id, { x: hx + Math.cos(a) * r, y: hy + Math.sin(a) * r });
      });
    });
  };

  // Inner ring = real classes (Person, Agent, Document…) — WebVOWL's main cloud
  const innerR = Math.max(280, Math.min(cx, cy) * 0.62);
  // Outer ring = Thing→prop→Literal stars — WebVOWL parks these on the fringe
  const outerR = Math.max(520, Math.min(cx, cy) * 1.15);

  if (classNbs.length === 0 && periNbs.length > 0) {
    placeOnRing(periNbs, outerR);
  } else {
    placeOnRing(classNbs, innerR);
    placeOnRing(periNbs, outerR, Math.PI / 6);
  }

  return positions;
}

export function applyVowlTransform(
  inputNodes: OntologyNode[],
  inputEdges: OntologyEdge[],
  options: VowlTransformOptions = {}
): VowlGraph {
  const mergeEquivalents = options.mergeEquivalents !== false;
  // Shallow-clone so simulation-side mutation never leaks back into React state
  let nodes: OntologyNode[] = inputNodes.map(n => ({ ...n }));
  let edges: OntologyEdge[] = inputEdges.map(e => ({ ...e }));
  const nodeById = new Map(nodes.map(n => [n.id, n]));

  // ── 1. Merge equivalent classes (union-find over equivalentClass edges) ──
  if (mergeEquivalents) {
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
    // Prefer the first declared (often ontology-local) class as the representative
    const rep = members[0];
    const repNode = nodeById.get(rep);
    if (!repNode) return;
    // WebVOWL label style: "Document, CreativeWork" — unique display names.
    // When two classes share the same rdfs:label (foaf:Person ≡ schema:Person),
    // keep a single "Person" rather than "Person, Person".
    const labels: string[] = [];
    const seen = new Set<string>();
    for (const m of members) {
      const n = nodeById.get(m);
      const raw = (n?.label || '').trim() || localNameFromIri(m);
      const key = raw.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      labels.push(raw);
    }
    if (labels.length === 0) labels.push(repNode.label || localNameFromIri(rep));
    repNode.label = labels.join(', ');
    // Slightly larger so multi-name labels fit (WebVOWL also grows these)
    repNode.size = Math.max(repNode.size ?? 20, 22 + Math.min(10, (labels.length - 1) * 4));
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
  } // end mergeEquivalents

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
