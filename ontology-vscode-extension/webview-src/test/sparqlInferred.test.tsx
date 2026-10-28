import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SparqlQueryEditor } from "../../../plugins/sparql-query-plugin/src/SparqlQueryEditor";

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

const button = (label: string) =>
  Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === label) as HTMLButtonElement | undefined;

const click = async (label: string) => {
  const el = button(label);
  expect(el, label).toBeTruthy();
  await act(async () => el!.click());
};

const mount = async (status: any, post: any, draftMode = false) => {
  const get = vi.fn(async (url: string) => (url.includes("/inference/") ? status : []));
  const apiClient = { get, post, put: vi.fn(), delete: vi.fn() };
  await act(async () => root.render(<SparqlQueryEditor projectId="p1" draftMode={draftMode} context={{ apiClient } as any} />));
  return get;
};

const okStatus = { available: true, stale: false, reasoner: "HermiT", layers: ["owl"], tooLarge: false };

describe("Include inferred switch", () => {
  it("defaults to asserted only and sends no inference fields", async () => {
    const post = vi.fn(async () => ({ results: [], head: { vars: [] } }));
    await mount(okStatus, post);
    expect(button("Asserted only")!.getAttribute("aria-pressed")).toBe("true");
    await click("Execute");
    expect(post.mock.calls[0][1]).toEqual({ query: "SELECT ?s ?p ?o WHERE {\n  ?s ?p ?o\n} LIMIT 10", pageSize: 100 });
  });

  it("is disabled with a tooltip when the reasoner has not run", async () => {
    await mount({ available: false }, vi.fn());
    const inferred = button("Include inferred")!;
    expect(inferred.disabled).toBe(true);
    expect(inferred.title).toBe("Run the reasoner first");
  });

  it("is disabled when the ontology is too large", async () => {
    await mount({ available: true, tooLarge: true }, vi.fn());
    const inferred = button("Include inferred")!;
    expect(inferred.disabled).toBe(true);
    expect(inferred.title).toBe("Too large to include inferred facts");
  });

  it("remembers the choice per project and sends includeInferred", async () => {
    const post = vi.fn(async () => ({
      head: { vars: ["s"] },
      results: [{ s: "http://ex.org/a" }],
      inferred: {
        used: true, reasoner: "HermiT", computedAt: new Date(Date.now() - 5 * 60000).toISOString(), layers: ["owl", "rules"],
        assertedTripleCount: 10, inferredTripleCount: 4, partial: true, partialReasons: ["timeout"], equality: { incomplete: true }
      }
    }));
    const get = await mount({ ...okStatus, stale: true }, post, true);
    expect(get.mock.calls.some((c: any) => c[0] === "/api/sparql/inference/p1/status?draft=true")).toBe(true);
    await click("Include inferred");
    expect(localStorage.getItem("sparql.includeInferred.p1")).toBe("inferred");
    await click("Execute");
    expect(post.mock.calls[0][1].includeInferred).toBe(true);
    expect(post.mock.calls[0][1].refresh).toBeUndefined();
    const info = host.querySelector("[data-testid=inferred-info]")!;
    expect(info.textContent).toContain("Includes the documented, tested equality consequences. HermiT, computed 5 minutes ago");
    expect(info.textContent).not.toMatch(/full owl reasoning|openllet|pellet/i);
    expect(host.querySelector("[data-testid=stale-badge]")).toBeTruthy();
    expect(info.textContent).toContain("10 stated, 4 inferred triples searched");
    const partial = host.querySelector("[data-testid=partial-warning]")!.textContent!;
    expect(partial).toContain("time limit");
    expect(partial).toContain("Some equivalent-name facts were left out because a limit was reached");
    expect(get.mock.calls.filter((c: any) => c[0].includes("/inference/")).length).toBeGreaterThanOrEqual(2);
  });

  it("shows what is included in plain words", async () => {
    await mount(okStatus, vi.fn());
    await click("What is included");
    const text = host.querySelector("[data-testid=included-popover]")!.textContent!;
    expect(text).toContain("Class hierarchy");
    expect(text).toContain("Same-as and different-from facts");
    expect(text).toContain("Full OWL 2 DL entailment");
    expect(text).toContain("Rule results that conclude same-as");
  });
});

describe("Inference conflicts", () => {
  const needs = { needsReasoning: true, reason: "stale", message: "The ontology changed since the reasoner last ran." };

  it("offers refresh and switch back on needsReasoning, and refresh re-sends with refresh:true", async () => {
    localStorage.setItem("sparql.includeInferred.p1", "inferred");
    const post = vi
      .fn()
      .mockRejectedValueOnce({ status: 409, body: needs })
      .mockResolvedValueOnce({ head: { vars: [] }, results: [], inferred: { used: true, computedAt: Date.now(), layers: ["owl"] } });
    await mount(okStatus, post);
    await click("Execute");
    const panel = host.querySelector("[data-testid=needs-reasoning-panel]")!;
    expect(panel.textContent).toContain("The ontology changed since the reasoner last ran.");
    await click("Run again with refresh");
    expect(post.mock.calls[1][1]).toMatchObject({ includeInferred: true, refresh: true });
    expect(host.querySelector("[data-testid=needs-reasoning-panel]")).toBeNull();
  });

  it("switch to asserted only clears the panel and the choice", async () => {
    localStorage.setItem("sparql.includeInferred.p1", "inferred");
    const post = vi.fn().mockResolvedValueOnce(needs);
    await mount(okStatus, post);
    await click("Execute");
    await click("Switch to asserted only");
    expect(host.querySelector("[data-testid=needs-reasoning-panel]")).toBeNull();
    expect(localStorage.getItem("sparql.includeInferred.p1")).toBe("asserted");
    expect(button("Asserted only")!.getAttribute("aria-pressed")).toBe("true");
  });

  it("shows a distinct panel with the explanation and no results for an inconsistent ontology", async () => {
    localStorage.setItem("sparql.includeInferred.p1", "inferred");
    const post = vi.fn().mockRejectedValueOnce({ response: { data: { status: "INCONSISTENT", message: "No model exists.", explanation: "Dog and Cat are disjoint." } } });
    await mount(okStatus, post);
    await click("Execute");
    const panel = host.querySelector("[data-testid=inconsistent-panel]")!;
    expect(panel.textContent).toContain("No model exists.");
    expect(panel.textContent).toContain("Dog and Cat are disjoint.");
    expect(host.querySelector("[data-testid=needs-reasoning-panel]")).toBeNull();
    expect(host.querySelector("[data-testid=inferred-info]")).toBeNull();
  });

  it("keeps ordinary errors as plain error text", async () => {
    const post = vi.fn().mockRejectedValueOnce(new Error("Server down"));
    await mount(okStatus, post);
    await click("Execute");
    expect(host.textContent).toContain("Server down");
    expect(host.querySelector("[data-testid=needs-reasoning-panel]")).toBeNull();
  });
});
