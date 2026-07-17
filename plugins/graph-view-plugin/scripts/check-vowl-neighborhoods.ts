/**
 * Headless check: FOAF-like graph → VOWL transform → neighborhood separation.
 * Run: npx --yes ts-node --transpile-only scripts/check-vowl-neighborhoods.ts
 * (from plugins/graph-view-plugin)
 */
import {
  applyVowlTransform,
  buildVowlNeighborhoods,
  placeVowlNeighborhoods,
  isThingIri,
  VOWL_CLONE_SEPARATOR
} from '../src/vowlTransform';
import type { OntologyNode, OntologyEdge } from '../src/types';

const FOAF = 'http://xmlns.com/foaf/0.1/';
const SCHEMA = 'http://schema.org/';
const LIT = 'http://www.w3.org/2000/01/rdf-schema#Literal';
const THING = 'http://www.w3.org/2002/07/owl#Thing';

function fail(msg: string): never {
  console.error('FAIL:', msg);
  process.exit(1);
}

function ok(msg: string) {
  console.log('OK  ', msg);
}

// Minimal FOAF-shaped graph (mirrors test-harness + GraphDataFetchService output)
const nodes: OntologyNode[] = [
  { id: THING, label: 'Thing', type: 'class', uri: THING },
  { id: FOAF + 'Agent', label: 'Agent', type: 'class' },
  { id: FOAF + 'Person', label: 'Person', type: 'class' },
  { id: SCHEMA + 'Person', label: 'Person', type: 'class' },
  { id: FOAF + 'Document', label: 'Document', type: 'class' },
  { id: SCHEMA + 'CreativeWork', label: 'CreativeWork', type: 'class' },
  { id: FOAF + 'Image', label: 'Image', type: 'class' },
  { id: FOAF + 'OnlineAccount', label: 'Online Account', type: 'class' },
  { id: FOAF + 'Group', label: 'Group', type: 'class' },
  { id: FOAF + 'Project', label: 'Project', type: 'class' },
  { id: LIT, label: 'Literal', type: 'datatype', uri: LIT }
];

const edges: OntologyEdge[] = [
  // Synthetic orphan adoption (sidebar) — must be dropped in VOWL
  { id: 'orphan-Agent', from: FOAF + 'Agent', to: THING, type: 'subClassOf', label: 'subClassOf', metadata: { synthetic: true } },
  { id: 'orphan-Doc', from: FOAF + 'Document', to: THING, type: 'subClassOf', label: 'subClassOf', metadata: { synthetic: true } },
  { id: 'orphan-OA', from: FOAF + 'OnlineAccount', to: THING, type: 'subClassOf', label: 'subClassOf', metadata: { synthetic: true } },
  { id: 'orphan-Proj', from: FOAF + 'Project', to: THING, type: 'subClassOf', label: 'subClassOf', metadata: { synthetic: true } },
  // Real hierarchy
  { id: 'Person-Agent', from: FOAF + 'Person', to: FOAF + 'Agent', type: 'subClassOf', label: 'subClassOf' },
  { id: 'Image-Doc', from: FOAF + 'Image', to: FOAF + 'Document', type: 'subClassOf', label: 'subClassOf' },
  { id: 'Group-Agent', from: FOAF + 'Group', to: FOAF + 'Agent', type: 'subClassOf', label: 'subClassOf' },
  // Equivalent → merge
  { id: 'eq-Person', from: FOAF + 'Person', to: SCHEMA + 'Person', type: 'equivalentClass', label: 'equivalentClass' },
  { id: 'eq-Doc', from: FOAF + 'Document', to: SCHEMA + 'CreativeWork', type: 'equivalentClass', label: 'equivalentClass' },
  // page (no domain → Thing, range Document) — must survive transform as Thing clone → Document
  { id: 'page', from: THING, to: FOAF + 'Document', type: 'propertyRelation', label: 'page', metadata: { propertyType: 'objectProperty', vowlOnly: true } },
  // Object props
  { id: 'knows', from: FOAF + 'Person', to: FOAF + 'Person', type: 'propertyRelation', label: 'knows', metadata: { propertyType: 'objectProperty' } },
  { id: 'img', from: FOAF + 'Person', to: FOAF + 'Image', type: 'propertyRelation', label: 'img', metadata: { propertyType: 'objectProperty' } },
  { id: 'account', from: FOAF + 'Agent', to: FOAF + 'OnlineAccount', type: 'propertyRelation', label: 'account', metadata: { propertyType: 'objectProperty' } },
  // Same class + unknown range: both must converge on one local Thing.
  { id: 'currentProject', from: FOAF + 'Person', to: THING, type: 'propertyRelation', label: 'current project', metadata: { propertyType: 'objectProperty' } },
  { id: 'pastProject', from: FOAF + 'Person', to: THING, type: 'propertyRelation', label: 'past project', metadata: { propertyType: 'objectProperty' } },
  // Domain-less data props → Thing / Literal (vowlOnly)
  { id: 'nick', from: THING, to: LIT, type: 'propertyRelation', label: 'nickname', metadata: { propertyType: 'dataProperty', vowlOnly: true } },
  { id: 'title', from: THING, to: LIT, type: 'propertyRelation', label: 'title', metadata: { propertyType: 'dataProperty', vowlOnly: true } },
  { id: 'name', from: THING, to: LIT, type: 'propertyRelation', label: 'name', metadata: { propertyType: 'dataProperty', vowlOnly: true } },
  // Class-scoped data props
  { id: 'geek', from: FOAF + 'Person', to: LIT, type: 'propertyRelation', label: 'geekcode', metadata: { propertyType: 'dataProperty' } },
  { id: 'acctName', from: FOAF + 'OnlineAccount', to: LIT, type: 'propertyRelation', label: 'account name', metadata: { propertyType: 'dataProperty' } }
];

