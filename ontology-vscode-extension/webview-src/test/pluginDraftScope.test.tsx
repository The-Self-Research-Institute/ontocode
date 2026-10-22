import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { withDraft } from "../../../plugins/fuzzy-ontology-plugin/src/draftScope";
import { useUndoRefresh } from "../../../plugins/fuzzy-ontology-plugin/src/useUndoRefresh";
import FuzzyEditorEnhanced from "../../../plugins/fuzzy-ontology-plugin/src/FuzzyEditorEnhanced";
import { SparqlQueryEditor } from "../../../plugins/sparql-query-plugin/src/SparqlQueryEditor";
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

describe("withDraft", () => {
  it("adds the draft flag only in draft mode", () => {
    expect(withDraft("/api/sparql/query/p", false)).toBe("/api/sparql/query/p");
    expect(withDraft("/api/sparql/query/p", true)).toBe("/api/sparql/query/p?draft=true");
    expect(withDraft("/api/x?a=1", true)).toBe("/api/x?a=1&draft=true");
  });
});

describe("useUndoRefresh", () => {
  const Probe: React.FC<{ onRefresh: () => void }> = ({ onRefresh }) => {
    useUndoRefresh("p1", onRefresh, 20);
    return null;
  };

  it("refreshes after an undo or redo of this project and ignores other projects and plain edits", async () => {
    const refresh = vi.fn();
    await act(async () => root.render(<Probe onRefresh={refresh} />));

    window.dispatchEvent(new CustomEvent("ontologyRollback", { detail: { projectId: "other" } }));
    window.dispatchEvent(new CustomEvent("ontocode:committed-change", { detail: { projectId: "p1", direction: "APPLY" } }));
    await wait(60);
    expect(refresh).not.toHaveBeenCalled();

    window.dispatchEvent(new CustomEvent("ontologyRollback", { detail: { projectId: "p1" } }));
    await wait(60);
    expect(refresh).toHaveBeenCalledTimes(1);

    window.dispatchEvent(new CustomEvent("ontocode:committed-change", { detail: { projectId: "p1", direction: "REDO" } }));
    window.dispatchEvent(new CustomEvent("ontocode:committed-change", { detail: { projectId: "p1", direction: "REDO" } }));
    await wait(60);
    expect(refresh).toHaveBeenCalledTimes(2);
  });
});

describe("Fuzzy editor", () => {
  const stubFetch = () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      urls.push(String(url));
      return { ok: true, status: 200, json: async () => ({ results: [], content: [] }), text: async () => "[]" };
    }));
    return urls;
  };

  it("asks for the draft version while in draft mode", async () => {
    const urls = stubFetch();
    await act(async () => root.render(<FuzzyEditorEnhanced projectId="p1" draftMode />));
    await wait(80);
    const sparql = urls.filter((url) => url.includes("/api/sparql/"));
    expect(sparql.length).toBeGreaterThan(0);
    expect(sparql.every((url) => url.includes("draft=true"))).toBe(true);
  });

  it("asks for the public version otherwise", async () => {
    const urls = stubFetch();
    await act(async () => root.render(<FuzzyEditorEnhanced projectId="p1" />));
    await wait(80);
    const sparql = urls.filter((url) => url.includes("/api/sparql/"));
    expect(sparql.length).toBeGreaterThan(0);
    expect(sparql.some((url) => url.includes("draft=true"))).toBe(false);
  });

  it("reloads its data after an undo", async () => {
    const urls = stubFetch();
    await act(async () => root.render(<FuzzyEditorEnhanced projectId="p1" />));
    await wait(80);
    const before = urls.length;
    window.dispatchEvent(new CustomEvent("ontologyRollback", { detail: { projectId: "p1" } }));
    await wait(700);
    expect(urls.length).toBeGreaterThan(before);
  });
});

describe("SPARQL query editor", () => {
  const runQuery = async (draftMode: boolean) => {
    const post = vi.fn(async () => ({ head: { vars: [] }, results: [], executionTime: 1 }));
    const apiClient = { get: vi.fn(async () => []), post, put: vi.fn(), delete: vi.fn() };
    await act(async () => root.render(<SparqlQueryEditor projectId="p1" context={{ apiClient } as any} draftMode={draftMode} />));
    const execute = Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === "Execute") as HTMLButtonElement;
    await act(async () => execute.click());
    return post.mock.calls.map((call: any[]) => String(call[0]));
  };

  it("queries the draft version while in draft mode", async () => {
    const urls = await runQuery(true);
    expect(urls).toContain("/api/sparql/query/p1?draft=true");
  });

  it("queries the public version otherwise", async () => {
    const urls = await runQuery(false);
    expect(urls).toContain("/api/sparql/query/p1");
  });
});

describe("SWRL editor", () => {
  it("reloads its rules after an undo", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      urls.push(String(url));
      return { ok: true, status: 200, json: async () => ({ content: [] }), text: async () => "{}" };
    }));
    await act(async () => root.render(<SWRLEditor projectId="p1" />));
    await wait(100);
    const count = () => urls.filter((url) => url.includes("/api/swrl/p1/rules") && !url.includes("/rules/")).length;
    const before = count();
    expect(before).toBeGreaterThan(0);
    window.dispatchEvent(new CustomEvent("ontologyRollback", { detail: { projectId: "p1" } }));
    await wait(700);
    expect(count()).toBeGreaterThan(before);
  });
});
