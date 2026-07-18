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

// ── Balloon / radial-tree geometry (Melançon & Herman) ───────────────────────
// Screenshot-#1 look comes from short spokes + wedge packing, NOT from global
// stress that pulls every linked hub into one hairball.
//
// Child i of a node with wedge ω and subtree radii rᵢ sits at:
//   R ≥ rᵢ / sin(αᵢ / 2)     where αᵢ = ω · sᵢ / Σs
// Equal children (pure VOWL star): R = r / sin(π/n), chord ≥ 2·CLASS_NODE_R.
const PROP_RING_BASE = 78;
const PROP_MIN_CHORD = 72;
const SUBCLASS_GAP = 52;
const CLASS_NODE_R = 28;
const HUB_PAD = 48;
const LITERAL_BOX_HALF = 42; // half-width of yellow datatype box

/** Radius so n items on a circle keep ≈minChord between neighbors. */
function starRadiusForCount(count: number, base = PROP_RING_BASE, minChord = PROP_MIN_CHORD): number {
  if (count <= 1) return base;
  const need = minChord / (2 * Math.sin(Math.PI / count));
  return Math.max(base, need);
}

/** Half-width of a property chip label at ~11px font (matches renderer). */
function chipHalfWidth(label: string | undefined): number {
  return Math.min(96, String(label ?? '').length * 2.8 + 10);
}

/** Balloon ring: children of radii rᵢ need R so adjacent balloons don't overlap. */
function balloonRingRadius(childRadii: number[]): number {
  const n = childRadii.length;
  if (n === 0) return 0;
  if (n === 1) return childRadii[0] + CLASS_NODE_R + 8;
  // Equal angular slots: R · sin(π/n) ≥ (rᵢ + rᵢ₊₁) / 2  →  R ≥ (rᵢ+rᵢ₊₁)/(2·sin(π/n))
  let need = 0;
  for (let i = 0; i < n; i++) {
    const a = childRadii[i];
    const b = childRadii[(i + 1) % n];
    need = Math.max(need, (a + b) / (2 * Math.sin(Math.PI / n)));
  }
  // Also clear the parent's own body
  need = Math.max(need, CLASS_NODE_R + Math.max(...childRadii) + 8);
  return need;
}

/** Unit vector that never collapses to (0,0) — exact overlaps must still separate. */
function safeUnit(dx: number, dy: number): { ux: number; uy: number; dist: number } {
  const dist = Math.hypot(dx, dy);
  if (dist < 1e-6) return { ux: 1, uy: 0, dist: 0 };
  return { ux: dx / dist, uy: dy / dist, dist };
}

/** Even angular lattice: θᵢ = θ₀ + 2π·i/n (never spin individuals off-lattice). */
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
 * Grow a ring radius until every lattice point clears `occupied` by minDist.
 * Preserves even angles — WebVOWL look — instead of scattering individuals.
 */
function placeEvenCircleClear(
  ids: string[],
  cx: number,
  cy: number,
  baseRadius: number,
  positions: Map<string, { x: number; y: number }>,
  occupied: Map<string, { x: number; y: number }>,
  minDist: number,
  startAngle = -Math.PI / 2
): number {
  const n = ids.length;
  if (n === 0) return baseRadius;

  const clears = (r: number): boolean => {
    for (let i = 0; i < n; i++) {
      const angle = startAngle + (i / n) * Math.PI * 2;
      const x = cx + Math.cos(angle) * r;
      const y = cy + Math.sin(angle) * r;
      for (const [oid, p] of occupied) {
        if (ids.includes(oid)) continue;
        if (Math.hypot(x - p.x, y - p.y) < minDist) return false;
      }
    }
    return true;
  };

  let r = baseRadius;
  for (let k = 0; k < 12 && !clears(r); k++) r *= 1.18;
  placeEvenCircle(ids, cx, cy, r, positions, startAngle);
  return r;
}

/**
 * Balloon star (Melançon–Herman radial tree) around one class.
 *
 * Subclasses get equal angular slots on a short outer ring whose radius is
 * derived from child-balloon radii so spokes stay short and even — the #1 look.
 * Literals/Things sit on an INNER ring at mid-angles between subclass spokes
 * (or a side lattice when alone), never competing for the same ray.
 *
 * @param outwardAngle parent→this angle; child i=0 continues outward.
 * @param wedge angular budget for this subtree (2π at roots).
 */
