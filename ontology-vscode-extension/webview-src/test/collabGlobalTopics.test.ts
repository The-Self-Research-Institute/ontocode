import { describe, it, expect, vi } from "vitest";
import { subscribeGlobalTopics } from "../utils/collabGlobalTopics";

describe("subscribeGlobalTopics", () => {
  it("listens on the private share queue and never on a per-email topic", () => {
    const destinations: string[] = [];
    const client: any = {
      subscribe: vi.fn((destination: string) => {
        destinations.push(destination);
        return { unsubscribe: () => {} };
      }),
    };
    subscribeGlobalTopics(client, new Map(), { workspaceId: "w1", email: "someone@example.org" } as any, {
      shareIsNew: () => true,
      forbiddenDest: () => null,
    });
    expect(destinations).toContain("/user/queue/shares");
    expect(destinations.some((d) => d.startsWith("/topic/shares/"))).toBe(false);
    expect(destinations).toContain("/topic/workspace/w1");
  });
});
