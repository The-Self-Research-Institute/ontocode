import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SparqlQueryEditor } from "../../../plugins/sparql-query-plugin/src/SparqlQueryEditor";

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
});

const setQuery = async (text: string) => {
  const area = host.querySelector("textarea") as HTMLTextAreaElement;
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!;
  await act(async () => {
    setter.call(area, text);
    area.dispatchEvent(new Event("input", { bubbles: true }));
  });
};

const execute = async () => {
  const button = Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === "Execute") as HTMLButtonElement;
  await act(async () => button.click());
};

const mount = async (post: any) => {
  const apiClient = { get: vi.fn(async () => []), post, put: vi.fn(), delete: vi.fn() };
  await act(async () => root.render(<SparqlQueryEditor projectId="p1" context={{ apiClient } as any} />));
};

describe("SPARQL result kinds", () => {
  it("shows a graph result with a format dropdown and re-runs on change", async () => {
    const post = vi.fn(async (_url: string, body: any) => ({
      type: "graph",
      format: body.format,
      content: body.format === "ntriples" ? "<a> <b> <c> ." : "@prefix ex: <http://ex.org/> .\nex:a ex:b ex:c .",
      tripleCount: 1,
      truncated: false,
      executionTime: 3
    }));
    await mount(post);
    await setQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } LIMIT 5");
    await execute();
    expect(post.mock.calls[0][1]).toEqual({ query: "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o } LIMIT 5", format: "turtle" });
    expect(host.querySelector("[data-testid=graph-result]")?.textContent).toContain("ex:a ex:b ex:c");

    const select = host.querySelector("select[aria-label='Result format']") as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.text)).toEqual(["Turtle", "JSON-LD", "RDF/XML", "N-Triples"]);
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")!.set!;
    await act(async () => {
      setter.call(select, "ntriples");
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(post).toHaveBeenCalledTimes(2);
    expect(post.mock.calls[1][1].format).toBe("ntriples");
    expect(host.querySelector("[data-testid=graph-result]")?.textContent).toBe("<a> <b> <c> .");
  });

  it("notes a truncated graph result", async () => {
    const post = vi.fn(async () => ({ type: "graph", format: "turtle", content: "x", tripleCount: 10, truncated: true }));
    await mount(post);
    await setQuery("DESCRIBE <http://ex.org/a>");
    await execute();
    expect(host.textContent).toContain("Showing first 10 triples");
  });

  it("shows an ASK answer as a true or false chip", async () => {
    const post = vi.fn(async () => ({ type: "boolean", boolean: true, executionTime: 1 }));
    await mount(post);
    await setQuery("ASK { ?s ?p ?o }");
    await execute();
    expect(host.querySelector("[data-testid=boolean-result]")?.textContent).toBe("true");
  });

  it("renders typed literals, languages, IRIs and blank nodes without shortening literals", async () => {
    const post = vi.fn(async () => ({
      type: "select",
      head: { vars: ["a", "b", "c", "d", "e"] },
      results: [{ a: "x" }],
      bindings: [{
        a: { type: "uri", value: "http://ex.org/onto#Thing" },
        b: { type: "literal", value: "1", datatype: "http://www.w3.org/2001/XMLSchema#int" },
        c: { type: "literal", value: "hello", "xml:lang": "en" },
        d: { type: "literal", value: "path/to/file" },
        e: { type: "bnode", value: "b0" }
      }],
      executionTime: 2
    }));
    await mount(post);
    await execute();
    const cells = Array.from(host.querySelectorAll("tbody td")).map((td) => td.textContent);
    expect(cells).toEqual(["Thing", '"1"^^xsd:int', '"hello"@en', '"path/to/file"', "_:b0"]);
  });

  it("falls back to plain strings from an older response", async () => {
    const post = vi.fn(async () => ({ head: { vars: ["a"] }, results: [{ a: "http://ex.org/onto#Old" }], executionTime: 1 }));
    await mount(post);
    await execute();
    expect(Array.from(host.querySelectorAll("tbody td")).map((td) => td.textContent)).toEqual(["Old"]);
  });

  it("shows an update hint and does not send the query", async () => {
    const post = vi.fn(async () => ({}));
    await mount(post);
    await setQuery("INSERT DATA { <a> <b> <c> }");
    expect(host.textContent).toContain("This is an update; use the update option");
    await execute();
    expect(post).not.toHaveBeenCalled();
  });
});