const { nodes: vn, edges: ve } = applyVowlTransform(nodes, edges);

// 1. Thing is grouped by class neighborhood
const thingNodes = vn.filter(n => isThingIri(n.id) || n.label === 'Thing');
const currentProject = ve.find(e => e.id === 'currentProject');
const pastProject = ve.find(e => e.id === 'pastProject');
if (!currentProject || !pastProject) fail('Project properties missing after transform');
const currentThing = isThingIri(currentProject.from) ? currentProject.from : currentProject.to;
const pastThing = isThingIri(pastProject.from) ? pastProject.from : pastProject.to;
if (currentThing !== pastThing) {
  fail('Properties from Person should converge on the same local Thing');
}
const ownerThing = vn.find(n => n.id === currentThing);
if (ownerThing?.metadata?.vowlOwnerHub !== FOAF + 'Person') {
  fail('Person-local Thing is missing its owner-hub metadata');
}
if (thingNodes.some(n => n.id === THING)) fail('Global Thing should be replaced by neighborhood-local Things');
ok('Unknown-range properties share one Thing per class neighborhood');

// 2. Literal split
const litNodes = vn.filter(n => n.type === 'datatype');
const sharedLit = litNodes.find(n => n.id === LIT);
if (sharedLit) fail('Shared Literal hub still present');
if (litNodes.length < 3) fail(`Expected multiple Literal clones, got ${litNodes.length}`);
ok(`Literal split into ${litNodes.length} per-property nodes`);

// 3. No subClassOf → Thing
const badSub = ve.filter(e => e.type === 'subClassOf' && (isThingIri(e.to) || e.to.includes('owl#Thing')));
if (badSub.length) fail(`subClassOf→Thing still present: ${badSub.map(e => e.id).join(',')}`);
ok('Dropped all subClassOf→Thing (including synthetic orphans)');

// 4. Equivalent merge
if (vn.some(n => n.id === SCHEMA + 'Person') && vn.some(n => n.id === FOAF + 'Person')) {
  fail('Equivalent Person classes were not merged');
}
const person = vn.find(n => n.id === FOAF + 'Person' || n.id === SCHEMA + 'Person');
if (!person?.metadata?.vowlEquivalent) fail('Merged Person missing vowlEquivalent flag');
if (person.label !== 'Person') fail(`Person merge should keep single label, got "${person.label}"`);
ok('Equivalent Person classes merged with double-border flag');

const doc = vn.find(n => n.id === FOAF + 'Document' || n.id === SCHEMA + 'CreativeWork');
if (!doc?.metadata?.vowlEquivalent) fail('Merged Document missing vowlEquivalent flag');
if (doc.label !== 'Document, CreativeWork') {
  fail(`Expected "Document, CreativeWork" label, got "${doc.label}"`);
}
ok('Document ≡ CreativeWork shows OntoCode VOWL comma label');

const pageEdge = ve.find(e => e.label === 'page');
if (!pageEdge) fail('page property missing after transform');
if (!isThingIri(pageEdge.from)) {
  fail(`page should start from Document's local Thing, got ${pageEdge.from}`);
}
if (pageEdge.to !== FOAF + 'Document' && pageEdge.to !== doc.id) {
  fail(`page should target Document merge node, got ${pageEdge.to}`);
}
ok('page property present (Document-local Thing → Document)');

