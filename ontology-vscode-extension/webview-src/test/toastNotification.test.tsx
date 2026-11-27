import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ToastNotification, { type Toast } from "../components/ToastNotification";
import { notificationService, readingTimeMs } from "../services/notificationService";

const FIVE_LINES = "The export finished, but 12 SWRL rules could not be written because they use built-ins "
  + "that this format cannot express. They are listed in the export report. Rules that use only standard "
  + "atoms were written normally. Open the report to see which rules were skipped and why, then either "
  + "simplify them or export to a format that supports custom built-ins, such as the OntoCode project file.";

describe("ToastNotification", () => {
  let root: Root;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
    vi.useFakeTimers();
    host = document.createElement("div");
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.useRealTimers();
  });

  const show = (toasts: Toast[], onDismiss: (id: string) => void) => act(() => {
    root.render(<ToastNotification toasts={toasts} onDismiss={onDismiss} />);
  });

  it("stacks several notifications in one list instead of drawing them on top of each other", () => {
    show([
      { id: "a", type: "info", message: "First" },
      { id: "b", type: "warning", message: "Second" },
      { id: "c", type: "error", message: "Third" },
    ], vi.fn());

    expect(host.querySelectorAll(".toast-container")).toHaveLength(1);
    expect(host.querySelectorAll(".toast")).toHaveLength(3);
  });

  it("keeps a five-line warning up long enough to read it", () => {
    const dismissed = vi.fn();
    show([{ id: "long", type: "warning", message: FIVE_LINES }], dismissed);

    act(() => { vi.advanceTimersByTime(6_000); });
    expect(dismissed).not.toHaveBeenCalled();
    act(() => { vi.advanceTimersByTime(readingTimeMs(FIVE_LINES, "warning")); });
    expect(dismissed).toHaveBeenCalledWith("long");
  });

  it("does not restart or cut short a timer when the screen updates", () => {
    const dismissed = vi.fn();
    const first: Toast = { id: "a", type: "info", message: "First", duration: 5_000 };
    show([first], dismissed);
    act(() => { vi.advanceTimersByTime(3_000); });
    show([first, { id: "b", type: "info", message: "Second", duration: 5_000 }], dismissed);
    act(() => { vi.advanceTimersByTime(2_000); });

    expect(dismissed).toHaveBeenCalledTimes(1);
    expect(dismissed).toHaveBeenCalledWith("a");
  });
});

describe("how long a notification stays up", () => {
  it("gives short messages the usual time and long ones more, up to half a minute", () => {
    expect(readingTimeMs("Saved", "success")).toBe(5_000);
    expect(readingTimeMs("Saved", "warning")).toBe(7_000);
    expect(readingTimeMs("Saved", "error")).toBe(9_000);
    expect(readingTimeMs(FIVE_LINES, "warning")).toBeGreaterThan(20_000);
    expect(readingTimeMs(FIVE_LINES.repeat(5), "error")).toBe(30_000);
  });

  it("uses the reading time for a long warning even though the shortcut has no duration of its own", () => {
    const seen = vi.fn();
    const stop = notificationService.onToast(seen);
    try {
      notificationService.warning("Export", FIVE_LINES);
    } finally {
      stop();
    }
    expect(seen.mock.calls[0][0].duration).toBeGreaterThan(20_000);
  });
});