function placeVowlClassStar(
  classId: string,
  x: number,
  y: number,
  childrenOf: Map<string, string[]>,
  edges: OntologyEdge[],
  nodeById: Map<string, OntologyNode>,
  positions: Map<string, { x: number; y: number }>,
  placedClasses: Set<string>,
  outwardAngle = -Math.PI / 2,
  wedge = Math.PI * 2
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
    if (n.type === 'datatype' || isThingIri(other) || n.label === 'Thing' || other.includes(VOWL_CLONE_SEPARATOR)) {
      inner.push(other);
    }
  }
  const uniqInner = [...new Set(inner)];
  const subclasses = (childrenOf.get(classId) || []).filter(id => !placedClasses.has(id));

  const innerDegree = new Map<string, number>();
  for (const e of edges) {
    if (e.type !== 'propertyRelation') continue;
    const other = e.from === classId ? e.to : e.to === classId ? e.from : null;
    if (!other || !uniqInner.includes(other)) continue;
    innerDegree.set(other, (innerDegree.get(other) ?? 0) + 1);
  }

  // Local child glyph radii only — grandchildren get their own short stars.
  // Full subtree disks (true Melançon) explode on deep BFO chains; #1 does not.
  const useRs = subclasses.map(() => CLASS_NODE_R + 16);
  // Literal boxes are ~84px wide: adjacent boxes on the ring need chord ≥ 96
  // or the yellow boxes (and their green chips) overlap side by side.
  const propR = starRadiusForCount(Math.max(1, uniqInner.length), PROP_RING_BASE, 96) +
    (uniqInner.length > 0 ? LITERAL_BOX_HALF * 0.35 : 0);
  let subclassR = subclasses.length > 0
    ? Math.max(propR + SUBCLASS_GAP, balloonRingRadius(useRs))
    : propR;

  if (subclasses.length > 0) {
    // Equal slots inside the available wedge (full 2π at roots → classic star).
    const n = subclasses.length;
    const slot = wedge / n;
    const placeAt = (r: number) => {
      subclasses.forEach((id, i) => {
        const angle = outwardAngle - wedge / 2 + slot * (i + 0.5);
        positions.set(id, { x: x + Math.cos(angle) * r, y: y + Math.sin(angle) * r });
      });
    };
    placeAt(subclassR);
    // Grow only if a lattice point collides with a foreign placed node
    for (let k = 0; k < 10; k++) {
      let clear = true;
      for (const id of subclasses) {
        const p = positions.get(id)!;
        for (const [oid, op] of positions) {
          if (oid === id || subclasses.includes(oid) || oid === classId) continue;
          if (Math.hypot(p.x - op.x, p.y - op.y) < CLASS_NODE_R * 2 + 6) {
            clear = false;
            break;
          }
        }
        if (!clear) break;
      }
      if (clear) break;
      subclassR *= 1.14;
      placeAt(subclassR);
    }
  }

  // Inner literals: mid-angles between subclass spokes (interleaved), short ring.
  if (uniqInner.length > 0) {
    const ordered = [...uniqInner].sort(
      (a, b) => (innerDegree.get(b) ?? 1) - (innerDegree.get(a) ?? 1)
    );
    const nSub = Math.max(1, subclasses.length);
    const innerStart =
      subclasses.length > 0
        ? outwardAngle - wedge / 2 + wedge / (2 * nSub)
        : outwardAngle + Math.PI / 2;
    const maxDeg = Math.max(1, ...ordered.map(id => innerDegree.get(id) ?? 1));
    // Spoke must FIT its chip: L ≥ classR + chip width + literal clearance.
    // "has observation temporal interval" needs a ~230px spoke; "age" ~120px.
    let maxChipHalf = 20;
    for (const e of edges) {
      if (e.type !== 'propertyRelation') continue;
      const other = e.from === classId ? e.to : e.to === classId ? e.from : null;
      if (!other || !uniqInner.includes(other)) continue;
      maxChipHalf = Math.max(maxChipHalf, chipHalfWidth(e.label));
    }
    const chipFitR = CLASS_NODE_R + maxChipHalf * 1.35 + 48;
    // Keep subclasses OUTSIDE the chip-fit literal ring
    if (subclasses.length > 0 && subclassR < chipFitR + SUBCLASS_GAP) {
      subclassR = chipFitR + SUBCLASS_GAP;
      const nSub = subclasses.length;
      const slot = wedge / nSub;
      subclasses.forEach((id, i) => {
        const angle = outwardAngle - wedge / 2 + slot * (i + 0.5);
        positions.set(id, { x: x + Math.cos(angle) * subclassR, y: y + Math.sin(angle) * subclassR });
      });
    }
    const innerR = Math.max(
      chipFitR,
      Math.min(
        subclassR - CLASS_NODE_R - 10,
        maxDeg >= 3 ? propR + 28 + maxDeg * 10 : propR
      )
    );
    const n = ordered.length;
    // Two-radius stagger: adjacent literal boxes alternate near/far rings so
    // wide yellow boxes + green chips never sit shoulder-to-shoulder.
    const rNear = Math.max(54, innerR);
    const rFar = n >= 4 ? rNear + LITERAL_BOX_HALF + 22 : rNear;
    ordered.forEach((id, i) => {
      const angle = subclasses.length > 0
        ? innerStart + (i / n) * wedge
        : innerStart + (i / n) * Math.PI * 2;
      const r = i % 2 === 0 ? rNear : rFar;
      positions.set(id, { x: x + Math.cos(angle) * r, y: y + Math.sin(angle) * r });
    });
  }

  for (const childId of subclasses) {
    const cp = positions.get(childId)!;
    const childOut = Math.atan2(cp.y - y, cp.x - x);
    const n = Math.max(1, subclasses.length);
    const childWedge = Math.min(wedge / n, Math.PI * 1.15);
    placeVowlClassStar(
      childId, cp.x, cp.y, childrenOf, edges, nodeById, positions, placedClasses,
      childOut, childWedge
    );
  }
}

