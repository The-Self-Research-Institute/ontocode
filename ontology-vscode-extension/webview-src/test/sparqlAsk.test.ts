import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { runSparqlHighlight } from "../../../plugins/graph-view-plugin/src/renderers/sparqlAsk";

const makeGraph = () => {
  const nodes = new Set(["http://ex.org/a", "http://ex.org/b"]);
  return { hasNode: (id: string) => nodes.has(id) } as any;
};

const respond = (data: any, ok = true, status = 200) =>
  vi.fn(async () => ({ ok, status, json: async () => data }));

beforeEach(() => {
  (window as any).API_BASE_URL = "http://api.test";
  localStorage.removeItem("authToken");
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete (window as any).API_BASE_URL;
});

describe("runSparqlHighlight", () => {
  it("sends the draft flag only in draft mode", async () => {
    const fetchMock = respond({ bindings: [] });
    vi.stubGlobal("fetch", fetchMock);
    await runSparqlHighlight(makeGraph(), "SELECT * WHERE {?s ?p ?o}", "p 1", true);
    expect((fetchMock.mock.calls[0] as any)[0]).toBe("http://api.test/api/sparql/query/p%201?draft=true");
    await runSparqlHighlight(makeGraph(), "SELECT * WHERE {?s ?p ?o}", "p1");
    expect((fetchMock.mock.calls[1] as any)[0]).toBe("http://api.test/api/sparql/query/p1");
  });

  it("reads values from bindings objects", async () => {
    vi.stubGlobal("fetch", respond({
      head: { vars: ["x"] },
      bindings: [{ x: { type: "uri", value: "http://ex.org/a" } }, { x: { type: "literal", value: "text" } }]
    }));
    const result = await runSparqlHighlight(makeGraph(), "SELECT ?x WHERE {?x ?p ?o}", "p1");
    expect([...result.answers]).toEqual(["http://ex.org/a"]);
    expect(result.rows).toEqual([{ x: "http://ex.org/a" }, { x: "text" }]);
    expect(result.summary).toContain("2 results, 1 in view");
  });

  it("falls back to the legacy plain-string results", async () => {
    vi.stubGlobal("fetch", respond({ results: [{ x: "http://ex.org/b" }] }));
    const result = await runSparqlHighlight(makeGraph(), "SELECT ?x WHERE {?x ?p ?o}", "p1");
    expect([...result.answers]).toEqual(["http://ex.org/b"]);
    expect(result.rows).toEqual([{ x: "http://ex.org/b" }]);
  });

  it("reports server errors in plain words", async () => {
    vi.stubGlobal("fetch", respond({ error: "bad query" }, false, 400));
    const result = await runSparqlHighlight(makeGraph(), "SELECT", "p1");
    expect(result.summary).toBe("SPARQL error: bad query");
    expect(result.answers.size).toBe(0);
  });
});
