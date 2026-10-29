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
  vi.restoreAllMocks();
});

const wait = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });
const buttonByText = (text: string) => Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === text) as HTMLButtonElement | undefined;
const bodyRows = () => Array.from(host.querySelectorAll("tbody tr")).map((tr) => Array.from(tr.querySelectorAll("td")).map((td) => td.textContent));
const testId = (id: string) => host.querySelector(`[data-testid="${id}"]`)?.textContent ?? "";
const readBlob = (b: Blob) => new Promise<string>((resolve) => { const r = new FileReader(); r.onload = () => resolve(String(r.result)); r.readAsText(b); });

const term = (value: string) => ({ type: "literal", value });
const makeRows = (from: number, count: number) =>
  Array.from({ length: count }, (_, i) => ({ item: term(`item${from + i + 1}`), n: { type: "literal", value: String(((from + i) * 7) % 90), datatype: "http://www.w3.org/2001/XMLSchema#integer" } }));

const setNativeValue = async (el: HTMLInputElement | HTMLSelectElement, value: string, event: string) => {
  const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  await act(async () => {
    Object.getOwnPropertyDescriptor(proto, "value")!.set!.call(el, value);
    el.dispatchEvent(new Event(event, { bubbles: true }));
  });
};

const typeFilter = async (value: string) => {
  await setNativeValue(host.querySelector('input[aria-label="Filter results"]') as HTMLInputElement, value, "input");
  await wait(300);
};

const setQuery = async (text: string) => {
  const area = host.querySelector("textarea") as HTMLTextAreaElement;
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!;
  await act(async () => {
    setter.call(area, text);
    area.dispatchEvent(new Event("input", { bubbles: true }));
  });
};

const executeButton = () => Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === "Execute") as HTMLButtonElement;

const ALL = makeRows(0, 312);

const first = (extra: Record<string, unknown> = {}, count = 100) => ({
  type: "select",
  head: { vars: ["item", "n"] },
  vars: ["item", "n"],
  results: [],
  bindings: ALL.slice(0, count),
  executionTime: 12,
  runId: "run1",
  totalRows: 312,
  totalRowsExact: true,
  rowLimit: 1000,
  bytesCapped: false,
  pageSize: count,
  filteredRows: 312,
  storedRows: 312,
  ...extra
});

const mount = async (opts: { firstResponse?: any; expire?: boolean; rowsOverride?: (params: URLSearchParams) => any } = {}) => {
  const gets: string[] = [];
  const posts: any[] = [];
  const get = vi.fn(async (url: string) => {
    if (!url.includes("/runs/")) return [];
    gets.push(url);
    if (opts.expire) throw Object.assign(new Error("gone"), { status: 410, data: { message: "This result has expired. Run the query again." } });
    const params = new URL(url, "http://h").searchParams;
    if (opts.rowsOverride) return opts.rowsOverride(params);
    const filter = (params.get("filter") || "").toLowerCase();
    let view = ALL.filter((r) => !filter || r.item.value.includes(filter) || r.n.value.includes(filter));
    const sort = params.get("sort");
    if (sort) {
      const dir = params.get("dir") === "desc" ? -1 : 1;
      view = [...view].sort((a: any, b: any) => (Number(a[sort].value) - Number(b[sort].value)) * dir);
    }
    if (params.get("export") === "true") return { bindings: view, filteredRows: view.length };
    const offset = Number(params.get("offset"));
    const limit = Number(params.get("limit"));
    return { bindings: view.slice(offset, offset + limit), filteredRows: view.length, offset };
  });
  const post = vi.fn(async (_url: string, body: any) => {
    posts.push(body);
    return opts.firstResponse ?? first();
  });
  const apiClient = { get, post, put: vi.fn(), delete: vi.fn() };
  await act(async () => root.render(<SparqlQueryEditor projectId="p1" context={{ apiClient } as any} />));
  await setQuery("SELECT ?item ?n WHERE { ?item ?p ?n }");
  await act(async () => executeButton().click());
  await wait(50);
  return { gets, posts };
};


const panel = () => host.querySelector('[data-testid="results-panel"]') as HTMLDivElement;
const handle = () => host.querySelector('[data-testid="results-resize-handle"]') as HTMLDivElement;

const mockRects = () => {
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    const testid = this.getAttribute("data-testid");
    if (testid === "results-panel") return { top: 100, bottom: 100, left: 0, right: 0, width: 0, height: 0, x: 0, y: 100, toJSON() {} } as DOMRect;
    if (this === panel()?.parentElement) return { top: 84, bottom: 884, left: 0, right: 0, width: 0, height: 800, x: 0, y: 84, toJSON() {} } as DOMRect;
    return { top: 0, bottom: 0, left: 0, right: 0, width: 0, height: 0, x: 0, y: 0, toJSON() {} } as DOMRect;
  });
};

const dragTo = async (clientY: number) => {
  await act(async () => { handle().dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true })); });
  await act(async () => { document.dispatchEvent(new MouseEvent("mousemove", { bubbles: true, cancelable: true, clientY })); });
  await act(async () => { document.dispatchEvent(new MouseEvent("mouseup", { bubbles: true })); });
};

const heightPx = () => parseFloat(panel().style.height);

describe("SPARQL results panel height", () => {
  it("fills the available height by default with no fixed pixel height", async () => {
    await mount();
    expect(panel().style.height).toBe("");
    expect(panel().className).toContain("flex-1");
    expect(testId("results-showing")).toBe("Showing 1-100 of 312");
  });

  it("drag down grows, drag up shrinks, clamps at both ends and double-click resets", async () => {
    await mount();
    mockRects();
    await dragTo(500);
    expect(heightPx()).toBe(400);
    await dragTo(650);
    expect(heightPx()).toBe(550);
    await dragTo(450);
    expect(heightPx()).toBe(350);
    await dragTo(110);
    expect(heightPx()).toBe(200);
    await dragTo(5000);
    expect(heightPx()).toBe(760);
    await act(async () => { handle().dispatchEvent(new MouseEvent("dblclick", { bubbles: true })); });
    expect(panel().style.height).toBe("");
    expect(panel().className).toContain("flex-1");
  });
});
