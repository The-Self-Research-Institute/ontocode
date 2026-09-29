import { beforeEach, describe, expect, it, vi } from "vitest";

const mock = vi.hoisted(() => ({ config: null as any, activate: vi.fn(), deactivate: vi.fn() }));

vi.mock("@stomp/stompjs", () => ({
  Client: class {
    connectHeaders: Record<string, string> = {};
    connected = false;
    constructor(config: any) {
      mock.config = config;
    }
    activate = mock.activate;
    deactivate = mock.deactivate;
  },
}));
vi.mock("sockjs-client", () => ({ default: class {} }));

import { createCollabClient, CollabSocketHandlers } from "../utils/collabSocketClient";

function handlers(overrides: Partial<CollabSocketHandlers> = {}): CollabSocketHandlers {
  return {
    getToken: () => "t1",
    refreshToken: async () => "t2",
    onConnected: vi.fn(),
    onClosing: vi.fn(),
    onDown: vi.fn(),
    onGiveUp: vi.fn(),
    ...overrides,
  };
}

const stompError = (code: string) => mock.config.onStompError({ headers: { message: code } });

describe("createCollabClient", () => {
  beforeEach(() => {
    mock.activate.mockReset();
    mock.deactivate.mockReset().mockResolvedValue(undefined);
  });

  it("sends the current token on connect", async () => {
    createCollabClient("http://x/ws", () => handlers());
    const c: any = { connectHeaders: {}, deactivate: vi.fn() };
    await mock.config.beforeConnect(c);
    expect(c.connectHeaders.Authorization).toBe("Bearer t1");
  });

  it("gives up once when there is no token", async () => {
    const h = handlers({ getToken: () => null });
    createCollabClient("http://x/ws", () => h);
    await mock.config.beforeConnect({ connectHeaders: {} });
    stompError("AUTH_MISSING");
    expect(h.onGiveUp).toHaveBeenCalledTimes(1);
  });

  it("refreshes once on an expired token and reconnects with the new one", async () => {
    const h = handlers();
    createCollabClient("http://x/ws", () => h);
    await mock.config.beforeConnect({ connectHeaders: {} });
    stompError("AUTH_EXPIRED");
    await vi.waitFor(() => expect(mock.activate).toHaveBeenCalledTimes(1));
    const c: any = { connectHeaders: {} };
    await mock.config.beforeConnect(c);
    expect(c.connectHeaders.Authorization).toBe("Bearer t2");
    expect(h.onGiveUp).not.toHaveBeenCalled();
  });

  it("gives up when the refresh returns the same token", async () => {
    const h = handlers({ refreshToken: async () => "t1" });
    createCollabClient("http://x/ws", () => h);
    await mock.config.beforeConnect({ connectHeaders: {} });
    stompError("AUTH_INVALID");
    await vi.waitFor(() => expect(h.onGiveUp).toHaveBeenCalledTimes(1));
    expect(mock.activate).not.toHaveBeenCalled();
  });

  it("stops after a second auth failure", async () => {
    const h = handlers();
    createCollabClient("http://x/ws", () => h);
    await mock.config.beforeConnect({ connectHeaders: {} });
    stompError("AUTH_EXPIRED");
    await vi.waitFor(() => expect(mock.activate).toHaveBeenCalled());
    stompError("AUTH_EXPIRED");
    stompError("AUTH_EXPIRED");
    expect(h.onGiveUp).toHaveBeenCalledTimes(1);
  });

  it("flags a reconnect only after the first connection", () => {
    const h = handlers();
    createCollabClient("http://x/ws", () => h);
    mock.config.onConnect();
    mock.config.onConnect();
    expect((h.onConnected as any).mock.calls.map((c: any[]) => c[1])).toEqual([false, true]);
  });

  it("stop closes once and does not reconnect after a refresh", async () => {
    const h = handlers();
    const { stop } = createCollabClient("http://x/ws", () => h);
    await mock.config.beforeConnect({ connectHeaders: {} });
    stompError("AUTH_EXPIRED");
    stop();
    await vi.waitFor(() => expect(h.onClosing).toHaveBeenCalledTimes(1));
    expect(mock.activate).not.toHaveBeenCalled();
  });
});