// 5. Neighborhoods are separate (property hubs — not every subclass leaf)
const neighborhoods = buildVowlNeighborhoods(vn, ve);
if (neighborhoods.length < 3) {
  fail(`Expected ≥3 neighborhoods (separate hubs), got ${neighborhoods.length}: ${neighborhoods.map(n => n.hubId).join(', ')}`);
}
const hubNames = neighborhoods.map(n => n.hubId.split(/[#/]/).pop() || n.hubId);
for (const must of ['Person', 'Agent', 'Document']) {
  if (!hubNames.some(h => h === must || h?.includes(must))) {
    fail(`Expected ${must} to be a neighborhood hub, got: ${hubNames.join(', ')}`);
  }
}
ok(`${neighborhoods.length} separate neighborhoods: ${neighborhoods.map(n => {
  const short = n.hubId.split(/[#/]/).pop();
  return `${short}(${n.memberIds.length})`;
}).join(', ')}`);

// 6. Property-linked hubs stay compact (OntoCode VOWL) — not canvas-wide, not glued
const placed = placeVowlNeighborhoods(neighborhoods, 1200, 800, vn, ve);
const subclassPairs = new Set<string>();
const propLinked = new Set<string>();
for (const e of ve) {
  if (e.type === 'subClassOf') subclassPairs.add([e.from, e.to].sort().join('|'));
  if (e.type === 'propertyRelation') {
    propLinked.add(e.from);
    propLinked.add(e.to);
  }
}
const classHubIds = neighborhoods
  .filter(n => !n.hubId.includes(VOWL_CLONE_SEPARATOR) && !isThingIri(n.hubId) && propLinked.has(n.hubId))
  .map(n => n.hubId);
let minHubDist = Infinity;
let maxLinkedDist = 0;
for (let i = 0; i < classHubIds.length; i++) {
  for (let j = i + 1; j < classHubIds.length; j++) {
    const a = classHubIds[i];
    const b = classHubIds[j];
    if (subclassPairs.has([a, b].sort().join('|'))) continue;
    const pa = placed.get(a);
    const pb = placed.get(b);
    if (!pa || !pb) continue;
    const d = Math.hypot(pa.x - pb.x, pa.y - pb.y);
    minHubDist = Math.min(minHubDist, d);
    // Only score pairs that share a direct property edge
    const linked = ve.some(
      e => e.type === 'propertyRelation' &&
        ((e.from === a && e.to === b) || (e.from === b && e.to === a))
    );
    if (linked) maxLinkedDist = Math.max(maxLinkedDist, d);
  }
}
if (minHubDist !== Infinity && minHubDist < 250) {
  fail(`Property hubs too close after seed placement (minDist=${minHubDist.toFixed(1)})`);
}
if (maxLinkedDist > 900) {
  fail(`Property-linked hubs too far apart (maxDist=${maxLinkedDist.toFixed(1)}) — long crossing edges`);
}
ok(`Compact property-hub layout (min ${minHubDist === Infinity ? 'n/a' : minHubDist.toFixed(0)}px, linked max ${maxLinkedDist.toFixed(0)}px)`);

// 7. Subclass children sit on concentric rings outside the property star (~195 with empty inner)
const OUTER_MIN = 200;
const OUTER_MAX = 320;
const INNER_MIN = 110;
const INNER_MAX = 180;
const agentHub = neighborhoods.find(n => n.hubId === FOAF + 'Agent');
const docHub = neighborhoods.find(n => n.hubId === FOAF + 'Document');
if (agentHub && docHub) {
  const groupPos = placed.get(FOAF + 'Group');
  const agentPos = placed.get(FOAF + 'Agent');
  const imagePos = placed.get(FOAF + 'Image');
  const docPos = placed.get(FOAF + 'Document');
  if (groupPos && agentPos) {
    const d = Math.hypot(groupPos.x - agentPos.x, groupPos.y - agentPos.y);
    if (d < OUTER_MIN || d > OUTER_MAX) fail(`Group (subclass) should sit on OUTER ring (~195px), got ${d.toFixed(0)}`);
    ok(`Group on OUTER subclass ring around Agent (${d.toFixed(0)}px)`);
  }
  const personPos = placed.get(FOAF + 'Person');
  if (personPos && agentPos) {
    const d = Math.hypot(personPos.x - agentPos.x, personPos.y - agentPos.y);
    if (d < OUTER_MIN || d > OUTER_MAX) fail(`Person (subclass) should sit on OUTER ring (~195px), got ${d.toFixed(0)}`);
    ok(`Person on OUTER subclass ring around Agent (${d.toFixed(0)}px)`);
  }
  if (imagePos && docPos) {
    const d = Math.hypot(imagePos.x - docPos.x, imagePos.y - docPos.y);
    if (d < OUTER_MIN || d > OUTER_MAX) fail(`Image (subclass) should sit on OUTER ring (~195px), got ${d.toFixed(0)}`);
    ok(`Image on OUTER subclass ring around Document (${d.toFixed(0)}px)`);
  }
  // Literals around Person should be INNER (~100px base)
  const geekEdge = ve.find(e => e.label === 'geekcode');
  if (geekEdge && personPos) {
    const litPos = placed.get(geekEdge.to) || placed.get(geekEdge.from);
    if (litPos) {
      const d = Math.hypot(litPos.x - personPos.x, litPos.y - personPos.y);
      if (d < INNER_MIN || d > INNER_MAX) fail(`Literal for geekcode should be INNER (~100px), got ${d.toFixed(0)}`);
      ok(`Literal on INNER property ring around Person (${d.toFixed(0)}px)`);
    }
  }
}

console.log('\nPASS — VOWL transform + neighborhood separation look correct for FOAF-like data.');
