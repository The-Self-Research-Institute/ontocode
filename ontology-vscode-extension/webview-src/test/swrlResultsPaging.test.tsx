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
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});
const buttonByText = (text: string) => Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === text) as HTMLButtonElement | undefined;
const buttonStarting = (text: string) => Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim().startsWith(text)) as HTMLButtonElement | undefined;

const makeAxioms = (kind: string, offset: number, count: number) =>
  Array.from({ length: count }, (_, i) => {
    const n = offset + i + 1;
    return {
      axiomType: "ClassAssertion",
      description: `${kind}-${n}`,
      readable: `ClassAssertion(:${kind}${n} :Thing)`,
      category: kind === "structure" ? "structure" : "conclusion",
      subjectIri: `http://x/${kind}${n}`,
      predicateIri: "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
      objectIri: "http://x/Thing",
      origin: kind === "rules" ? "RULE_ENGINE" : undefined,
    };
  });

const TOTALS: Record<string, number> = { rules: 90, conclusions: 120, structure: 7 };

const setup = async (opts: {
  expireOnPage2?: boolean;
  attributionComplete?: boolean;
  inlineAxioms?: ReturnType<typeof makeAxioms>;
} = {}) => {
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
      if (opts.inlineAxioms) return reply({
        success: true, executionTimeMs: 5, totalRulesExecuted: 1,
        inferredAxiomsCount: opts.inlineAxioms.length, inferredAxioms: opts.inlineAxioms,
      });
      return reply({
        success: true, executionTimeMs: 5, inferredAxiomsCount: 127, totalRulesExecuted: 1,
        inferredAxioms: makeAxioms("conclusions", 0, 3), errorMessage: null,
        runId: "run1", counts: { conclusions: 120, ruleConclusions: 90, structure: 7, omittedTrivial: 0 }, truncated: false,
        attributionComplete: opts.attributionComplete,
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
  it("shows rule results first even when they follow more than 100 standard results in the response", async () => {
    const { pageUrls } = await setup({
      inlineAxioms: [
        ...makeAxioms("conclusions", 0, 120),
        ...makeAxioms("rules", 0, 2),
      ],
    });
    const sections = () => [...host.querySelectorAll('[data-testid^="section-"]')].map(section => section.getAttribute("data-testid"));
    expect(pageUrls).toHaveLength(0);
    expect(sections()).toEqual(["section-rules", "section-standard"]);
    expect(host.textContent).toContain("rules1");
    expect(host.textContent).toContain("rules2");
    expect(host.textContent).not.toContain("conclusions120");

    await act(async () => buttonStarting("Show 22 more")!.click());
    expect(sections()).toEqual(["section-rules", "section-standard"]);
    expect(host.textContent).toContain("conclusions120");
  });

  it("defaults to rule-dependent facts and switches to all facts when requested", async () => {
    const { pageUrls } = await setup({ attributionComplete: true });
    expect(pageUrls[0]).toContain("kind=rules");
    expect(host.textContent).toContain("Showing 1-50 of 90");

    await act(async () => buttonByText("All facts about individuals (120)")!.click());
    await wait(50);
    expect(pageUrls.at(-1)).toContain("kind=conclusions");
    expect(host.textContent).toContain("Showing 1-50 of 120");
  });

  it("shows the summary, pages with Next and Previous, and switches tabs", async () => {
    const { pageUrls } = await setup();
    expect(host.textContent).toContain("127 produced: 90 facts from your rules, 30 facts from standard reasoning, 7 class relationships from standard reasoning.");
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

    await act(async () => buttonStarting("Class relationships (")!.click());
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

    await act(async () => buttonStarting("Class relationships (")!.click());
    await wait(50);
    expect(host.textContent).toContain("100 selected");
    expect(buttonStarting("Add 100 selected")).toBeTruthy();

    await act(async () => buttonStarting("Facts about individuals (")!.click());
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
