import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

vi.mock("../services/apiClient", () => ({
  default: { get: vi.fn(), delete: vi.fn() },
}));

import apiClient from "../services/apiClient";
import {
  attachReasoningSocket,
  detachReasoningSocket,
  installReasoningJobWindowBridge,
  isReasoningPushAvailable,
  markReasoningDestinationForbidden,
  resetReasoningTopicState,
  subscribeReasoningJob,
} from "../services/reasoningJobTopic";
import { ReasoningJobCancelledError, waitForReasoningJob } from "../services/reasoningJobWait";
import { subscribeGlobalTopics } from "../utils/collabGlobalTopics";
import { createForbiddenFilter } from "../utils/collabSocketPolicy";

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

const fakeSocket = () => {
  const open = new Map<string, (payload: any) => void>();
  const subscriber = vi.fn((jobId: string, onMessage: (payload: any) => void) => {
    open.set(jobId, onMessage);
    return () => open.delete(jobId);
  });
  return { open, subscriber };
};

beforeEach(() => {
  resetReasoningTopicState();
  get.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("reasoning job topic", () => {
  it("subscribes waiting jobs once the socket connects and drops them after", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeReasoningJob("j1", listener);
    expect(isReasoningPushAvailable("j1")).toBe(false);

    const socket = fakeSocket();
    attachReasoningSocket(socket.subscriber);
    expect(socket.subscriber).toHaveBeenCalledWith("j1", expect.any(Function));
    expect(isReasoningPushAvailable("j1")).toBe(true);

    socket.open.get("j1")?.({ jobId: "j1", status: "PROCESSING" });
    expect(listener).toHaveBeenCalledWith({ jobId: "j1", status: "PROCESSING" });

    unsubscribe();
    expect(socket.open.has("j1")).toBe(false);
    detachReasoningSocket();
    expect(isReasoningPushAvailable("j1")).toBe(false);
  });

  it("falls back to fast polling when the subscription is forbidden", async () => {
    vi.useFakeTimers();
    const socket = fakeSocket();
    attachReasoningSocket(socket.subscriber);
    get.mockResolvedValueOnce({ status: "PROCESSING" }).mockResolvedValueOnce({ status: "COMPLETED", ok: 1 });

    const pending = waitForReasoningJob("p1", "j2");
    await vi.advanceTimersByTimeAsync(0);
    expect(isReasoningPushAvailable("j2")).toBe(true);
    expect(markReasoningDestinationForbidden("/topic/ontology/p1")).toBe(false);
    expect(markReasoningDestinationForbidden("/topic/reasoning/j2")).toBe(true);
    expect(isReasoningPushAvailable("j2")).toBe(false);

    await vi.advanceTimersByTimeAsync(2_500);
    expect(get).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2_500);
    await expect(pending).resolves.toMatchObject({ status: "COMPLETED" });
    expect(get).toHaveBeenCalledTimes(2);
  });

  it("stops waiting as soon as the caller aborts", async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const pending = waitForReasoningJob("p1", "j3", { signal: controller.signal });
    const assertion = expect(pending).rejects.toBeInstanceOf(ReasoningJobCancelledError);
    await vi.advanceTimersByTimeAsync(100);
    controller.abort();
    await assertion;
    expect(get).not.toHaveBeenCalled();
  });

  it("gives plugins a window bridge to the same subscriptions", () => {
    const target: any = {};
    const remove = installReasoningJobWindowBridge(target);
    const socket = fakeSocket();
    attachReasoningSocket(socket.subscriber);
    const listener = vi.fn();
    const unsubscribe = target.ontocodeReasoningJobs.subscribe("j4", listener);
    expect(target.ontocodeReasoningJobs.isPushAvailable("j4")).toBe(true);
    socket.open.get("j4")?.({ status: "COMPLETED" });
    expect(listener).toHaveBeenCalledWith({ status: "COMPLETED" });
    unsubscribe();
    remove();
    expect(target.ontocodeReasoningJobs).toBeUndefined();
  });

  it("reports forbidden reasoning subscriptions from the socket error queue", () => {
    const handlers = new Map<string, (msg: { body: string }) => void>();
    const client: any = {
      subscribe: (dest: string, cb: (msg: { body: string }) => void) => {
        handlers.set(dest, cb);
        return { unsubscribe: () => undefined };
      },
    };
    const onForbidden = vi.fn();
    subscribeGlobalTopics(client, new Map(), null, {
      shareIsNew: () => true,
      forbiddenDest: createForbiddenFilter(),
      onForbidden,
    });
    handlers.get("/user/queue/errors")?.({ body: JSON.stringify({ code: "WS_FORBIDDEN", destination: "/topic/reasoning/j5" }) });
    expect(onForbidden).toHaveBeenCalledWith("/topic/reasoning/j5");
  });
});
