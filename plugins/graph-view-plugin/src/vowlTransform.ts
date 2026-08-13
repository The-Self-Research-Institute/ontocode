

import type { OntologyNode, OntologyEdge } from './types';

export const isThingIri = (id: string | undefined | null): boolean =>
  !!id && (id === 'owl:Thing' || id.includes('owl#Thing'));

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

      hubIds.push(n.id);
      assigned.set(n.id, n.id);
    }
  }

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

const PROP_RING_BASE = 78;
const PROP_MIN_CHORD = 72;
const SUBCLASS_GAP = 52;

const CLASS_NODE_R = 40;
const HUB_PAD = 48;
const LITERAL_BOX_HALF = 42; // half-width of yellow datatype box

function starRadiusForCount(count: number, base = PROP_RING_BASE, minChord = PROP_MIN_CHORD): number {
  if (count <= 1) return base;
  const need = minChord / (2 * Math.sin(Math.PI / count));
  return Math.max(base, need);
}

function chipHalfWidth(label: string | undefined): number {
  return Math.min(96, String(label ?? '').length * 2.8 + 10);
}

function balloonRingRadius(childRadii: number[]): number {
  const n = childRadii.length;
  if (n === 0) return 0;
  if (n === 1) return childRadii[0] + CLASS_NODE_R + 8;

  let need = 0;
  for (let i = 0; i < n; i++) {
    const a = childRadii[i];
    const b = childRadii[(i + 1) % n];
    need = Math.max(need, (a + b) / (2 * Math.sin(Math.PI / n)));
  }

  need = Math.max(need, CLASS_NODE_R + Math.max(...childRadii) + 8);
  return need;
}

