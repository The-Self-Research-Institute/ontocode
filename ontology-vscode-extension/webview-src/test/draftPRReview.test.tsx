import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const get = vi.fn();
const post = vi.fn();
vi.mock("../services/apiClient", () => ({
  default: { get: (...args: unknown[]) => get(...args), post: (...args: unknown[]) => post(...args) },
}));

import { DraftPRPanel } from "../components/DraftPRPanel";

const TITLE = "Header and merge changes";

const DRAFTS = [
  { id: "d1", operationType: "updateOntologyIri", operationData: { iri: "http://ex.org/new", label: "Ontology IRI",
    oldValue: "http://ex.org/old", value: "http://ex.org/new", versionIri: "http://ex.org/new/2.0", changeSetIds: ["c1"] } },
  { id: "d2", operationType: "updateOntologyAnnotation", operationData: { iri: "http://ex.org/new", label: "Ontology annotation",
    property: "http://purl.org/dc/elements/1.1/title", oldValue: "Old title", value: "New title", changeSetIds: ["c2"] } },
  { id: "d3", operationType: "addOntologyImport", operationData: { iri: "http://ex.org/new", label: "Ontology import",
    value: "http://ex.org/imported.owl", changeSetIds: ["c3"] } },
  { id: "d4", operationType: "updatePrefix", operationData: { label: "ex:", prefix: "ex", oldValue: "http://old.org#",
    value: "http://new.org#", changeSetIds: ["c4"] } },
  { id: "d5", operationType: "updateGCI", operationData: { label: "General class axiom", oldValue: "A and B SubClassOf C",
    value: "A SubClassOf C", changeSetIds: ["c5"] } },
  { id: "d6", operationType: "ontologyMerge", operationData: { iri: "urn:ontocode:merge:1", label: "Ontology merge",
    value: "Merged ontology: 125 added", mergeSummary: { description: "Merged ontology: 125 added",
      totals: { added: 120, changed: 3, removed: 0, copies: 2 },
      kinds: [{ kind: "class", added: ["Pizza", "PizzaTopping", "Topping"], addedCount: 120, changed: ["Base"], changedCount: 3,
        removed: [], removedCount: 0, copies: ["Pizza_imported", "Topping_imported"], copiesCount: 2 }] } } },
];

const FULL_DIFF = {
  available: true,
  baselineLost: false,
  publicChangedSinceDraft: true,
  conflictCount: 2,
  changes: [
    { entityIri: "http://ex.org/A", entityLabel: "Alpha", kind: "class", change: "changed",
      yourAxioms: ["Alpha SubClassOf Beta"], publicAxioms: ["Alpha SubClassOf Gamma"], conflict: true },
    { entityIri: "http://ex.org/N", entityLabel: "Newcomer", kind: "class", change: "added",
      yourAxioms: ["Class: Newcomer"], publicAxioms: [], conflict: false },
    { entityIri: "http://ex.org/p", entityLabel: "hasPart", kind: "objectProperty", change: "removed",
      yourAxioms: [], publicAxioms: ["ObjectProperty: hasPart"], conflict: false },
  ],
  header: [
    { field: "ontologyIri", baselineValue: "http://a/base", yourValue: "http://a/mine", publicValue: "http://a/public", conflict: true },
    { field: "imports", baselineValue: [], yourValue: ["http://x/y.owl"], publicValue: [], conflict: false },
  ],
  otherAxioms: { added: 2, removed: 1, items: [{ axiom: "A and B SubClassOf C", change: "added" }] },
};

let root: Root;
let host: HTMLDivElement;
let fullDiff: unknown;
let approve: (body: any) => any;

const wait = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });

function click(element: Element | undefined | null) {
  expect(element).toBeTruthy();
  act(() => { element!.dispatchEvent(new MouseEvent("click", { bubbles: true })); });
}

function button(text: string, scope: ParentNode = host): HTMLButtonElement | undefined {
  return Array.from(scope.querySelectorAll("button")).find((b) => b.textContent?.includes(text));
}