/**
 * Isotropic core radius: literal/Thing ring + box margin. This part of a hub
 * extends in EVERY direction, so two hub centers may never be closer than the
 * sum of their cores.
 */
function estimateClassCoreRadius(
  classId: string,
  edges: OntologyEdge[],
  nodeById: Map<string, OntologyNode>
): number {
  let inner = 0;
  let maxChipHalf = 20;
  for (const e of edges) {
    if (e.type !== 'propertyRelation') continue;
    const other = e.from === classId ? e.to : e.to === classId ? e.from : null;
    if (!other) continue;
    const n = nodeById.get(other);
    if (!n) continue;
    if (n.type === 'datatype' || isThingIri(other) || n.label === 'Thing' || other.includes(VOWL_CLONE_SEPARATOR)) {
      inner += 1;
      maxChipHalf = Math.max(maxChipHalf, chipHalfWidth(e.label));
    }
  }
  if (inner === 0) return CLASS_NODE_R + 12;
  // Ring must fit the widest chip along its spoke, plus half a literal box.
  const chipFitR = CLASS_NODE_R + maxChipHalf * 1.35 + 48;
  return Math.max(starRadiusForCount(Math.max(1, inner)), chipFitR) + 48;
}

/** Neighborhood disk radius = outer star extent + class glyph. */
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
  const propR = starRadiusForCount(Math.max(1, inner));
  const subclassR = subclasses > 0
    ? Math.max(propR + SUBCLASS_GAP, starRadiusForCount(subclasses, propR + SUBCLASS_GAP, PROP_MIN_CHORD))
    : propR;
  return subclassR + CLASS_NODE_R;
}

/**
 * Stress majorization (SMACOF) over hub centers.
 * Minimizes  Σᵢⱼ wᵢⱼ·(‖xᵢ−xⱼ‖ − dᵢⱼ)²  with wᵢⱼ = 1/dᵢⱼ².
 * Each iteration is the closed-form majorizer update, which is guaranteed to
 * monotonically decrease stress — no tuning, no oscillation, no local pushes.
 */
