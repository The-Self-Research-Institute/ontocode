import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  CHANGE_ASSISTANT_PENDING_TAB,
  requestChangeAssistantTab,
} from "../utils/changeAssistantNav";
import { useRequestedTab } from "../../../plugins/change-assistant-plugin/src/assistant/hooks/useRequestedTab";
import ChangeAssistant from "../../../plugins/change-assistant-plugin/src/ChangeAssistant";

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  (window as any)[CHANGE_ASSISTANT_PENDING_TAB] = undefined;
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

describe("requestChangeAssistantTab", () => {
  it("remembers the tab for a page that is not open yet and announces it to one that is", () => {
    const heard = vi.fn();
    window.addEventListener("change-assistant:show-tab", heard);
    requestChangeAssistantTab("changes");
    window.removeEventListener("change-assistant:show-tab", heard);
    expect((window as any)[CHANGE_ASSISTANT_PENDING_TAB]).toBe("changes");
    expect(heard).toHaveBeenCalledTimes(1);
  });
});

describe("useRequestedTab", () => {
  const Probe: React.FC<{ onSelect: (tab: string) => void }> = ({ onSelect }) => {
    useRequestedTab(onSelect as any);
    return null;
  };

  it("applies a request made before the page opened, once", async () => {
    const select = vi.fn();
    requestChangeAssistantTab("changes");
    await act(async () => root.render(<Probe onSelect={select} />));
    expect(select).toHaveBeenCalledWith("changes");
    expect((window as any)[CHANGE_ASSISTANT_PENDING_TAB]).toBeUndefined();
  });

  it("applies a request made while the page is open and ignores unknown tabs", async () => {
    const select = vi.fn();
    await act(async () => root.render(<Probe onSelect={select} />));
    expect(select).not.toHaveBeenCalled();
    act(() => { window.dispatchEvent(new CustomEvent("change-assistant:show-tab", { detail: { tab: "stats" } })); });
    expect(select).toHaveBeenCalledWith("stats");
    act(() => { window.dispatchEvent(new CustomEvent("change-assistant:show-tab", { detail: { tab: "nonsense" } })); });
    expect(select).toHaveBeenCalledTimes(1);
  });
});

describe("Change Assistant", () => {
  const activeTabLabel = () => {
    const active = Array.from(host.querySelectorAll("button")).find((el) => el.className.includes("border-purple-600"));
    return active?.textContent?.replace(/\d+$/, "").trim();
  };

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ content: [], data: [] }), text: async () => "[]" })));
  });

  it("opens on Activity by default", async () => {
    await act(async () => root.render(<ChangeAssistant projectId="p1" />));
    await wait(50);
    expect(activeTabLabel()).toBe("Activity");
  });

  it("opens on Saved when View full history asked for it before the page loaded", async () => {
    requestChangeAssistantTab("changes");
    await act(async () => root.render(<ChangeAssistant projectId="p1" />));
    await wait(50);
    expect(activeTabLabel()).toBe("Saved");
  });

  it("switches to Saved when it is already open", async () => {
    await act(async () => root.render(<ChangeAssistant projectId="p1" />));
    await wait(50);
    act(() => requestChangeAssistantTab("changes"));
    await wait(20);
    expect(activeTabLabel()).toBe("Saved");
  });
});
