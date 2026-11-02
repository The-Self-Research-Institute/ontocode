import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { notificationService } from "../services/notificationService";

describe("notification service toasts on the web", () => {
  const original = (globalThis as any).Notification;

  beforeEach(() => {
    (globalThis as any).Notification = { permission: "default", requestPermission: vi.fn() };
  });

  afterEach(() => {
    (globalThis as any).Notification = original;
  });

  it("hands warnings to the toast listener with their duration", () => {
    const seen = vi.fn();
    const off = notificationService.onToast(seen);
    notificationService.notify({ title: "Rules in this export", message: "No rules", type: "warning", duration: 15000 });
    off();
    expect(seen).toHaveBeenCalledWith(expect.objectContaining({ type: "warning", duration: 15000, title: "Rules in this export" }));
  });

  it("stops calling a listener once it unsubscribes", () => {
    const seen = vi.fn();
    const off = notificationService.onToast(seen);
    off();
    notificationService.warning("t", "m");
    expect(seen).not.toHaveBeenCalled();
  });
});
