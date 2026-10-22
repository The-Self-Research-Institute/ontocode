import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  JobCancelledError,
  pollJobUntilDone,
} from "../../../plugins/reasoner-plugin/src/reasoningJobPolling";
import {
  attachReasoningSocket,
  installReasoningJobWindowBridge,
  resetReasoningTopicState,
} from "../services/reasoningJobTopic";

let removeBridge: () => void = () => undefined;

beforeEach(() => {
  vi.useFakeTimers();
  resetReasoningTopicState();
});

afterEach(() => {
  removeBridge();
  removeBridge = () => undefined;
  vi.useRealTimers();
});

describe("reasoner plugin job polling", () => {
  it("polls every 2.5 s when the host has no socket bridge", async () => {
    const check = vi.fn().mockResolvedValueOnce(undefined).mockResolvedValueOnce("done");
    const pending = pollJobUntilDone("j1", check, { timeoutMs: 60_000, timeoutMessage: "timed out" });
    await vi.advanceTimersByTimeAsync(2_499);
    expect(check).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(check).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2_500);
    await expect(pending).resolves.toBe("done");
  });

  it("checks right away when the host socket reports the job finished", async () => {
    removeBridge = installReasoningJobWindowBridge(window);
    const open = new Map<string, (payload: any) => void>();
    attachReasoningSocket((jobId, onMessage) => {
      open.set(jobId, onMessage);
      return () => open.delete(jobId);
    });
    const check = vi.fn().mockResolvedValue("done");
    const pending = pollJobUntilDone("j2", check, { timeoutMs: 60_000, timeoutMessage: "timed out" });
    await vi.advanceTimersByTimeAsync(5_000);
    expect(check).not.toHaveBeenCalled();
    open.get("j2")?.({ jobId: "j2", status: "COMPLETED" });
    await vi.advanceTimersByTimeAsync(0);
    await expect(pending).resolves.toBe("done");
    expect(check).toHaveBeenCalledTimes(1);
    expect(open.has("j2")).toBe(false);
  });

  it("stops when the run is cancelled", async () => {
    const controller = new AbortController();
    const check = vi.fn().mockResolvedValue(undefined);
    const pending = pollJobUntilDone("j3", check, {
      timeoutMs: 60_000,
      timeoutMessage: "timed out",
      signal: controller.signal,
    });
    const assertion = expect(pending).rejects.toBeInstanceOf(JobCancelledError);
    await vi.advanceTimersByTimeAsync(1_000);
    controller.abort();
    await assertion;
    expect(check).not.toHaveBeenCalled();
  });
});
