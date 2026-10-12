import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { CodeAssistantContextUsed } from "../components/CodeAssistantContextUsed";
import { formatLatency, formatUsageLine, totalUsage } from "../components/codeAssistantPanelHelpers";
import type { ProviderUsage } from "../services/codeAssistantProviders";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const turn1: ProviderUsage = {
  provider: "claude",
  model: "m",
  latencyMs: 812,
  inputTokens: 1200,
  outputTokens: 40,
  cacheReadTokens: 900,
  cacheWriteTokens: 300,
};
const turn2: ProviderUsage = { provider: "claude", model: "m", latencyMs: 1450, inputTokens: 1500, outputTokens: 210, cacheReadTokens: 1100 };

describe("usage formatting", () => {
  it("lists only the counts the provider reported, then the latency", () => {
    expect(formatUsageLine(turn1)).toBe("1,200 in · 40 out · 900 cache read · 300 cache write · 812 ms");
    expect(formatUsageLine({ latencyMs: 90 })).toBe("90 ms");
    expect(formatUsageLine({ latencyMs: Number.NaN, outputTokens: 3 })).toBe("3 out");
  });

  it("shows latency in seconds past one second", () => {
    expect(formatLatency(999)).toBe("999 ms");
    expect(formatLatency(999.7)).toBe("1.0 s");
    expect(formatLatency(1450)).toBe("1.5 s");
  });

  it("adds up the turns without inventing counts nobody reported", () => {
    expect(totalUsage([turn1, turn2])).toEqual({
      latencyMs: 2262,
      inputTokens: 2700,
      outputTokens: 250,
      cacheReadTokens: 2000,
      cacheWriteTokens: 300,
    });
    expect(totalUsage([{ provider: "openai", model: "m", latencyMs: 10 }])).toEqual({ latencyMs: 10 });
  });
});

describe("CodeAssistantContextUsed", () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  function render(element: React.ReactElement) {
    act(() => root.render(element));
  }

  function toggle() {
    act(() => (container.querySelector("button") as HTMLButtonElement).click());
  }

  const event = { tool: "run_sparql", args: { query: "SELECT * WHERE { ?s ?p ?o }" }, result: { rows: [] }, isError: false };

  it("shows the turn totals next to the toggle and each turn once opened", () => {
    render(<CodeAssistantContextUsed events={[event]} usage={[turn1, turn2]} />);
    expect(container.querySelector("button")?.textContent).toContain("Context used (1)");
    expect(container.querySelector("[data-usage-total]")?.textContent).toBe(
      "· 2,700 in · 250 out · 2,000 cache read · 300 cache write · 2.3 s",
    );
    expect(container.querySelector("[data-usage-turns]")).toBeNull();

    toggle();

    const rows = Array.from(container.querySelectorAll("[data-usage-turns] li")).map((li) => li.textContent);
    expect(rows).toEqual([
      "Turn 1: 1,200 in · 40 out · 900 cache read · 300 cache write · 812 ms",
      "Turn 2: 1,500 in · 210 out · 1,100 cache read · 1.5 s",
    ]);
    expect(container.textContent).toContain("run_sparql");
  });

  it("still shows usage for an answer that needed no tools", () => {
    render(<CodeAssistantContextUsed events={[]} usage={[turn2]} />);
    expect(container.querySelector("button")?.textContent).toContain("Usage");
    toggle();
    expect(container.querySelectorAll("[data-usage-turns] li")).toHaveLength(1);
  });

  it("renders nothing without context or usage, and copes with old saved entries", () => {
    render(<CodeAssistantContextUsed events={[]} />);
    expect(container.innerHTML).toBe("");
    render(<CodeAssistantContextUsed events={[]} usage={"junk" as never} />);
    expect(container.innerHTML).toBe("");
    render(<CodeAssistantContextUsed events={[event]} usage={[null, turn2] as never} />);
    toggle();
    expect(container.querySelectorAll("[data-usage-turns] li")).toHaveLength(1);
  });
});