async function openPr() {
  await act(async () => root.render(
    <DraftPRPanel isOpen onClose={() => undefined} projectId="p1" userId="owner" username="Owner"
      canReview canRaisePR={false} draftCount={0} />,
  ));
  await wait(20);
  click(Array.from(host.querySelectorAll("div")).find((d) => d.textContent === TITLE));
  await wait(30);
}

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  fullDiff = { available: false };
  approve = () => ({ success: true });
  get.mockReset();
  post.mockReset();
  get.mockImplementation(async (url: string) => {
    if (url.endsWith("/draft-prs")) {
      return { prs: [{ id: "pr1", projectId: "p1", authorId: "author1", authorUsername: "Author", title: TITLE,
        status: "OPEN", changeCount: DRAFTS.length, createdAt: "2026-10-20" }] };
    }
    if (url.endsWith("/drafts") || url.endsWith("/changes")) {
      return { drafts: DRAFTS };
    }
    if (url.endsWith("/drafts/full-diff")) {
      return fullDiff;
    }
    if (url.endsWith("/drafts/publish-preview")) {
      return { conflictType: "NONE", conflicts: [] };
    }
    return {};
  });
  post.mockImplementation(async (url: string, body: any) => (url.endsWith("/approve") ? approve(body) : { success: true }));
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe("the pull request change list", () => {
  it("groups header, GCI and merge changes with readable old and new values", async () => {
    await openPr();

    for (const category of ["Ontology merge", "Ontology header", "GCI axioms"]) {
      click(button(category));
    }
    const text = host.textContent || "";

    expect(text).toContain("Ontology IRI: http://ex.org/old → http://ex.org/new");
    expect(text).toContain("Version IRI: (none) → http://ex.org/new/2.0");
    expect(text).toContain("Annotation dc:title");
    expect(text).toContain("\"Old title\" → \"New title\"");
    expect(text).toContain("Import added");
    expect(text).toContain("http://ex.org/imported.owl");
    expect(text).toContain("Prefix ex:");
    expect(text).toContain("<http://old.org#> → <http://new.org#>");
    expect(text).toContain("A and B SubClassOf C → A SubClassOf C");
    expect(text).toContain("Ontology merge — Merged ontology: 125 added: 120 classes added (Pizza, PizzaTopping, Topping, …), "
      + "3 changed, 2 copies added (Pizza_imported, Topping_imported)");

    click(host.querySelector("[data-testid=\"merge-row\"] button"));
    const expanded = host.textContent || "";
    expect(expanded).toContain("Classes — added (120): Pizza, PizzaTopping, Topping +117 more");
    expect(expanded).toContain("Classes — copies added (2): Pizza_imported, Topping_imported");
    expect(button("Full changes")).toBeUndefined();
  });
});

describe("the full changes view", () => {
  it("shows kind groups, header rows, other axioms and highlighted conflicts", async () => {
    fullDiff = FULL_DIFF;
    await openPr();

    click(button("Full changes"));
    const view = host.querySelector("[data-testid=\"full-changes\"]")!;
    const text = view.textContent || "";

    expect(text).toContain("Public changed since this draft started");
    expect(text).toContain("2 conflicts still need a choice");
    expect(text).toContain("Ontology IRI: http://a/base → http://a/mine (public: http://a/public)");
    expect(text).toContain("Imports: (none) → http://x/y.owl");
    expect(button("Classes", view)?.textContent).toContain("1 added · 1 changed");
    expect(button("Object properties", view)?.textContent).toContain("1 removed");
    expect(text).toContain("Other axioms: 2 added, 1 removed");
    expect(text).toContain("A and B SubClassOf C");
    const conflicts = Array.from(view.querySelectorAll("[data-conflict=\"true\"]"));
    expect(conflicts.map((row) => row.textContent?.includes("Alpha") || row.textContent?.includes("Ontology IRI"))).toEqual([true, true]);
    expect(button("Merge", conflicts[1])).toBeTruthy();
    expect(button("Merge", conflicts[0])).toBeUndefined();

    click(button("Alpha", view));
    expect(view.textContent).toContain("Alpha SubClassOf Beta");
    expect(view.textContent).toContain("Alpha SubClassOf Gamma");
  });

  it("sends the reviewer's choices with the approval", async () => {
    fullDiff = FULL_DIFF;
    await openPr();
    click(button("Full changes"));
    const rows = Array.from(host.querySelectorAll("[data-conflict=\"true\"]"));

    click(button("Take Public's", rows.find((row) => row.textContent?.includes("Alpha"))!));
    click(button("Keep draft", rows.find((row) => row.textContent?.includes("Ontology IRI"))!));
    click(button("Review"));
    click(button("Approve & Merge"));
    await wait(30);

    const call = post.mock.calls.find(([url]) => String(url).endsWith("/draft-prs/pr1/approve"));
    expect(call?.[1]).toEqual({
      reviewerId: "owner",
      reviewNote: undefined,
      resolutions: { "http://ex.org/A": "take_public", "header:ontologyIri": "keep_draft" },
    });
  });

  it("opens the conflicts when the server refuses the approval", async () => {
    approve = () => {
      throw Object.assign(new Error("Conflicts"), { status: 409, data: { success: false, conflicts: FULL_DIFF } });
    };
    await openPr();
    expect(button("Full changes")).toBeUndefined();

    click(button("Review"));
    click(button("Approve & Merge"));
    await wait(30);

    const view = host.querySelector("[data-testid=\"full-changes\"]");
    expect(view).toBeTruthy();
    expect(view!.textContent).toContain("Choose Keep draft or Take Public's for each highlighted row");
    expect(view!.querySelectorAll("[data-conflict=\"true\"]").length).toBe(2);
    expect(button("Approve & Merge")).toBeTruthy();
    expect(host.textContent).toContain("2 conflicts still need a choice in Full changes");
  });

  it("keeps the change notes and says why the full comparison is missing", async () => {
    fullDiff = { available: true, baselineLost: true };
    await openPr();

    expect(host.textContent).toContain("this draft's starting point was lost");
    expect(host.textContent).toContain(`${DRAFTS.length} changes by Author`);
    expect(button("Full changes")).toBeUndefined();
  });
});
