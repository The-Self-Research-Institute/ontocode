import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { autoSyncSettleDelay, createAutoSyncSettleTimer } from "../services/reasonerAutoSync";

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(0);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("auto-sync settle timing", () => {
  it("waits 2 s after a lone edit", async () => {
    const onSettle = vi.fn();
    const timer = createAutoSyncSettleTimer(onSettle);
    expect(timer.noteEdit()).toBe(2_000);
    await vi.advanceTimersByTimeAsync(1_999);
    expect(onSettle).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(onSettle).toHaveBeenCalledTimes(1);
  });

  it("waits 5 s when another edit came in the previous 10 s", async () => {
    const onSettle = vi.fn();
    const timer = createAutoSyncSettleTimer(onSettle);
    timer.noteEdit();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(timer.noteEdit()).toBe(5_000);
    await vi.advanceTimersByTimeAsync(4_999);
    expect(onSettle).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(onSettle).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(3_000);
    expect(timer.noteEdit()).toBe(5_000);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(onSettle).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(11_000);
    expect(timer.noteEdit()).toBe(2_000);
  });

  it("never runs later than 20 s after the first unprocessed edit", async () => {
    const onSettle = vi.fn();
    const timer = createAutoSyncSettleTimer(onSettle);
    timer.noteEdit();
    await vi.advanceTimersByTimeAsync(1_500);
    for (let i = 0; i < 4; i += 1) {
      expect(timer.noteEdit()).toBe(5_000);
      await vi.advanceTimersByTimeAsync(4_000);
    }
    expect(onSettle).not.toHaveBeenCalled();
    expect(timer.noteEdit()).toBe(2_500);
    await vi.advanceTimersByTimeAsync(2_499);
    expect(onSettle).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(onSettle).toHaveBeenCalledTimes(1);
    expect(Date.now()).toBe(20_000);
  });

  it("does nothing after it is cancelled", async () => {
    const onSettle = vi.fn();
    const timer = createAutoSyncSettleTimer(onSettle);
    timer.noteEdit();
    expect(timer.isPending()).toBe(true);
    timer.cancel();
    expect(timer.isPending()).toBe(false);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(onSettle).not.toHaveBeenCalled();
    expect(timer.noteEdit()).toBe(2_000);
  });

  it("computes the delay from the edit history", () => {
    expect(autoSyncSettleDelay(100_000, null, null)).toBe(2_000);
    expect(autoSyncSettleDelay(100_000, 95_000, 95_000)).toBe(5_000);
    expect(autoSyncSettleDelay(100_000, 89_000, 100_000)).toBe(2_000);
    expect(autoSyncSettleDelay(100_000, 99_000, 82_000)).toBe(2_000);
    expect(autoSyncSettleDelay(100_000, 99_000, 70_000)).toBe(0);
  });
});
