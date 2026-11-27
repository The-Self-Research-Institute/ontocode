import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const desktop = vi.hoisted(() => ({ value: false }));
vi.mock("../utils/desktop", () => ({ isDesktop: () => desktop.value }));

import { PLAN_REFRESH_INTERVAL_MS, tokenAgeMs, usePlanFreshness } from "../hooks/usePlanFreshness";

const tokenIssuedAt = (millis: number) =>
  `h.${btoa(JSON.stringify({ sub: "member", iat: Math.floor(millis / 1000) }))}.s`;

describe("usePlanFreshness", () => {
  let root: Root;
  let host: HTMLDivElement;
  let clock: number;
  const now = () => clock;

  const Probe: React.FC<{ token?: string; workspaceId?: string; refresh: () => Promise<void> }> = (props) => {
    usePlanFreshness(props.token, props.workspaceId, props.refresh, now);
    return null;
  };

  const mount = async (props: { token?: string; workspaceId?: string; refresh: () => Promise<void> }) => {
    await act(async () => root.render(<Probe {...props} />));
  };

  const focus = async () => {
    await act(async () => window.dispatchEvent(new Event("focus")));
  };

  beforeEach(() => {
    (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
    desktop.value = false;
    clock = 1_800_000_000_000;
    host = document.createElement("div");
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  it("gets a fresh token when the page opens with a token from earlier", async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    await mount({ token: tokenIssuedAt(clock - 3_600_000), workspaceId: "ws1", refresh });
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("does nothing right after login or a workspace switch", async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    await mount({ token: tokenIssuedAt(clock - 5_000), workspaceId: "ws1", refresh });
    expect(refresh).not.toHaveBeenCalled();
  });

  it("refreshes again on focus at most once a minute", async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    await mount({ token: tokenIssuedAt(clock - 3_600_000), workspaceId: "ws1", refresh });
    clock += 10_000;
    await focus();
    expect(refresh).toHaveBeenCalledTimes(1);
    clock += PLAN_REFRESH_INTERVAL_MS;
    await focus();
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("does not ask while the tab is hidden", async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    try {
      await mount({ token: tokenIssuedAt(clock - 3_600_000), workspaceId: "ws1", refresh });
      expect(refresh).not.toHaveBeenCalled();
    } finally {
      visibility.mockRestore();
    }
  });

  it("is off on desktop and when no workspace is chosen", async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    desktop.value = true;
    await mount({ token: tokenIssuedAt(clock - 3_600_000), workspaceId: "ws1", refresh });
    desktop.value = false;
    await mount({ token: tokenIssuedAt(clock - 3_600_000), workspaceId: undefined, refresh });
    expect(refresh).not.toHaveBeenCalled();
  });

  it("a failed refresh keeps the session and is not retried within the minute", async () => {
    const refresh = vi.fn().mockRejectedValue(new Error("offline"));
    await mount({ token: tokenIssuedAt(clock - 3_600_000), workspaceId: "ws1", refresh });
    clock += 30_000;
    await focus();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("reads the age from the token and tolerates tokens it cannot read", () => {
    expect(tokenAgeMs(tokenIssuedAt(clock - 120_000), clock)).toBe(120_000);
    expect(tokenAgeMs("not-a-token", clock)).toBeNull();
  });
});
