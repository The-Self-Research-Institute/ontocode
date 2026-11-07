import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CodeHighlighter } from "../components/CodeHighlighter";
import { escapeHtml } from "../utils/escapeHtml";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.spyOn(console, "log").mockImplementation(() => {});
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("escapeHtml", () => {
  it("escapes markup and quotes", () => {
    expect(escapeHtml(`<a href="x">'&'</a>`)).toBe("&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;");
  });
});

describe("CodeHighlighter DOI rendering", () => {
  it("does not let a crafted DOI break out of attributes", () => {
    const content = `<bibo:doi>x" data-injected="1" title="y</bibo:doi>`;
    act(() => root.render(<CodeHighlighter content={content} format="rdfxml" readOnly />));
    expect(container.querySelector("[data-injected]")).toBeNull();
    const line = container.querySelector(".doi-line") as HTMLElement | null;
    expect(line).not.toBeNull();
    expect(line!.getAttribute("data-doi")).toBe(`x" data-injected="1" title="y`);
  });
});
