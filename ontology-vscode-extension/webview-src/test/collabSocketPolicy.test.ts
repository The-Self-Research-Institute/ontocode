import { describe, expect, it } from "vitest";
import {
  backoffDelay,
  capNotifications,
  collaborationAllowed,
  createForbiddenFilter,
  createShareDeduper,
  stompErrorAction,
  summarizeRemoteOps,
} from "../utils/collabSocketPolicy";
import * as ext from "../../src/collaboration/socketPolicy";

describe.each([
  ["webview", { backoffDelay, stompErrorAction, createShareDeduper }],
  ["extension", ext],
])("%s socket policy", (_name, policy) => {
  it("grows exponentially and stays within the jitter window", () => {
    expect(policy.backoffDelay(0, () => 0)).toBe(500);
    expect(policy.backoffDelay(0, () => 1)).toBe(1000);
    expect(policy.backoffDelay(3, () => 0)).toBe(4000);
    expect(policy.backoffDelay(3, () => 1)).toBe(8000);
  });

  it("caps at 30s", () => {
    expect(policy.backoffDelay(20, () => 1)).toBe(30_000);
    expect(policy.backoffDelay(20, () => 0)).toBe(15_000);
    expect(policy.backoffDelay(-4, () => 1)).toBe(1000);
  });

  it("refreshes once on expired or invalid tokens, then stops", () => {
    expect(policy.stompErrorAction("AUTH_EXPIRED", false)).toBe("refresh");
    expect(policy.stompErrorAction("AUTH_INVALID", false)).toBe("refresh");
    expect(policy.stompErrorAction("AUTH_EXPIRED", true)).toBe("stop");
    expect(policy.stompErrorAction("AUTH_INVALID", true)).toBe("stop");
  });

  it("stops on missing auth and retries anything else", () => {
    expect(policy.stompErrorAction("AUTH_MISSING", false)).toBe("stop");
    expect(policy.stompErrorAction("Broker unavailable", false)).toBe("retry");
    expect(policy.stompErrorAction(undefined, true)).toBe("retry");
  });

  it("drops a share seen on both topics", () => {
    const isNew = policy.createShareDeduper();
    const share = { projectId: "p1", sharedWithEmail: "a@b.c", permission: "READ", timestamp: 42 };
    expect(isNew(share)).toBe(true);
    expect(isNew({ ...share })).toBe(false);
    expect(isNew({ ...share, timestamp: 43 })).toBe(true);
    expect(isNew({ ...share, permission: "WRITE" })).toBe(true);
  });

  it("forgets old shares past the limit", () => {
    const isNew = policy.createShareDeduper(2);
    isNew({ projectId: "a" });
    isNew({ projectId: "b" });
    isNew({ projectId: "c" });
    expect(isNew({ projectId: "a" })).toBe(true);
    expect(isNew({ projectId: "c" })).toBe(false);
  });
});

describe("forbidden filter", () => {
  it("reports each destination once and ignores other codes", () => {
    const next = createForbiddenFilter();
    expect(next({ code: "WS_FORBIDDEN", destination: "/topic/ontology/x" })).toBe("/topic/ontology/x");
    expect(next({ code: "WS_FORBIDDEN", destination: "/topic/ontology/x" })).toBeNull();
    expect(next({ code: "WS_FORBIDDEN", destination: "/topic/locks/x" })).toBe("/topic/locks/x");
    expect(next({ code: "OTHER", destination: "/topic/y" })).toBeNull();
    expect(next(null)).toBeNull();
  });
});

describe("remote op coalescing", () => {
  it("summarizes a burst into one toast per user", () => {
    const ops = [
      ...Array.from({ length: 40 }, (_, i) => ({ userId: "u1", username: "Ann", type: "CLASS_ADDED", timestamp: i })),
      { userId: "u2", username: "Bo", type: "CLASS_DELETED", timestamp: 99 },
    ];
    const out = summarizeRemoteOps(ops);
    expect(out).toHaveLength(2);
    expect(out[0]).toMatchObject({ userId: "u1", username: "Ann", message: "40 changes by Ann", timestamp: 39 });
    expect(out[1].userId).toBe("u2");
    expect(out[1].message.startsWith("Bo ")).toBe(true);
  });

  it("keeps the single-op wording and falls back to Someone", () => {
    const [one] = summarizeRemoteOps([{ userId: "u3", type: "CLASS_ADDED", timestamp: 5 }]);
    expect(one.message.startsWith("Someone ")).toBe(true);
    expect(one.message).not.toMatch(/changes by/);
    expect(summarizeRemoteOps([])).toEqual([]);
  });

  it("caps visible notifications to the newest ones", () => {
    const list = [1, 2, 3, 4, 5, 6, 7];
    expect(capNotifications(list)).toEqual([3, 4, 5, 6, 7]);
    expect(capNotifications([1, 2], 5)).toEqual([1, 2]);
  });
});

describe("desktop gate", () => {
  it("only connects on desktop when the license enables collaboration", () => {
    expect(collaborationAllowed(false, null)).toBe(true);
    expect(collaborationAllowed(true, null)).toBe(false);
    expect(collaborationAllowed(true, { features: { collaboration: false } })).toBe(false);
    expect(collaborationAllowed(true, { features: { collaboration: true } })).toBe(true);
  });
});