function stressMajorizeDisks(
  ids: string[],
  pos: Map<string, { x: number; y: number }>,
  targetDist: number[][],
  iterations = 90
): void {
  const n = ids.length;
  if (n < 2) return;
  const xs = ids.map(id => pos.get(id)!.x);
  const ys = ids.map(id => pos.get(id)!.y);

  for (let it = 0; it < iterations; it++) {
    const nx = new Array(n).fill(0);
    const ny = new Array(n).fill(0);
    const wsum = new Array(n).fill(0);
    for (let i = 0; i < n; i++) {
      for (let j = 0; j < n; j++) {
        if (i === j) continue;
        const d = targetDist[i][j];
        if (!Number.isFinite(d) || d <= 0) continue;
        const w = 1 / (d * d);
        let dx = xs[i] - xs[j];
        let dy = ys[i] - ys[j];
        let dist = Math.hypot(dx, dy);
        if (dist < 1e-6) {
          // Deterministic tie-break for coincident points
          dx = Math.cos(i * 2.399963 + j);
          dy = Math.sin(i * 2.399963 + j);
          dist = 1;
        }
        nx[i] += w * (xs[j] + (d * dx) / dist);
        ny[i] += w * (ys[j] + (d * dy) / dist);
        wsum[i] += w;
      }
    }
    for (let i = 0; i < n; i++) {
      if (wsum[i] > 0) {
        xs[i] = nx[i] / wsum[i];
        ys[i] = ny[i] / wsum[i];
      }
    }
  }
  ids.forEach((id, i) => pos.set(id, { x: xs[i], y: ys[i] }));
}

/**
 * All-pairs metric distances on the hub graph via Dijkstra, where each hub
 * edge's length is the geometric requirement rᵢ+rⱼ+pad (disk tangency).
 * Unreachable pairs get 1.6× the graph diameter so components spread apart
 * but still participate in one coherent embedding.
 */
function hubMetricDistances(
  ids: string[],
  edgeLen: Map<string, number>, // key "i|j" with i<j (indices)
  adj: Map<number, Set<number>>
): number[][] {
  const n = ids.length;
  const D: number[][] = Array.from({ length: n }, () => new Array(n).fill(Infinity));
  for (let s = 0; s < n; s++) {
    D[s][s] = 0;
    // Simple O(n²) Dijkstra — hub counts are small (tens, not thousands)
    const done = new Array(n).fill(false);
    for (let step = 0; step < n; step++) {
      let u = -1;
      let best = Infinity;
      for (let v = 0; v < n; v++) {
        if (!done[v] && D[s][v] < best) {
          best = D[s][v];
          u = v;
        }
      }
      if (u === -1) break;
      done[u] = true;
      for (const v of adj.get(u) || []) {
        const key = u < v ? `${u}|${v}` : `${v}|${u}`;
        const w = edgeLen.get(key) ?? Infinity;
        if (D[s][u] + w < D[s][v]) D[s][v] = D[s][u] + w;
      }
    }
  }
  let diameter = 0;
  for (let i = 0; i < n; i++) {
    for (let j = 0; j < n; j++) {
      if (Number.isFinite(D[i][j])) diameter = Math.max(diameter, D[i][j]);
    }
  }
  const far = Math.max(600, diameter * 1.6);
  for (let i = 0; i < n; i++) {
    for (let j = 0; j < n; j++) {
      if (i !== j && !Number.isFinite(D[i][j])) D[i][j] = far;
    }
  }
  return D;
}

