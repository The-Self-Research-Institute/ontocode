import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CodeViewLoadingNotice from "../components/CodeViewLoadingNotice";

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  vi.useFakeTimers();
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  act(() => root.render(<CodeViewLoadingNotice />));
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
});

describe("Code View loading notice", () => {
  it("shows the plain message at first and no hint", () => {
    expect(host.textContent).toContain("Loading ontology content...");
    expect(host.textContent).not.toContain("large ontology");
  });

  it("explains that large ontologies take a while once it has waited a few seconds, and counts up", () => {
    act(() => { vi.advanceTimersByTime(12_000); });
    expect(host.textContent).toContain("A large ontology can take a few minutes the first time");
    expect(host.textContent).toContain("12 s so far");
  });

  it("stops its timer when it goes away", () => {
    act(() => root.unmount());
    expect(vi.getTimerCount()).toBe(0);
    root = createRoot(host);
  });
});
