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
  vi.restoreAllMocks();
});

const wait = (ms: number) => act(async () => { await new Promise((resolve) => setTimeout(resolve, ms)); });
const reply = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body, text: async () => JSON.stringify(body) });
const buttonByText = (text: string) => Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === text) as HTMLButtonElement | undefined;
const buttonStarting = (text: string) => Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim().startsWith(text)) as HTMLButtonElement | undefined;
const bodyRows = () => Array.from(host.querySelectorAll("tbody tr")).map((tr) => Array.from(tr.querySelectorAll("td")).slice(1).map((td) => td.textContent));
const testId = (id: string) => host.querySelector(`[data-testid="${id}"]`)?.textContent ?? "";

const readBlob = (b: Blob) => new Promise<string>((resolve) => { const r = new FileReader(); r.onload = () => resolve(String(r.result)); r.readAsText(b); });

const makeRows = (count: number) =>
  Array.from({ length: count }, (_, i) => ({ p: `person${i + 1}`, age: String((i * 7) % 90) }));

const setup = async (response: Record<string, unknown>) => {
  const posts: any[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: any) => {
    const u = String(url);
    if (u.includes("/sqwrl/query")) {
      posts.push(JSON.parse(init.body));
      return reply(response);
    }
    if (u.endsWith("/api/ontology/p1/rules")) return reply({ rules: [] });
    return reply({});
  }));
  await act(async () => root.render(<SWRLEditor projectId="p1" />));
  await wait(100);
  await act(async () => buttonStarting("SQWRL Query")!.click());
  await wait(20);
  await act(async () => buttonStarting("Execute Query")!.click());
  await wait(100);
  return { posts };
};

const typeFilter = async (value: string) => {
  const input = host.querySelector('input[aria-label="Filter results"]') as HTMLInputElement;
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
  await act(async () => {
    setter.call(input, value);
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await wait(300);
};

const full = (rows: any[], extra: Record<string, unknown> = {}) => ({
  success: true, queryName: "q", queryText: "x", executionTimeMs: 12, rowCount: rows.length,
  columnNames: ["p", "age"], rows, ...extra,
});

describe("SQWRL results", () => {
  it("pages 50 at a time with a showing line and the page size choice", async () => {
    await setup(full(makeRows(312), { totalRows: 312, totalRowsExact: true, truncated: false, rowLimit: 1000, bytesCapped: false }));
    expect(bodyRows().length).toBe(50);
    expect(testId("sqwrl-showing")).toBe("Showing 1-50 of 312");
    expect(testId("sqwrl-results-line")).toContain("312 results in 12ms");
    expect(buttonByText("Previous")!.disabled).toBe(true);

    await act(async () => buttonByText("Next")!.click());
    expect(testId("sqwrl-showing")).toBe("Showing 51-100 of 312");
    expect(bodyRows()[0][0]).toBe("person51");

    await act(async () => buttonByText("Last")!.click());
    expect(testId("sqwrl-showing")).toBe("Showing 301-312 of 312");
    expect(buttonByText("Next")!.disabled).toBe(true);

    await act(async () => buttonByText("First")!.click());
    expect(testId("sqwrl-showing")).toBe("Showing 1-50 of 312");

    const select = host.querySelector('select[aria-label="Rows per page"]') as HTMLSelectElement;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")!.set!.call(select, "250");
      select.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(bodyRows().length).toBe(250);
    expect(testId("sqwrl-showing")).toBe("Showing 1-250 of 312");
  });

  it("filters across all columns after the debounce and reports the filtered count", async () => {
    await setup(full(makeRows(312), { totalRows: 312, truncated: false }));
    await typeFilter("PERSON31");
    expect(bodyRows().map((r) => r[0])).toEqual(["person31", "person310", "person311", "person312"]);
    expect(testId("sqwrl-showing")).toBe("Showing 1-4 of 4 (filtered from 312)");

    await typeFilter("zzzz");
    expect(host.textContent).toContain("No rows match the filter.");
    expect(testId("sqwrl-showing")).toBe("Showing 0 of 0 (filtered from 312)");
  });

  it("sorts numerically on header click and toggles direction", async () => {
    await setup(full([{ p: "a", age: "30" }, { p: "b", age: "4" }, { p: "c", age: "100" }]));
    const header = () => Array.from(host.querySelectorAll("th")).find((th) => th.textContent?.startsWith("age")) as HTMLElement;
    await act(async () => header().click());
    expect(bodyRows().map((r) => r[1])).toEqual(["4", "30", "100"]);
    expect(header().textContent).toContain("▲");
    await act(async () => header().click());
    expect(bodyRows().map((r) => r[1])).toEqual(["100", "30", "4"]);
    expect(header().textContent).toContain("▼");
    await act(async () => header().click());
    expect(bodyRows().map((r) => r[1])).toEqual(["30", "4", "100"]);
  });

  it("shows the truncation notice and N of TOTAL when the server cut the rows", async () => {
    await setup(full(makeRows(1000), { totalRows: 5000, totalRowsExact: true, truncated: true, rowLimit: 1000 }));
    expect(testId("sqwrl-truncation-notice")).toBe("Showing the first 1,000 of 5,000 results. Narrow the query, add a filter in the query, or use sqwrl:limit.");
    expect(testId("sqwrl-results-line")).toContain("1,000 of 5,000 results in 12ms");
  });

  it("says more than 100,000 when the count is not exact", async () => {
    await setup(full(makeRows(1000), { totalRows: 100000, totalRowsExact: false, truncated: true, rowLimit: 1000 }));
    expect(testId("sqwrl-truncation-notice")).toContain("of more than 100,000 results");
  });

  it("still renders a response from an older backend without the new fields", async () => {
    await setup(full(makeRows(3)));
    expect(bodyRows().length).toBe(3);
    expect(host.querySelector('[data-testid="sqwrl-truncation-notice"]')).toBeNull();
    expect(testId("sqwrl-results-line")).toContain("3 results in 12ms");
  });

  it("copies and downloads only the filtered rows, with formula-safe CSV", async () => {
    const rows = [{ p: "=cmd", age: "1" }, { p: "alice", age: "2" }, { p: "alina, jr", age: "3" }];
    await setup(full(rows));
    await typeFilter("ali");

    const written: string[] = [];
    vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText: async (t: string) => { written.push(t); } } });
    await act(async () => buttonByText("Copy (TSV)")!.click());
    await wait(20);
    expect(written).toEqual(["p\tage\nalice\t2\nalina, jr\t3\n"]);

    const blobs: Blob[] = [];
    (URL as any).createObjectURL = (b: Blob) => { blobs.push(b); return "blob:x"; };
    (URL as any).revokeObjectURL = () => {};
    const clicked: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) { clicked.push(this.download); });
    await act(async () => buttonByText("Download CSV")!.click());
    expect(clicked).toEqual(["sqwrl-results.csv"]);
    expect(await readBlob(blobs[0])).toBe('p,age\r\nalice,2\r\n"alina, jr",3\r\n');

    await typeFilter("");
    await act(async () => buttonByText("Download CSV")!.click());
    expect((await readBlob(blobs[1])).split("\r\n")[1]).toBe("'=cmd,1");
  });
});

