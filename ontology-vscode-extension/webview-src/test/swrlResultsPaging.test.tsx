import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SWRLEditor from "../../../plugins/swrl-editor-plugin/src/SWRLEditor";

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});

const wait = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });
const reply = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body, text: async () => JSON.stringify(body) });
const buttonByText = (text: string) => Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === text) as HTMLButtonElement | undefined;
const buttonStarting = (text: string) => Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim().startsWith(text)) as HTMLButtonElement | undefined;

const makeAxioms = (kind: string, offset: number, count: number) =>
  Array.from({ length: count }, (_, i) => {
    const n = offset + i + 1;
    return {
      axiomType: "ClassAssertion",
      description: `${kind}-${n}`,
      readable: `ClassAssertion(:${kind}${n} :Thing)`,
      category: kind === "conclusions" ? "conclusion" : "structure",
      subjectIri: `http://x/${kind}${n}`,
      predicateIri: "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
      objectIri: "http://x/Thing",
    };
  });

const TOTALS: Record<string, number> = { conclusions: 120, structure: 7 };

const setup = async (opts: { expireOnPage2?: boolean } = {}) => {
  const pageUrls: string[] = [];
  const posts: any[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: any) => {
    const u = String(url);
    if (u.includes("/rules/execute/run1/axioms")) {
      pageUrls.push(u);
      const params = new URL(u, "http://h").searchParams;
      const offset = Number(params.get("offset"));
      const kind = params.get("kind") || "conclusions";
      if (opts.expireOnPage2 && offset >= 50) return reply({ message: "gone" }, 410);
      const total = TOTALS[kind];
      return reply({ axioms: makeAxioms(kind, offset, Math.min(50, total - offset)), total, offset, limit: 50, kind });
    }
    if (u.endsWith("/api/ontology/p1/rules/execute") && init?.method === "POST") {
      posts.push(u);
      return reply({
        success: true, executionTimeMs: 5, inferredAxiomsCount: 127, totalRulesExecuted: 1,
        inferredAxioms: makeAxioms("conclusions", 0, 3), errorMessage: null,
        runId: "run1", counts: { conclusions: 120, structure: 7, omittedTrivial: 0 }, truncated: false,
      });
    }
    if (u.endsWith("/api/ontology/p1/rules")) {
      return reply({ rules: [{ id: "r1", ruleName: "R", ruleText: "Patient(?p) -> Senior(?p)", comment: "", category: "", enabled: true }] });
    }
    return reply({});
  }));
  await act(async () => root.render(<SWRLEditor projectId="p1" />));
  await wait(100);
  const exec = Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim().startsWith("Execute All")) as HTMLButtonElement;
  await act(async () => exec.click());
  await wait(100);
  return { pageUrls, posts };
};

describe("SWRL results paging", () => {
  it("shows the summary, pages with Next and Previous, and switches tabs", async () => {
    const { pageUrls } = await setup();
    expect(host.textContent).toContain("127 derived: 120 rule conclusions, 7 structure");
    expect(host.textContent).toContain("Showing 1-50 of 120");
    expect(buttonByText("Previous")!.disabled).toBe(true);

    await act(async () => buttonByText("Next")!.click());
    await wait(50);
    expect(host.textContent).toContain("Showing 51-100 of 120");
    expect(pageUrls.some((u) => u.includes("offset=50") && u.includes("kind=conclusions"))).toBe(true);

    const fetched = pageUrls.length;
    await act(async () => buttonByText("Previous")!.click());
    await wait(50);
    expect(host.textContent).toContain("Showing 1-50 of 120");
    expect(pageUrls.length).toBe(fetched);

    await act(async () => buttonStarting("Structure (")!.click());
    await wait(50);
    expect(host.textContent).toContain("Showing 1-7 of 7");
    expect(pageUrls.some((u) => u.includes("kind=structure"))).toBe(true);
    expect(buttonByText("Next")!.disabled).toBe(true);
  });

  it("keeps the selection across pages and tabs and counts it in the Add button", async () => {
    await setup();
    await act(async () => buttonStarting("Select all on this page")!.click());
    expect(host.textContent).toContain("50 selected");

    await act(async () => buttonByText("Next")!.click());
    await wait(50);
    expect(host.textContent).toContain("50 selected");
    await act(async () => buttonStarting("Select all on this page")!.click());
    expect(host.textContent).toContain("100 selected");

    await act(async () => buttonStarting("Structure (")!.click());
    await wait(50);
    expect(host.textContent).toContain("100 selected");
    expect(buttonStarting("Add 100 selected")).toBeTruthy();

    await act(async () => buttonStarting("Rule conclusions (")!.click());
    await wait(50);
    await act(async () => buttonByText("Clear selection")!.click());
    expect(host.textContent).toContain("0 selected");
  });

  it("shows the expired message with a Run again button that executes again", async () => {
    const { posts } = await setup({ expireOnPage2: true });
    await act(async () => buttonByText("Next")!.click());
    await wait(50);
    expect(host.textContent).toContain("This result has expired. Run Execute again.");
    const again = buttonByText("Run again")!;
    expect(again).toBeTruthy();
    const before = posts.length;
    await act(async () => again.click());
    await wait(100);
    expect(posts.length).toBe(before + 1);
  });
});
