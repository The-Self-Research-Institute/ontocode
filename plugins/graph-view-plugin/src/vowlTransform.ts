// vowlTransform.ts
//
// Restructures the filtered graph into real VOWL topology before rendering.
// VOWL is not just a styling convention — its graph model differs from ours:
//
//  1. subClassOf edges to owl:Thing are never drawn (everything is a subclass of
//     Thing); orphan classes float freely instead of star-wiring into a hub.
//  2. owl:Thing is split into one small dashed node PER EDGE that touches it.
//  3. Datatype nodes (Literal, string, …) are split into one node PER datatype
//     property edge — each green property points at its own yellow Literal box.
//  4. Equivalent classes are merged into a single node drawn with a double border.
//
// Without 1–3 every shared node becomes a high-degree hub and the force layout
// collapses into the "hairball" look; VOWL's even spacing falls out of this
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
  /** Merge owl:equivalentClass into one double-border node (VOWL default). */
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
 * Partition the VOWL graph into VOWL-style neighborhoods.
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

  // Attach Literals / single-use Things / individuals only (never walk into
  // other hubs). A shared Thing remains its own hub so all domainless
  // properties visibly converge on one node, matching VOWL.
  const isAttachable = (id: string) => {
    const n = nodeById.get(id);
    if (!n) return false;
    if (n.type === 'datatype' || n.type === 'individual' || n.type === 'setOperator') return true;
    if (n.metadata?.cloneOf) return true;
    if (isThingIri(n.id) || n.label === 'Thing') return degree(id) <= 1;
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

const isRealClassNode = (id: string, nodeById: Map<string, OntologyNode>): boolean => {
  const n = nodeById.get(id);
  return !!n && n.type === 'class' && !isThingIri(id) && n.label !== 'Thing' && !id.includes(VOWL_CLONE_SEPARATOR);
};

// VOWL neighborhood star:
//   INNER = Literals / Things (grow radius with count so labels don't stack)
//   OUTER = subclasses
const PROP_RING_BASE = 130;
const PROP_MIN_CHORD = 110; // room for property chips between Literals
const SUBCLASS_GAP = 110;   // subclasses sit outside the property star

/** Radius so n items on a circle keep ≈minChord between neighbors. */
function starRadiusForCount(count: number, base = PROP_RING_BASE, minChord = PROP_MIN_CHORD): number {
  if (count <= 1) return base;
  const need = minChord / (2 * Math.sin(Math.PI / count));
  return Math.max(base, need);
}

function placeEvenCircle(
  ids: string[],
  cx: number,
  cy: number,
  radius: number,
  positions: Map<string, { x: number; y: number }>,
  startAngle = -Math.PI / 2
): void {
  const n = ids.length;
  if (n === 0) return;
  ids.forEach((id, i) => {
    const angle = startAngle + (i / n) * Math.PI * 2;
    positions.set(id, { x: cx + Math.cos(angle) * radius, y: cy + Math.sin(angle) * radius });
  });
}

/**
 * VOWL-style star around one class: properties inside, subclasses outside.
 * Ring size grows with property count so chips/Literals don't overlap.
 */
function placeVowlClassStar(
  classId: string,
  x: number,
  y: number,
  childrenOf: Map<string, string[]>,
  edges: OntologyEdge[],
  nodeById: Map<string, OntologyNode>,
  positions: Map<string, { x: number; y: number }>,
  placedClasses: Set<string>
): void {
  if (placedClasses.has(classId)) return;
  placedClasses.add(classId);
  positions.set(classId, { x, y });

  const inner: string[] = [];
  for (const e of edges) {
    if (e.type !== 'propertyRelation') continue;
    const other = e.from === classId ? e.to : e.to === classId ? e.from : null;
    if (!other || positions.has(other)) continue;
    const n = nodeById.get(other);
    if (!n) continue;
    // Only Literals / Things sit on the property ring. Other classes stay independent
    // hubs (VOWL) — claiming them here left their subclass trees unplaced.
    if (n.type === 'datatype' || isThingIri(other) || n.label === 'Thing' || other.includes(VOWL_CLONE_SEPARATOR)) {
      inner.push(other);
    }
  }
  const uniqInner = [...new Set(inner)];
  const subclasses = (childrenOf.get(classId) || []).filter(id => !placedClasses.has(id));

  // How many properties share each inner endpoint (Document↔Thing can be 5+)
  const innerDegree = new Map<string, number>();
  for (const e of edges) {
    if (e.type !== 'propertyRelation') continue;
    const other = e.from === classId ? e.to : e.to === classId ? e.from : null;
    if (!other || !uniqInner.includes(other)) continue;
    innerDegree.set(other, (innerDegree.get(other) ?? 0) + 1);
  }

  const propR = starRadiusForCount(Math.max(1, uniqInner.length));
  const subclassR = propR + SUBCLASS_GAP;

  // OUTER subclasses first — reserve their angles so Thing/Literals don't land
  // on the same spoke (Document had Thing + PersonalProfileDocument both at bottom).
  const subclassStart = -Math.PI / 2;
  placeEvenCircle(subclasses, x, y, subclassR, positions, subclassStart);
  const blockedAngles = subclasses.map((_, i) => {
    const n = Math.max(1, subclasses.length);
    return subclassStart + (i / n) * Math.PI * 2;
  });

  const angleClear = (a: number): number => {
    let best = Math.PI;
    for (const b of blockedAngles) {
      let d = Math.abs(a - b) % (Math.PI * 2);
      if (d > Math.PI) d = Math.PI * 2 - d;
      best = Math.min(best, d);
    }
    return best;
  };

  // INNER: pick angles that stay clear of subclass spokes
  if (uniqInner.length > 0) {
    const usedAngles: number[] = [];
    // Prefer side slots (left/right) for multi-edge Things so curves don't
    // run through the subclass stack under the hub.
    const candidates: number[] = [];
    for (let i = 0; i < 24; i++) candidates.push(-Math.PI + (i / 24) * Math.PI * 2);

    const pickAngle = (preferSide: boolean): number => {
      let best = candidates[0];
      let bestScore = -Infinity;
      for (const a of candidates) {
        const clear = angleClear(a);
        let neighbor = Math.PI;
        for (const u of usedAngles) {
          let d = Math.abs(a - u) % (Math.PI * 2);
          if (d > Math.PI) d = Math.PI * 2 - d;
          neighbor = Math.min(neighbor, d);
        }
        // Prefer horizontal-ish for multi-property Things (π and 0)
        const sideBias = preferSide ? Math.cos(a) * Math.cos(a) : 0;
        const score = clear * 2 + neighbor + sideBias * 0.8;
        if (score > bestScore) {
          bestScore = score;
          best = a;
        }
      }
      usedAngles.push(best);
      blockedAngles.push(best);
      return best;
    };

    // Place high-degree Things first so they claim clear side angles
    const ordered = [...uniqInner].sort(
      (a, b) => (innerDegree.get(b) ?? 1) - (innerDegree.get(a) ?? 1)
    );
    for (const id of ordered) {
      const deg = innerDegree.get(id) ?? 1;
      const preferSide = deg >= 2;
      const angle = pickAngle(preferSide);
      const r = deg >= 3 ? propR + 70 + deg * 22 : propR;
      positions.set(id, { x: x + Math.cos(angle) * r, y: y + Math.sin(angle) * r });
    }
  }

  for (const childId of subclasses) {
    const cp = positions.get(childId)!;
    placeVowlClassStar(childId, cp.x, cp.y, childrenOf, edges, nodeById, positions, placedClasses);
  }
}

/** Estimate how far a class star reaches (Literals + Things + subclass ring). */
function estimateClassFootprint(
  classId: string,
  childrenOf: Map<string, string[]>,
  edges: OntologyEdge[],
  nodeById: Map<string, OntologyNode>
): number {
  let inner = 0;
  for (const e of edges) {
    if (e.type !== 'propertyRelation') continue;
    const other = e.from === classId ? e.to : e.to === classId ? e.from : null;
    if (!other) continue;
    const n = nodeById.get(other);
    if (!n) continue;
    if (n.type === 'datatype' || isThingIri(other) || n.label === 'Thing' || other.includes(VOWL_CLONE_SEPARATOR)) {
      inner += 1;
    }
  }
  const subclasses = (childrenOf.get(classId) || []).length;
  const propR = starRadiusForCount(inner);
  const subclassR = subclasses > 0 ? propR + SUBCLASS_GAP : propR;
  return Math.max(propR, subclassR) + 40;
}

/**
 * Place class trees like VOWL:
 *  - densest hub near center
 *  - other hubs cleared by their star footprints (no overlapping neighborhoods)
 *  - Literals / class-local Things on the inner star
 *  - disconnected orphans in a compact side pack
 */
export function placeVowlNeighborhoods(
  neighborhoods: VowlNeighborhood[],
  width: number,
  height: number,
  nodes: OntologyNode[] = [],
  edges: OntologyEdge[] = []
): Map<string, { x: number; y: number }> {
  const positions = new Map<string, { x: number; y: number }>();
  const cx = width / 2;
  const cy = height / 2;
  const nodeById = new Map(nodes.map(n => [n.id, n]));

  const childrenOf = new Map<string, string[]>();
  const hasParent = new Set<string>();
  for (const e of edges) {
    if (e.type !== 'subClassOf') continue;
    if (!isRealClassNode(e.from, nodeById) || !isRealClassNode(e.to, nodeById)) continue;
    if (!childrenOf.has(e.to)) childrenOf.set(e.to, []);
    childrenOf.get(e.to)!.push(e.from);
    hasParent.add(e.from);
  }

  const realClassIds = nodes.filter(n => isRealClassNode(n.id, nodeById)).map(n => n.id);
  const propDegree = new Map<string, number>();
  const classAdj = new Map<string, Set<string>>();
  for (const e of edges) {
    if (e.type !== 'propertyRelation') continue;
    propDegree.set(e.from, (propDegree.get(e.from) ?? 0) + 1);
    propDegree.set(e.to, (propDegree.get(e.to) ?? 0) + 1);
    if (isRealClassNode(e.from, nodeById) && isRealClassNode(e.to, nodeById) && e.from !== e.to) {
      if (!classAdj.has(e.from)) classAdj.set(e.from, new Set());
      if (!classAdj.has(e.to)) classAdj.set(e.to, new Set());
      classAdj.get(e.from)!.add(e.to);
      classAdj.get(e.to)!.add(e.from);
    }
  }

  let roots = realClassIds.filter(id => !hasParent.has(id));
  const treeWeight = (id: string, seen = new Set<string>()): number => {
    if (seen.has(id)) return 0;
    seen.add(id);
    let w = propDegree.get(id) ?? 0;
    for (const c of childrenOf.get(id) || []) w += treeWeight(c, seen);
    return w;
  };
  if (roots.length === 0) {
    roots = [...realClassIds].sort((a, b) => treeWeight(b) - treeWeight(a)).slice(0, 6);
  } else {
    roots = [...roots].sort((a, b) => treeWeight(b) - treeWeight(a));
  }

  const placedClasses = new Set<string>();
  const footprints = new Map<string, number>();
  for (const id of realClassIds) {
    footprints.set(id, estimateClassFootprint(id, childrenOf, edges, nodeById));
  }

  // Compass slots around the primary hub — VOWL puts Document south, Account west, etc.
  const COMPASS = [Math.PI / 2, Math.PI, -Math.PI / 2, 0, Math.PI * 0.75, Math.PI * 0.25, -Math.PI * 0.75, -Math.PI * 0.25];
  let compassIdx = 0;

  const clearanceNeeded = (a: string, b: string) =>
    (footprints.get(a) ?? 160) + (footprints.get(b) ?? 160) + 50;

  /** Find a free point near anchors that doesn't collide with placed class stars. */
  const findClearSlot = (
    anchors: { x: number; y: number }[],
    newId: string,
    preferredAngle: number
  ): { x: number; y: number } => {
    const ax = anchors.reduce((s, p) => s + p.x, 0) / Math.max(1, anchors.length);
    const ay = anchors.reduce((s, p) => s + p.y, 0) / Math.max(1, anchors.length);
    const placedHubs = [...placedClasses].filter(id => !hasParent.has(id) || id === roots[0]);
    // Also keep clear of already-placed subclasses (Person sits on Agent's ring)
    const blockers = [...placedClasses];

    let best: { x: number; y: number; score: number } | null = null;
    for (let distMul = 1; distMul <= 4; distMul++) {
      for (let i = 0; i < 24; i++) {
        const angle = preferredAngle + (i / 24) * Math.PI * 2;
        // Base distance from footprint of nearest anchor hub
        let baseDist = footprints.get(newId) ?? 180;
        for (const a of anchors) {
          // rough: use average footprint of placed roots
          baseDist = Math.max(baseDist, 280);
        }
        const dist = baseDist * distMul * 0.55 + (footprints.get(newId) ?? 160);
        const x = ax + Math.cos(angle) * dist;
        const y = ay + Math.sin(angle) * dist;
        let minClear = Infinity;
        let ok = true;
        for (const bid of blockers) {
          const bp = positions.get(bid);
          if (!bp) continue;
          const need = clearanceNeeded(newId, bid);
          const d = Math.hypot(x - bp.x, y - bp.y);
          minClear = Math.min(minClear, d - need);
          if (d < need) ok = false;
        }
        const score = (ok ? 10000 : 0) + minClear - distMul * 10;
        if (!best || score > best.score) best = { x, y, score };
        if (ok && distMul <= 2) return { x, y };
      }
    }
    return best ? { x: best.x, y: best.y } : { x: ax + 360, y: ay + 360 };
  };

  const moveClassTree = (classId: string, dx: number, dy: number) => {
    const q = [classId];
    const seen = new Set<string>();
    while (q.length) {
      const id = q.shift()!;
      if (seen.has(id)) continue;
      seen.add(id);
      const p = positions.get(id);
      if (p) positions.set(id, { x: p.x + dx, y: p.y + dy });
      for (const e of edges) {
        if (e.type !== 'propertyRelation') continue;
        const other = e.from === id ? e.to : e.to === id ? e.from : null;
        if (!other || seen.has(other)) continue;
        const n = nodeById.get(other);
        if (!n) continue;
        if (n.type === 'datatype' || isThingIri(other) || n.label === 'Thing' || other.includes(VOWL_CLONE_SEPARATOR)) {
          q.push(other);
        }
      }
      for (const c of childrenOf.get(id) || []) q.push(c);
    }
  };

  // 1) Primary hub near center
  if (roots.length > 0) {
    placeVowlClassStar(roots[0], cx, cy - 40, childrenOf, edges, nodeById, positions, placedClasses);
  }

  // 2) Grow property-linked root hubs into clear compass slots
  const queue = [...placedClasses];
  while (queue.length > 0) {
    const cur = queue.shift()!;
    const cp = positions.get(cur);
    if (!cp) continue;
    const neighbors = [...(classAdj.get(cur) || [])].filter(
      id => isRealClassNode(id, nodeById) && !placedClasses.has(id) && !hasParent.has(id)
    );
    neighbors.sort((a, b) => (propDegree.get(b) ?? 0) - (propDegree.get(a) ?? 0));
    neighbors.forEach(id => {
      if (placedClasses.has(id)) return;
      const known = [...(classAdj.get(id) || [])].filter(x => placedClasses.has(x));
      const anchors = known.length > 0
        ? known.map(k => positions.get(k)!).filter(Boolean)
        : [cp];
      const preferred = COMPASS[compassIdx++ % COMPASS.length];
      const slot = findClearSlot(anchors, id, preferred);
      placeVowlClassStar(id, slot.x, slot.y, childrenOf, edges, nodeById, positions, placedClasses);
      queue.push(id);
    });
  }

  // 3) Remaining roots — footprint-clear slots near related hubs, else side pack
  const leftoverRoots = roots.filter(id => !placedClasses.has(id));
  const orphanCx = cx - Math.min(380, width * 0.32);
  const orphanCy = cy + Math.min(220, height * 0.26);

  const collectDescendants = (id: string): Set<string> => {
    const out = new Set<string>([id]);
    const q = [id];
    while (q.length) {
      const c = q.shift()!;
      for (const ch of childrenOf.get(c) || []) {
        if (out.has(ch)) continue;
        out.add(ch);
        q.push(ch);
      }
    }
    return out;
  };

  leftoverRoots.forEach((id, i) => {
    const tree = collectDescendants(id);
    const related: string[] = [];
    for (const e of edges) {
      if (e.type !== 'propertyRelation') continue;
      if (tree.has(e.from) && placedClasses.has(e.to)) related.push(e.to);
      if (tree.has(e.to) && placedClasses.has(e.from)) related.push(e.from);
    }
    let x: number;
    let y: number;
    if (related.length > 0) {
      const anchors = related.map(r => positions.get(r)!).filter(Boolean);
      const preferred = COMPASS[compassIdx++ % COMPASS.length];
      const slot = findClearSlot(anchors, id, preferred);
      x = slot.x;
      y = slot.y;
    } else {
      const cols = Math.max(1, Math.ceil(Math.sqrt(leftoverRoots.length)));
      x = orphanCx + (i % cols) * 140;
      y = orphanCy + Math.floor(i / cols) * 140;
    }
    placeVowlClassStar(id, x, y, childrenOf, edges, nodeById, positions, placedClasses);
  });

  // 4) Leftover classes (safety)
  realClassIds.filter(id => !placedClasses.has(id)).forEach((id, i) => {
    placeVowlClassStar(
      id,
      orphanCx + 40 + i * 100,
      orphanCy + 160,
      childrenOf,
      edges,
      nodeById,
      positions,
      placedClasses
    );
  });

  // 5) Resolve remaining star overlaps between hierarchy roots (never collapse subclasses)
  const rootHubs = [...placedClasses].filter(id => !hasParent.has(id));
  for (let iter = 0; iter < 40; iter++) {
    let moved = false;
    for (let i = 0; i < rootHubs.length; i++) {
      for (let j = i + 1; j < rootHubs.length; j++) {
        const aId = rootHubs[i];
        const bId = rootHubs[j];
        const a = positions.get(aId);
        const b = positions.get(bId);
        if (!a || !b) continue;
        const need = clearanceNeeded(aId, bId);
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const dist = Math.hypot(dx, dy) || 0.001;
        if (dist >= need) continue;
        const push = (need - dist) / 2;
        const ux = dx / dist;
        const uy = dy / dist;
        moveClassTree(aId, -ux * push, -uy * push);
        moveClassTree(bId, ux * push, uy * push);
        moved = true;
      }
    }
    // Also keep root hubs clear of other trees' subclasses (e.g. Document vs Person)
    for (const rootId of rootHubs) {
      const rp = positions.get(rootId);
      if (!rp) continue;
      for (const other of placedClasses) {
        if (other === rootId || !hasParent.has(other)) continue;
        // skip own descendants
        let walk: string | undefined = other;
        let own = false;
        const seen = new Set<string>();
        while (walk && !seen.has(walk)) {
          seen.add(walk);
          if (walk === rootId) { own = true; break; }
          walk = (edges.find(e => e.type === 'subClassOf' && e.from === walk)?.to);
        }
        if (own) continue;
        const op = positions.get(other);
        if (!op) continue;
        const need = (footprints.get(rootId) ?? 160) + 80;
        const dx = op.x - rp.x;
        const dy = op.y - rp.y;
        const dist = Math.hypot(dx, dy) || 0.001;
        if (dist >= need) continue;
        const push = (need - dist);
        const ux = dx / dist;
        const uy = dy / dist;
        moveClassTree(rootId, -ux * push, -uy * push);
        moved = true;
      }
    }
    if (!moved) break;
  }

  // 6) Domainless Thing→Literal stars — keep them clearly outside the main
  // cluster so they never sit on class-local Things (title was glued to Agent).
  const periNbs = neighborhoods.filter(
    nb => (isThingIri(nb.hubId) || nb.hubId.includes(VOWL_CLONE_SEPARATOR)) && !positions.has(nb.hubId)
  );
  const fringeR = Math.max(420, Math.min(cx, cy) * 0.85);
  periNbs.forEach((nb, i) => {
    const n = Math.max(1, periNbs.length);
    // Bottom-left / bottom fringe — away from Agent/Person/Document stars
    const angle = Math.PI * 0.55 + (i / Math.max(1, n - 1 || 1)) * Math.PI * 0.7;
    let hx = cx + Math.cos(angle) * fringeR;
    let hy = cy + Math.sin(angle) * fringeR;

    // Push away from anything already placed
    for (let pass = 0; pass < 12; pass++) {
      let moved = false;
      positions.forEach(p => {
        const dx = hx - p.x;
        const dy = hy - p.y;
        const dist = Math.hypot(dx, dy) || 0.001;
        const need = 130;
        if (dist >= need) return;
        const push = (need - dist) * 0.6;
        hx += (dx / dist) * push;
        hy += (dy / dist) * push;
        moved = true;
      });
      if (!moved) break;
    }

    positions.set(nb.hubId, { x: hx, y: hy });
    const members = nb.memberIds.filter(id => id !== nb.hubId && !positions.has(id));
    placeEvenCircle(members, hx, hy, starRadiusForCount(members.length, 70, 56), positions);
  });

  // Final Thing–Thing clearance (class-local + peripheral)
  const thingIds = nodes
    .filter(n => isThingIri(n.id) || n.label === 'Thing' || n.id.includes(VOWL_CLONE_SEPARATOR))
    .map(n => n.id)
    .filter(id => positions.has(id));
  for (let pass = 0; pass < 20; pass++) {
    let moved = false;
    for (let i = 0; i < thingIds.length; i++) {
      for (let j = i + 1; j < thingIds.length; j++) {
        const a = positions.get(thingIds[i])!;
        const b = positions.get(thingIds[j])!;
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const dist = Math.hypot(dx, dy) || 0.001;
        const need = 110;
        if (dist >= need) continue;
        const push = (need - dist) / 2;
        const ux = dx / dist;
        const uy = dy / dist;
        // Prefer moving peripheral (no class owner) Things
        const aPeri = !nodes.find(n => n.id === thingIds[i])?.metadata?.vowlOwnerHub;
        const bPeri = !nodes.find(n => n.id === thingIds[j])?.metadata?.vowlOwnerHub;
        if (aPeri && !bPeri) {
          positions.set(thingIds[i], { x: a.x - ux * push * 2, y: a.y - uy * push * 2 });
        } else if (bPeri && !aPeri) {
          positions.set(thingIds[j], { x: b.x + ux * push * 2, y: b.y + uy * push * 2 });
        } else {
          positions.set(thingIds[i], { x: a.x - ux * push, y: a.y - uy * push });
          positions.set(thingIds[j], { x: b.x + ux * push, y: b.y + uy * push });
        }
        moved = true;
      }
    }
    if (!moved) break;
  }

  nodes.forEach((node, i) => {
    if (positions.has(node.id)) return;
    positions.set(node.id, {
      x: orphanCx + (i % 4) * 80,
      y: orphanCy + 220 + Math.floor(i / 4) * 80
    });
  });

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
    // VOWL label style: "Document, CreativeWork" — unique display names.
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
    // Slightly larger so multi-name labels fit (VOWL also grows these)
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

  // ── 3. Group owl:Thing by class neighborhood ──
  // VOWL uses one Thing per owning class: all unknown-range properties from
  // Person share one Thing, those from Document share another, etc. Properties
  // with no class endpoint remain independent peripheral Thing stars.
  const thingNodes = nodes.filter(n => isThingIri(n.id));
  for (const thing of thingNodes) {
    const touching = edges.filter(e => e.from === thing.id || e.to === thing.id);
    const clonesByGroup = new Map<string, OntologyNode>();

    for (const e of touching) {
      const otherId = e.from === thing.id ? e.to : e.from;
      const other = nodeById.get(otherId);
      const classOwner = other?.type === 'class' && !isThingIri(otherId)
        ? otherId
        : undefined;
      const groupKey = classOwner ? `class:${classOwner}` : `edge:${e.id}`;

      let clone = clonesByGroup.get(groupKey);
      if (!clone) {
        clone = {
          ...thing,
          id: `${thing.id}${VOWL_CLONE_SEPARATOR}${classOwner ? `hub-${classOwner}` : e.id}`,
          size: Math.max(8, Math.round((thing.size ?? 20) * 0.62)),
          metadata: {
            ...(thing.metadata || {}),
            cloneOf: thing.id,
            ...(classOwner ? { vowlOwnerHub: classOwner } : {})
          }
        };
        clonesByGroup.set(groupKey, clone);
      }

      if (e.from === thing.id) e.from = clone.id;
      if (e.to === thing.id) e.to = clone.id;
    }

    nodes = nodes.filter(n => n.id !== thing.id);
    nodes.push(...clonesByGroup.values());
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
