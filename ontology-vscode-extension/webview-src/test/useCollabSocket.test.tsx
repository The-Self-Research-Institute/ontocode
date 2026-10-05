import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

const mock = vi.hoisted(() => ({ created: [] as any[], handlers: null as any }));

vi.mock("../services/apiClient", () => ({ getBaseUrl: () => "http://localhost:8083" }));
vi.mock("../utils/collabSocketClient", () => ({
  createCollabClient: (_url: string, handlers: () => any) => {
    const entry = { handlers, activate: vi.fn(), stop: vi.fn() };
    mock.created.push(entry);
    return { client: { activate: entry.activate }, stop: entry.stop };
  },
}));

import { useCollabSocket } from "../hooks/useCollabSocket";

const noopHandlers: any = {
  getToken: () => "t",
  refreshToken: async () => "t",
  onConnected: vi.fn(),
  onClosing: vi.fn(),
  onDown: vi.fn(),
  onGiveUp: vi.fn(),
};

describe("useCollabSocket", () => {
  let root: Root;

  function Probe({ token }: { token: string }) {
    useCollabSocket(true, "user-1", token, noopHandlers);
    return null;
  }

  beforeEach(() => {
    mock.created.length = 0;
    root = createRoot(document.createElement("div"));
  });

  afterEach(() => act(() => root.unmount()));

  const render = (token: string) => act(() => root.render(<Probe token={token} />));

  it("keeps one connection across ordinary token refreshes", () => {
    render("t1");
    render("t2");
    expect(mock.created).toHaveLength(1);
  });

  it("reconnects with a new token after the client gave up", () => {
    render("t1");
    act(() => mock.created[0].handlers().onGiveUp("AUTH_EXPIRED"));
    expect(mock.created).toHaveLength(1);
    render("t2");
    expect(mock.created).toHaveLength(2);
    expect(mock.created[0].stop).toHaveBeenCalled();
    expect(mock.created[1].activate).toHaveBeenCalled();
  });

  it("still reports the give-up to the caller", () => {
    render("t1");
    mock.created[0].handlers().onGiveUp("AUTH_MISSING");
    expect(noopHandlers.onGiveUp).toHaveBeenCalledWith("AUTH_MISSING");
  });
});