const ALL = makeRows(300);

const serverSetup = async (opts: { expireRows?: boolean; busy?: boolean } = {}) => {
  const rowUrls: string[] = [];
  const posts: any[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: any) => {
    const u = String(url);
    if (u.includes("/sqwrl/query")) {
      if (opts.busy) return reply({ busy: true, message: "The rule engine is busy. Try again in a moment." }, 429);
      const body = JSON.parse(init.body);
      posts.push(body);
      return reply(full(ALL.slice(0, body.pageSize), {
        runId: "run1", totalRows: 300, totalRowsExact: true, truncated: false, rowLimit: 1000,
        storedRows: 300, filteredRows: 300, pageSize: body.pageSize, rowCount: body.pageSize,
      }));
    }
    if (u.includes("/sqwrl/runs/run1/rows")) {
      rowUrls.push(u);
      if (opts.expireRows) return reply({ expired: true, message: "This result has expired. Run the query again." }, 410);
      const params = new URL(u, "http://h").searchParams;
      const filter = (params.get("filter") || "").toLowerCase();
      const view = ALL.filter((r) => !filter || r.p.includes(filter) || r.age.includes(filter));
      if (params.get("export") === "true") return reply({ rows: view, filteredRows: view.length });
      const offset = Number(params.get("offset"));
      const limit = Number(params.get("limit"));
      return reply({ rows: view.slice(offset, offset + limit), filteredRows: view.length, offset });
    }
    if (u.endsWith("/api/ontology/p1/rules")) return reply({ rules: [] });
    return reply({});
  }));
  await act(async () => root.render(<SWRLEditor projectId="p1" />));
  await wait(100);
  await act(async () => buttonStarting("SQWRL Query")!.click());
  await wait(20);
  await act(async () => buttonStarting("Execute Query")!.click());
  await wait(100);
  return { rowUrls, posts };
};

describe("SQWRL results with a server side run", () => {
  it("asks for one page, pages and filters through the server and renders only that page", async () => {
    const { rowUrls, posts } = await serverSetup();
    expect(posts[0].pageSize).toBe(50);
    expect(bodyRows().length).toBe(50);
    expect(rowUrls.length).toBe(0);
    expect(testId("sqwrl-showing")).toBe("Showing 1-50 of 300");

    await act(async () => buttonByText("Next")!.click());
    await wait(50);
    expect(rowUrls.length).toBe(1);
    expect(rowUrls[0]).toContain("offset=50");
    expect(rowUrls[0]).toContain("limit=50");
    expect(bodyRows()[0][0]).toBe("person51");
    expect(bodyRows().length).toBe(50);

    await typeFilter("person29");
    await wait(50);
    expect(rowUrls[rowUrls.length - 1]).toContain("filter=person29");
    expect(testId("sqwrl-showing")).toBe("Showing 1-11 of 11 (filtered from 300)");
    expect(bodyRows().length).toBe(11);
    const settled = rowUrls.length;
    await wait(400);
    expect(rowUrls.length).toBe(settled);
  });

  it("downloads the whole filtered result through the export call", async () => {
    const { rowUrls } = await serverSetup();
    await typeFilter("person29");
    await wait(50);
    const blobs: Blob[] = [];
    (URL as any).createObjectURL = (b: Blob) => { blobs.push(b); return "blob:x"; };
    (URL as any).revokeObjectURL = () => {};
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    await act(async () => buttonByText("Download CSV")!.click());
    await wait(50);
    expect(rowUrls.some((u) => u.includes("export=true") && u.includes("filter=person29"))).toBe(true);
    expect((await readBlob(blobs[0])).split("\r\n").length).toBe(11 + 2);
  });

  it("shows the expired message with a Run again button on 410", async () => {
    await serverSetup({ expireRows: true });
    await act(async () => buttonByText("Next")!.click());
    await wait(50);
    expect(testId("sqwrl-expired")).toContain("This result has expired. Run the query again.");
    expect(buttonByText("Run again")).toBeTruthy();
  });

  it("shows the busy message plainly when the engine refuses the query", async () => {
    await serverSetup({ busy: true });
    expect(host.textContent).toContain("The rule engine is busy. Try again in a moment.");
  });
});
