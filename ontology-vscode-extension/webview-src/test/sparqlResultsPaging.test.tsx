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

describe("SPARQL SELECT paging", () => {
  it("asks for one page of 100, renders only that page and shows the showing line", async () => {
    const { posts, gets } = await mount();
    expect(posts[0].pageSize).toBe(100);
    expect(bodyRows().length).toBe(100);
    expect(testId("results-showing")).toBe("Showing 1-100 of 312");
    expect(testId("results-line")).toBe("312 rows");
    expect(gets.length).toBe(0);
    expect(buttonByText("Previous")!.disabled).toBe(true);
    expect(host.querySelector('[data-testid="truncation-notice"]')).toBeNull();
  });

  it("goes to the next and last page through the server and never polls", async () => {
    const { gets } = await mount();
    await act(async () => buttonByText("Next")!.click());
    await wait(30);
    expect(gets.length).toBe(1);
    expect(gets[0]).toContain("offset=100");
    expect(gets[0]).toContain("limit=100");
    expect(testId("results-showing")).toBe("Showing 101-200 of 312");
    expect(bodyRows()[0][0]).toBe('"item101"');

    await act(async () => buttonByText("Last")!.click());
    await wait(30);
    expect(testId("results-showing")).toBe("Showing 301-312 of 312");
    expect(bodyRows().length).toBe(12);
    expect(buttonByText("Next")!.disabled).toBe(true);

    await act(async () => buttonByText("First")!.click());
    await wait(30);
    expect(testId("results-showing")).toBe("Showing 1-100 of 312");
    const settled = gets.length;
    await wait(400);
    expect(gets.length).toBe(settled);
  });

  it("sends the filter once after the pause and reports the filtered count", async () => {
    const { gets } = await mount();
    const input = host.querySelector('input[aria-label="Filter results"]') as HTMLInputElement;
    await setNativeValue(input, "i", "input");
    await setNativeValue(input, "it", "input");
    await setNativeValue(input, "item29", "input");
    await wait(300);
    expect(gets.length).toBe(1);
    expect(gets[0]).toContain("filter=item29");
    expect(testId("results-showing")).toBe("Showing 1-11 of 11 (filtered from 312)");
    expect(bodyRows().length).toBe(11);

    await typeFilter("zzzz");
    expect(host.textContent).toContain("No rows match the filter.");
    expect(testId("results-showing")).toBe("Showing 0 of 0 (filtered from 312)");
  });

  it("sorts through the server on a header click and toggles the direction", async () => {
    const { gets } = await mount();
    const header = () => Array.from(host.querySelectorAll("th")).find((th) => th.textContent?.startsWith("?n")) as HTMLElement;
    await act(async () => header().click());
    await wait(30);
    expect(gets[gets.length - 1]).toContain("sort=n");
    expect(gets[gets.length - 1]).toContain("dir=asc");
    expect(header().textContent).toContain("▲");
    await act(async () => header().click());
    await wait(30);
    expect(gets[gets.length - 1]).toContain("dir=desc");
    expect(header().textContent).toContain("▼");
    await act(async () => header().click());
    await wait(30);
    expect(gets[gets.length - 1]).not.toContain("sort=");
  });

  it("changes the page size and asks the server for that many rows", async () => {
    const { gets } = await mount();
    await setNativeValue(host.querySelector('select[aria-label="Rows per page"]') as HTMLSelectElement, "250", "change");
    await wait(30);
    expect(gets[gets.length - 1]).toContain("limit=250");
    expect(bodyRows().length).toBe(250);
    expect(testId("results-showing")).toBe("Showing 1-250 of 312");
  });

  it("says plainly when the cap was hit and how many rows were seen", async () => {
    await mount({ firstResponse: first({ truncated: true, totalRows: 4321, totalRowsExact: false, storedRows: 1000, filteredRows: 1000 }) });
    expect(testId("truncation-notice")).toBe("Showing the first 1,000 of at least 4,321 rows. Narrow the query or add LIMIT.");
    expect(testId("results-line")).toBe("1,000 of at least 4,321 rows");
    expect(testId("results-showing")).toBe("Showing 1-100 of 1,000");
  });

  it("shows the expiry message with a Run again button when the run is gone", async () => {
    const { posts } = await mount({ expire: true });
    await act(async () => buttonByText("Next")!.click());
    await wait(30);
    expect(testId("results-expired")).toContain("This result has expired. Run the query again.");
    await act(async () => buttonByText("Run again")!.click());
    await wait(50);
    expect(posts.length).toBe(2);
  });

  it("shows a page problem in plain words and keeps the rows", async () => {
    await mount({ rowsOverride: () => { throw Object.assign(new Error("x"), { data: { message: "The server is busy. Try again in a moment." } }); } });
    await act(async () => buttonByText("Next")!.click());
    await wait(30);
    expect(testId("page-note")).toBe("The server is busy. Try again in a moment.");
    expect(bodyRows().length).toBe(100);
  });

  it("falls back to local paging when the server sent no run", async () => {
    const legacy = { type: "select", head: { vars: ["item", "n"] }, results: [], bindings: ALL.slice(0, 250), executionTime: 5 };
    const { gets } = await mount({ firstResponse: legacy });
    expect(bodyRows().length).toBe(100);
    expect(testId("results-showing")).toBe("Showing 1-100 of 250");
    await act(async () => buttonByText("Last")!.click());
    expect(testId("results-showing")).toBe("Showing 201-250 of 250");
    await typeFilter("item24");
    expect(testId("results-showing")).toBe("Showing 1-11 of 11 (filtered from 250)");
    expect(gets.length).toBe(0);
  });

  it("notes a truncated old style response that has no run", async () => {
    const legacy = { type: "select", head: { vars: ["item"] }, results: [], bindings: ALL.slice(0, 5), truncated: true };
    await mount({ firstResponse: legacy });
    expect(testId("truncation-notice")).toBe("Showing the first 5 rows; the rest were cut off. Narrow the query or add LIMIT.");
  });

  it("copies only the page on screen", async () => {
    await mount();
    const written: string[] = [];
    Object.assign(navigator, { clipboard: { writeText: async (t: string) => { written.push(t); } } });
    await act(async () => (host.querySelector('button[title="Copy this page to clipboard"]') as HTMLButtonElement).click());
    expect(written.length).toBe(1);
    expect(written[0].split("\n").length).toBe(100);
  });

  it("downloads the filtered set through the export call and notes a cut export", async () => {
    const { gets } = await mount({
      rowsOverride: (params) => params.get("export") === "true"
        ? { bindings: ALL.slice(0, 40), filteredRows: 90 }
        : { bindings: ALL.slice(0, 11), filteredRows: 11, offset: 0 }
    });
    await typeFilter("item");
    const blobs: Blob[] = [];
    (URL as any).createObjectURL = (b: Blob) => { blobs.push(b); return "blob:x"; };
    (URL as any).revokeObjectURL = () => {};
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    await act(async () => (host.querySelector('button[title="Download CSV"]') as HTMLButtonElement).click());
    await wait(30);
    expect(gets.some((u) => u.includes("export=true") && u.includes("filter=item"))).toBe(true);
    expect((await readBlob(blobs[0])).split("\n").length).toBe(41);
    expect(testId("page-note")).toBe("Downloaded the first 40 of 90 matching rows. Add a filter to get the rest.");
  });

  it("shows only the current page on the JSON tab", async () => {
    await mount();
    await act(async () => buttonByText("JSON")!.click());
    const text = host.querySelector("pre")?.textContent ?? "";
    expect(JSON.parse(text).results.bindings.length).toBe(100);
  });
});