/** Project all class disks apart (Jacobi-style pairwise separation). */
function projectClassDisks(
  classIds: string[],
  positions: Map<string, { x: number; y: number }>,
  footprints: Map<string, number>,
  cores: Map<string, number>,
  moveTree: (id: string, dx: number, dy: number) => void,
  hasParent: Set<string>,
  iterations = 48
): void {
  for (let iter = 0; iter < iterations; iter++) {
    let moved = false;
    for (let i = 0; i < classIds.length; i++) {
      for (let j = i + 1; j < classIds.length; j++) {
        const aId = classIds[i];
        const bId = classIds[j];
        const a = positions.get(aId);
        const b = positions.get(bId);
        if (!a || !b) continue;
        // Parent–child on the same star: allow closer (they're on a designed ring)
        const parentChild =
          (hasParent.has(aId) && !hasParent.has(bId)) ||
          (hasParent.has(bId) && !hasParent.has(aId));
        const bothRoots = !hasParent.has(aId) && !hasParent.has(bId);
        const { ux, uy, dist } = safeUnit(b.x - a.x, b.y - a.y);
        let sep: number;
        if (parentChild) {
          sep = CLASS_NODE_R * 2 + 16;
        } else if (bothRoots) {
          // Two hub centers: their literal rings + chips must not interleave
          const need = (cores.get(aId) ?? 60) + (cores.get(bId) ?? 60) + 24;
          sep = Math.max(CLASS_NODE_R * 2 + 20, Math.min(need, 460));
        } else {
          const need =
            (footprints.get(aId) ?? 120) * 0.35 + (footprints.get(bId) ?? 120) * 0.35 + CLASS_NODE_R;
          sep = Math.max(CLASS_NODE_R * 2 + 20, Math.min(need, 140));
        }
        if (dist >= sep) continue;
        const push = (sep - dist) / 2;
        const aDeep = hasParent.has(aId);
        const bDeep = hasParent.has(bId);
        if (aDeep && !bDeep) {
          moveTree(aId, -ux * push * 2, -uy * push * 2);
        } else if (bDeep && !aDeep) {
          moveTree(bId, ux * push * 2, uy * push * 2);
        } else {
          moveTree(aId, -ux * push, -uy * push);
          moveTree(bId, ux * push, uy * push);
        }
        moved = true;
      }
    }
    if (!moved) break;
  }
}