function safeUnit(dx: number, dy: number): { ux: number; uy: number; dist: number } {
  const dist = Math.hypot(dx, dy);
  if (dist < 1e-6) return { ux: 1, uy: 0, dist: 0 };
  return { ux: dx / dist, uy: dy / dist, dist };
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

  const useRs = subclasses.map(() => CLASS_NODE_R + 16);

  const propR = starRadiusForCount(Math.max(1, uniqInner.length), PROP_RING_BASE, 96) +
    (uniqInner.length > 0 ? LITERAL_BOX_HALF * 0.35 : 0);
  let subclassR = subclasses.length > 0
    ? Math.max(propR + SUBCLASS_GAP, balloonRingRadius(useRs))
    : propR;

  if (subclasses.length > 0) {

    const n = subclasses.length;
    const slot = wedge / n;
    const placeAt = (r: number) => {
      subclasses.forEach((id, i) => {
        const angle = outwardAngle - wedge / 2 + slot * (i + 0.5);
        positions.set(id, { x: x + Math.cos(angle) * r, y: y + Math.sin(angle) * r });
      });
    };
    placeAt(subclassR);

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

    let maxChipHalf = 20;
    for (const e of edges) {
      if (e.type !== 'propertyRelation') continue;
      const other = e.from === classId ? e.to : e.to === classId ? e.from : null;
      if (!other || !uniqInner.includes(other)) continue;
      maxChipHalf = Math.max(maxChipHalf, chipHalfWidth(e.label));
    }
    const chipFitR = CLASS_NODE_R + maxChipHalf * 1.35 + 48;

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

  const chipFitR = CLASS_NODE_R + maxChipHalf * 1.35 + 48;
  return Math.max(starRadiusForCount(Math.max(1, inner)), chipFitR) + 48;
}

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

function hubMetricDistances(
  ids: string[],
  edgeLen: Map<string, number>, // key "i|j" with i<j (indices)
  adj: Map<number, Set<number>>
): number[][] {
  const n = ids.length;
  const D: number[][] = Array.from({ length: n }, () => new Array(n).fill(Infinity));
  for (let s = 0; s < n; s++) {
    D[s][s] = 0;

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

        const parentChild =
          (hasParent.has(aId) && !hasParent.has(bId)) ||
          (hasParent.has(bId) && !hasParent.has(aId));
        const bothRoots = !hasParent.has(aId) && !hasParent.has(bId);
        const { ux, uy, dist } = safeUnit(b.x - a.x, b.y - a.y);
        let sep: number;
        if (parentChild) {
          sep = CLASS_NODE_R * 2 + 16;
        } else if (bothRoots) {

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

  const hubOf = new Map<string, string>();
  for (const nb of neighborhoods) {
    for (const m of nb.memberIds) hubOf.set(m, nb.hubId);
  }

  const childrenOf = new Map<string, string[]>();
  const hasParent = new Set<string>();
  const structuralHubLinks: Array<[string, string]> = [];
  for (const e of edges) {
    if (e.type !== 'subClassOf') continue;
    if (!isRealClassNode(e.from, nodeById) || !isRealClassNode(e.to, nodeById)) continue;
    const childHub = hubOf.get(e.from);
    const parentHub = hubOf.get(e.to);
    if (childHub && parentHub && childHub !== parentHub) {
      structuralHubLinks.push([childHub, parentHub]);
      continue;
    }
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

  const treeWeight = (id: string, seen = new Set<string>()): number => {
    if (seen.has(id)) return 0;
    seen.add(id);
    let w = propDegree.get(id) ?? 0;
    for (const c of childrenOf.get(id) || []) w += treeWeight(c, seen);
    return w;
  };

  const placedClasses = new Set<string>();
  const footprints = new Map<string, number>();
  const cores = new Map<string, number>();
  for (const id of realClassIds) {
    footprints.set(id, estimateClassFootprint(id, childrenOf, edges, nodeById));
    cores.set(id, estimateClassCoreRadius(id, edges, nodeById));
  }

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

  const hubIds = [...new Set(
    neighborhoods.filter(nb => isRealClassNode(nb.hubId, nodeById)).map(nb => nb.hubId)
  )];

  const idIndex = new Map(hubIds.map((id, i) => [id, i]));
  const hubAdjIdx = new Map<number, Set<number>>();
  hubIds.forEach((_, i) => hubAdjIdx.set(i, new Set()));
  const edgeLen = new Map<string, number>();
  const addHubEdge = (a: string, b: string, len: number) => {
    const ia = idIndex.get(a);
    const ib = idIndex.get(b);
    if (ia === undefined || ib === undefined || ia === ib) return;
    const lo = Math.min(ia, ib);
    const hi = Math.max(ia, ib);
    const key = `${lo}|${hi}`;
    const existing = edgeLen.get(key);
    if (existing === undefined || len < existing) edgeLen.set(key, len);
    hubAdjIdx.get(ia)!.add(ib);
    hubAdjIdx.get(ib)!.add(ia);
  };

  const hubPairCount = new Map<string, number>();
  for (const [a, nbs] of classAdj) {
    const ha = hubOf.get(a);
    if (!ha) continue;
    for (const b of nbs) {
      const hb = hubOf.get(b);
      if (!hb || ha === hb) continue;
      const lo = ha < hb ? ha : hb;
      const hi = ha < hb ? hb : ha;
      const key = `${lo}|${hi}`;
      hubPairCount.set(key, (hubPairCount.get(key) ?? 0) + 1);
    }
  }
  for (const [a, nbs] of classAdj) {
    const ha = hubOf.get(a);
    if (!ha) continue;
    for (const b of nbs) {
      const hb = hubOf.get(b);
      if (!hb || ha === hb) continue;
      const lo = ha < hb ? ha : hb;
      const hi = ha < hb ? hb : ha;
      const count = hubPairCount.get(`${lo}|${hi}`) ?? 1;
      const shrink = 1 / Math.sqrt(Math.max(1, count));
      const baseLen = (cores.get(ha) ?? 60) + (cores.get(hb) ?? 60) + 60;

      const len = Math.max(baseLen * 0.45, baseLen * shrink);
      addHubEdge(ha, hb, len);
    }
  }
  for (const [childHub, parentHub] of structuralHubLinks) {

    const len = (cores.get(childHub) ?? 60) + (cores.get(parentHub) ?? 60) + SUBCLASS_GAP - 12;
    addHubEdge(childHub, parentHub, len);
  }

  const seedR = 260 + hubIds.length * 18;
  hubIds.forEach((h, i) => {
    const angle = (i / Math.max(1, hubIds.length)) * Math.PI * 2;
    hubCenters.set(h, { x: cx + Math.cos(angle) * seedR, y: cy + Math.sin(angle) * seedR });
  });
  const hubDist = hubMetricDistances(hubIds, edgeLen, hubAdjIdx);
  stressMajorizeDisks(hubIds, hubCenters, hubDist, 150);

  for (let pass = 0; pass < 60; pass++) {
    let moved = false;
    for (let i = 0; i < hubIds.length; i++) {
      for (let j = i + 1; j < hubIds.length; j++) {
        const a = hubCenters.get(hubIds[i])!;
        const b = hubCenters.get(hubIds[j])!;
        const need = (cores.get(hubIds[i]) ?? 60) + (cores.get(hubIds[j]) ?? 60) + 20;
        const { ux, uy, dist } = safeUnit(b.x - a.x, b.y - a.y);
        if (dist >= need) continue;
        const push = (need - dist) / 2;
        hubCenters.set(hubIds[i], { x: a.x - ux * push, y: a.y - uy * push });
        hubCenters.set(hubIds[j], { x: b.x + ux * push, y: b.y + uy * push });
        moved = true;
      }
    }
    if (!moved) break;
  }

  const isolatedHubIdx = hubIds
    .map((_, i) => i)
    .filter(i => (hubAdjIdx.get(i)?.size ?? 0) === 0);
  if (isolatedHubIdx.length > 0) {
    const connectedCenters = hubIds
      .map((h, i) => ({ h, i, p: hubCenters.get(h) }))
      .filter(({ i, p }) => p && !isolatedHubIdx.includes(i));
    const minX = connectedCenters.length ? Math.min(...connectedCenters.map(c => c.p!.x)) : cx;
    const maxX = connectedCenters.length ? Math.max(...connectedCenters.map(c => c.p!.x)) : cx;
    const minY = connectedCenters.length ? Math.min(...connectedCenters.map(c => c.p!.y)) : cy;
    const maxY = connectedCenters.length ? Math.max(...connectedCenters.map(c => c.p!.y)) : cy;
    const massCx = (minX + maxX) / 2;
    const massCy = (minY + maxY) / 2;
    const massR = Math.max(200, Math.hypot(maxX - minX, maxY - minY) / 2);

    isolatedHubIdx.forEach((idx, i) => {
      const angle = (i / Math.max(1, isolatedHubIdx.length)) * Math.PI * 2 + 0.4;
      let ox = massCx + Math.cos(angle) * (massR + 220);
      let oy = massCy + Math.sin(angle) * (massR + 220);
      for (let pass = 0; pass < 12; pass++) {
        let moved = false;
        hubCenters.forEach((p, h2) => {
          if (h2 === hubIds[idx]) return;
          const { ux, uy, dist } = safeUnit(ox - p.x, oy - p.y);
          const need = (cores.get(hubIds[idx]) ?? 60) + (cores.get(h2) ?? 60) + 80;
          if (dist >= need) return;
          ox += ux * (need - dist) * 0.6;
          oy += uy * (need - dist) * 0.6;
          moved = true;
        });
        if (!moved) break;
      }
      hubCenters.set(hubIds[idx], { x: ox, y: oy });
    });
  }

  const byDensity = [...hubIds].sort((a, b) => treeWeight(b) - treeWeight(a));
  for (const h of byDensity) {
    const p = hubCenters.get(h);
    if (!p) continue;
    placeVowlClassStar(
      h, p.x, p.y, childrenOf, edges, nodeById, positions, placedClasses,
      -Math.PI / 2, Math.PI * 2
    );
  }

  const orphanCx = cx - Math.min(320, width * 0.28);
  const orphanCy = cy + Math.min(180, height * 0.22);

  projectClassDisks([...placedClasses], positions, footprints, cores, moveClassTree, hasParent);

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

  const periNbs = neighborhoods.filter(
    nb => (isThingIri(nb.hubId) || nb.hubId.includes(VOWL_CLONE_SEPARATOR)) && !positions.has(nb.hubId)
  );
  const fringeR = Math.max(360, Math.min(cx, cy) * 0.75);
  periNbs.forEach((nb, i) => {
    const n = Math.max(1, periNbs.length);
    const angle = Math.PI * 0.55 + (i / Math.max(1, n - 1 || 1)) * Math.PI * 0.7;
    let hx = cx + Math.cos(angle) * fringeR;
    let hy = cy + Math.sin(angle) * fringeR;

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

  const litIds = nodes
    .filter(n => n.type === 'datatype' || n.label === 'Literal')
    .map(n => n.id)
    .filter(id => positions.has(id));
  const classIdsAll = realClassIds.filter(id => positions.has(id));

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

  let nodes: OntologyNode[] = inputNodes.map(n => ({ ...n }));
  let edges: OntologyEdge[] = inputEdges.map(e => ({ ...e }));
  const nodeById = new Map(nodes.map(n => [n.id, n]));

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

  edges = edges.filter(e => !(e.type === 'subClassOf' && isThingIri(e.to)));

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
