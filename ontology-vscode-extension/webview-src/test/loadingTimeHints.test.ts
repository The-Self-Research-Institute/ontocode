import { describe, expect, it } from "vitest";
import { fileImportEtaHint, loadingTimeHint } from "../utils/fileSizeHints";

describe("loading time hints", () => {
  it("keeps the server wording for the web app", () => {
    expect(loadingTimeHint(false)).toBe("Small files: ~1 min · Large (100MB+): 15–40 min");
    expect(fileImportEtaHint(200 * 1024 * 1024)).toContain("15–40 minutes");
    expect(fileImportEtaHint(20 * 1024 * 1024, false)).toBe("Typically 1–5 minutes");
  });

  it("gives desktop wording that matches what the desktop app really takes", () => {
    expect(loadingTimeHint(true)).toContain("2–4 minutes");
    expect(loadingTimeHint(true)).not.toContain("15–40");
    expect(fileImportEtaHint(2 * 1024 * 1024, true)).toBe("Usually a few seconds");
    expect(fileImportEtaHint(30 * 1024 * 1024, true)).toBe("Usually under a minute");
    expect(fileImportEtaHint(100 * 1024 * 1024, true)).toBe("Typically 1–2 minutes");
    expect(fileImportEtaHint(240 * 1024 * 1024, true)).toContain("2–4 minutes");
  });
});