/**
 * Place class trees as Melançon–Herman balloons (screenshot-#1 geometry):
 *  - each subclass tree is a short-spoke star (wedge balloon)
 *  - within a property-linked hub component: densest nucleus + satellites on a ring
 *  - components themselves pack as rigid disks (no global stress hairball)
 *  - Literals on inner mid-angles; pairwise projection cleans residuals
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
    const list = childrenOf.get(e.to)!;
    if (!list.includes(e.from)) list.push(e.from);
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
  const cores = new Map<string, number>();
  for (const id of realClassIds) {
    footprints.set(id, estimateClassFootprint(id, childrenOf, edges, nodeById));
    cores.set(id, estimateClassCoreRadius(id, edges, nodeById));
  }

  // Packing radius = local star footprint only (capped). Deep trees place
  // grandchildren as nested short stars; packing must not treat them as giant disks.
  const packR = new Map<string, number>();
  for (const id of realClassIds) {
    const local = Math.max(footprints.get(id) ?? 120, cores.get(id) ?? 60);
    packR.set(id, Math.min(local, 220));
  }

  const hubCenters = new Map<string, { x: number; y: number }>();

  const moveClassTree = (classId: string, dx: number, dy: number) => {
    const q = [classId];
    const seen = new Set<string>();
    while (q.length) {
      const id = q.shift()!;
      if (seen.has(id)) continue;
      seen.add(id);
      const p = positions.get(id);
      if (p) positions.set(id, { x: p.x + dx, y: p.y + dy });
      const hc = hubCenters.get(id);
      if (hc) hubCenters.set(id, { x: hc.x + dx, y: hc.y + dy });
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

  // Map every class → root hub
  const rootOf = new Map<string, string>();
  for (const root of roots) {
    const q = [root];
    while (q.length) {
      const c = q.shift()!;
      if (rootOf.has(c)) continue;
      rootOf.set(c, root);
      for (const ch of childrenOf.get(c) || []) q.push(ch);
    }
  }
  for (const id of realClassIds) {
    if (!rootOf.has(id)) rootOf.set(id, id);
  }
  const hubIds = [...new Set(rootOf.values())];

  // Hub adjacency via property links across trees
  const hubAdj = new Map<string, Set<string>>();
  for (const h of hubIds) hubAdj.set(h, new Set());
  for (const [a, nbs] of classAdj) {
    const ra = rootOf.get(a);
    if (!ra) continue;
    for (const b of nbs) {
      const rb = rootOf.get(b);
      if (!rb || ra === rb) continue;
      hubAdj.get(ra)!.add(rb);
      hubAdj.get(rb)!.add(ra);
    }
  }

  // Connected components of hubs (property graph)
  const components: string[][] = [];
  const seenHub = new Set<string>();
  for (const h of [...hubIds].sort((a, b) => treeWeight(b) - treeWeight(a))) {
    if (seenHub.has(h)) continue;
    const comp: string[] = [];
    const q = [h];
    while (q.length) {
      const cur = q.shift()!;
      if (seenHub.has(cur)) continue;
      seenHub.add(cur);
      comp.push(cur);
      for (const nb of hubAdj.get(cur) || []) q.push(nb);
    }
    comp.sort((a, b) => treeWeight(b) - treeWeight(a));
    components.push(comp);
  }

  // Component radius from capped local pack radii (short stars, not deep balloons)
  const compRadius = (comp: string[]): number => {
    if (comp.length === 0) return 140;
    const nucR = packR.get(comp[0]) ?? 140;
    if (comp.length === 1) return nucR;
    const sats = comp.slice(1).map(id => packR.get(id) ?? 120);
    const ring = balloonRingRadius(sats.map(r => Math.min(r, 160)));
    return Math.min(nucR + ring * 0.55 + Math.max(...sats) * 0.45, 520);
  };

  // Place components as meta-balloons: densest at center, others on a circle
  components.sort((a, b) => treeWeight(b[0]) - treeWeight(a[0]));
  const compCenters: { x: number; y: number }[] = [];
  const compRs = components.map(compRadius);

  components.forEach((comp, ci) => {
    let cpos: { x: number; y: number };
    if (ci === 0) {
      cpos = { x: cx, y: cy };
    } else {
      const angle = -Math.PI / 2 + ((ci - 1) / Math.max(1, components.length - 1)) * Math.PI * 2;
      const dist = Math.min(compRs[0] + compRs[ci] + HUB_PAD, 420 + ci * 40);
      cpos = { x: cx + Math.cos(angle) * dist, y: cy + Math.sin(angle) * dist };
      for (let pass = 0; pass < 24; pass++) {
        let moved = false;
        for (let j = 0; j < ci; j++) {
          const need = Math.min(compRs[ci] + compRs[j] + HUB_PAD, 380);
          const { ux, uy, dist: d } = safeUnit(cpos.x - compCenters[j].x, cpos.y - compCenters[j].y);
          if (d >= need) continue;
          cpos = { x: cpos.x + ux * (need - d), y: cpos.y + uy * (need - d) };
          moved = true;
        }
        if (!moved) break;
      }
    }
    compCenters.push(cpos);

    // Nucleus + satellites on a SHORT ring (screenshot #1 left-side stars)
    const nucleus = comp[0];
    hubCenters.set(nucleus, { x: cpos.x, y: cpos.y });
    const sats = comp.slice(1);
    if (sats.length > 0) {
      const satRs = sats.map(id => packR.get(id) ?? 120);
      const nucR = packR.get(nucleus) ?? 140;
      const ringR = Math.max(
        nucR + 40 + HUB_PAD * 0.5,
        balloonRingRadius(satRs.map(r => Math.min(r, 140)))
      );
      const cappedRing = Math.min(ringR, 280 + sats.length * 12);
      sats.forEach((id, i) => {
        const angle = -Math.PI / 2 + (i / sats.length) * Math.PI * 2;
        hubCenters.set(id, {
          x: cpos.x + Math.cos(angle) * cappedRing,
          y: cpos.y + Math.sin(angle) * cappedRing
        });
      });
    }
  });

  // Draw each hub as a short-spoke star
  const byDensity = [...hubIds].sort((a, b) => treeWeight(b) - treeWeight(a));
  for (const h of byDensity) {
    const p = hubCenters.get(h);
    if (!p) continue;
    const comp = components.find(c => c.includes(h));
    let outward = -Math.PI / 2;
    if (comp && comp[0] !== h) {
      const nuc = hubCenters.get(comp[0])!;
      outward = Math.atan2(p.y - nuc.y, p.x - nuc.x);
    }
    placeVowlClassStar(
      h, p.x, p.y, childrenOf, edges, nodeById, positions, placedClasses,
      outward, Math.PI * 2
    );
  }

  const orphanCx = cx - Math.min(320, width * 0.28);
  const orphanCy = cy + Math.min(180, height * 0.22);

  // Pairwise disk projection — compact, aligned, no stacks
  projectClassDisks([...placedClasses], positions, footprints, cores, moveClassTree, hasParent);

  // Leftover classes (safety)
  realClassIds.filter(id => !placedClasses.has(id)).forEach((id, i) => {
    placeVowlClassStar(
      id,
      orphanCx + 40 + i * 100,
      orphanCy + 160,
      childrenOf,
      edges,
      nodeById,
      positions,
      placedClasses,
      -Math.PI / 2,
      Math.PI * 2
    );
  });

  // Domainless Thing→Literal stars — keep them clearly outside the main cluster
  const periNbs = neighborhoods.filter(
    nb => (isThingIri(nb.hubId) || nb.hubId.includes(VOWL_CLONE_SEPARATOR)) && !positions.has(nb.hubId)
  );
  const fringeR = Math.max(360, Math.min(cx, cy) * 0.75);
  periNbs.forEach((nb, i) => {
    const n = Math.max(1, periNbs.length);
    const angle = Math.PI * 0.55 + (i / Math.max(1, n - 1 || 1)) * Math.PI * 0.7;
    let hx = cx + Math.cos(angle) * fringeR;
    let hy = cy + Math.sin(angle) * fringeR;

    // Push away from anything already placed
    for (let pass = 0; pass < 12; pass++) {
      let moved = false;
      positions.forEach(p => {
        const dx = hx - p.x;
        const dy = hy - p.y;
        const { ux, uy, dist } = safeUnit(dx, dy);
        const need = 130;
        if (dist >= need) return;
        const push = (need - dist) * 0.6;
        hx += ux * push;
        hy += uy * push;
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
        const { ux, uy, dist } = safeUnit(dx, dy);
        const need = 110;
        if (dist >= need) continue;
        const push = (need - dist) / 2;
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

  // Literals must not sit on foreign class circles (yellow-on-blue overlaps).
  // Own-hub literals stay on their designed ring; everything else is pushed out.
  const litIds = nodes
    .filter(n => n.type === 'datatype' || n.label === 'Literal')
    .map(n => n.id)
    .filter(id => positions.has(id));
  const classIdsAll = realClassIds.filter(id => positions.has(id));
  // Which class owns each literal? The property edge endpoint that is a class.
  const litOwner = new Map<string, string>();
  for (const e of edges) {
    if (e.type !== 'propertyRelation') continue;
    if (litIds.includes(e.from) && classIdsAll.includes(e.to)) litOwner.set(e.from, e.to);
    if (litIds.includes(e.to) && classIdsAll.includes(e.from)) litOwner.set(e.to, e.from);
  }
  for (let pass = 0; pass < 16; pass++) {
    let moved = false;
    for (const lid of litIds) {
      const lp = positions.get(lid)!;
      const owner = litOwner.get(lid);
      for (const cid of classIdsAll) {
        if (cid === owner) continue; // own hub — ring math already placed this
        const cp = positions.get(cid)!;
        const dx = lp.x - cp.x;
        const dy = lp.y - cp.y;
        // AABB: literal box ~48×16, class circle ~28
        const overlapX = 48 + CLASS_NODE_R + 10 - Math.abs(dx);
        const overlapY = 16 + CLASS_NODE_R + 8 - Math.abs(dy);
        if (overlapX <= 0 || overlapY <= 0) continue;
        if (overlapX < overlapY) {
          const sx = (dx === 0 ? 1 : Math.sign(dx)) * overlapX;
          positions.set(lid, { x: lp.x + sx, y: lp.y });
        } else {
          const sy = (dy === 0 ? 1 : Math.sign(dy)) * overlapY;
          positions.set(lid, { x: lp.x, y: lp.y + sy });
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
