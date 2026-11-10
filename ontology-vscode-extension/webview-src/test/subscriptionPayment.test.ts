import { describe, it, expect, vi, afterEach } from "vitest";
import {
  resolveSubscriptionPayment,
  BANK_NOT_CONFIRMED_MESSAGE,
  type StripeLoader,
} from "../utils/subscriptionPayment";

const SECRET = "pi_123_secret_abc";

const loaderWith = (handleNextAction: ReturnType<typeof vi.fn>): StripeLoader =>
  vi.fn(async () => ({ handleNextAction }));

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("resolveSubscriptionPayment", () => {
  it("returns paid without any bank step when the subscription is active", async () => {
    const next = vi.fn();
    const loader = loaderWith(next);
    const fetchStatus = vi.fn();
    const result = await resolveSubscriptionPayment(
      { subscriptionId: "sub_1", status: "active", requiresAction: false },
      "pk_test",
      loader,
      fetchStatus,
    );
    expect(result).toEqual({ kind: "paid" });
    expect(loader).not.toHaveBeenCalled();
    expect(next).not.toHaveBeenCalled();
    expect(fetchStatus).not.toHaveBeenCalled();
  });

  it("returns paid for a trialing subscription", async () => {
    const result = await resolveSubscriptionPayment(
      { status: "trialing", requiresAction: false },
      "pk_test",
      loaderWith(vi.fn()),
      vi.fn(),
    );
    expect(result).toEqual({ kind: "paid" });
  });

  it("confirms with the bank, polls, and returns paid once active", async () => {
    vi.useFakeTimers();
    const next = vi.fn().mockResolvedValue({});
    const statuses = ["incomplete", "incomplete", "active"];
    const fetchStatus = vi.fn(async () => statuses.shift());
    const promise = resolveSubscriptionPayment(
      { status: "incomplete", requiresAction: true, clientSecret: SECRET },
      "pk_test",
      loaderWith(next),
      fetchStatus,
    );
    await vi.advanceTimersByTimeAsync(10000);
    expect(await promise).toEqual({ kind: "paid" });
    expect(next).toHaveBeenCalledWith({ clientSecret: SECRET });
    expect(fetchStatus).toHaveBeenCalledTimes(3);
  });

  it("returns failed with the friendly message when the bank step errors", async () => {
    const next = vi.fn().mockResolvedValue({ error: { message: "raw stripe text" } });
    const fetchStatus = vi.fn();
    const result = await resolveSubscriptionPayment(
      { status: "incomplete", requiresAction: true, clientSecret: SECRET },
      "pk_test",
      loaderWith(next),
      fetchStatus,
    );
    expect(result).toEqual({ kind: "failed", message: BANK_NOT_CONFIRMED_MESSAGE });
    expect(fetchStatus).not.toHaveBeenCalled();
  });

  it("returns failed when the bank step throws", async () => {
    const next = vi.fn().mockRejectedValue(new Error("closed"));
    const result = await resolveSubscriptionPayment(
      { requiresAction: true, clientSecret: SECRET },
      "pk_test",
      loaderWith(next),
      vi.fn(),
    );
    expect(result).toEqual({ kind: "failed", message: BANK_NOT_CONFIRMED_MESSAGE });
  });

  it("returns pending when the plan is still not active after the wait", async () => {
    vi.useFakeTimers();
    const fetchStatus = vi.fn(async () => "incomplete");
    const promise = resolveSubscriptionPayment(
      { requiresAction: true, clientSecret: SECRET },
      "pk_test",
      loaderWith(vi.fn().mockResolvedValue({})),
      fetchStatus,
    );
    await vi.advanceTimersByTimeAsync(30000);
    expect(await promise).toEqual({ kind: "pending" });
    expect(fetchStatus).toHaveBeenCalledTimes(10);
  });

  it("keeps polling through a failed status read", async () => {
    vi.useFakeTimers();
    const fetchStatus = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValue("active");
    const promise = resolveSubscriptionPayment(
      { requiresAction: true, clientSecret: SECRET },
      "pk_test",
      loaderWith(vi.fn().mockResolvedValue({})),
      fetchStatus,
    );
    await vi.advanceTimersByTimeAsync(10000);
    expect(await promise).toEqual({ kind: "paid" });
  });

  it("fails when the server asks for action but sends no client secret", async () => {
    const result = await resolveSubscriptionPayment(
      { requiresAction: true },
      "pk_test",
      loaderWith(vi.fn()),
      vi.fn(),
    );
    expect(result.kind).toBe("failed");
  });

  it("never writes the client secret to the console", async () => {
    vi.useFakeTimers();
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((m) =>
      vi.spyOn(console, m).mockImplementation(() => {}),
    );
    const failing = resolveSubscriptionPayment(
      { requiresAction: true, clientSecret: SECRET },
      "pk_test",
      loaderWith(vi.fn().mockResolvedValue({ error: { message: "x" } })),
      vi.fn(),
    );
    const pending = resolveSubscriptionPayment(
      { requiresAction: true, clientSecret: SECRET },
      "pk_test",
      loaderWith(vi.fn().mockResolvedValue({})),
      vi.fn(async () => "incomplete"),
    );
    await vi.advanceTimersByTimeAsync(30000);
    await failing;
    await pending;
    for (const spy of spies) {
      expect(JSON.stringify(spy.mock.calls)).not.toContain(SECRET);
    }
  });
});
