import { describe, it, expect } from "vitest";
import { normalizeKnownSlips } from "../services/codeAssistantLoopDispatch";

describe("normalizeKnownSlips", () => {
  it("treats a target type put in read_context's kind field as a plain definitions read", () => {
    for (const slip of ["range", "statement", "identifier"]) {
      const args = { targets: [{ type: "range", value: "turtle:0-60" }], kind: slip };

      expect(normalizeKnownSlips("read_context", args)).toEqual({ targets: args.targets, kind: "definitions" });
    }
  });

  it("leaves valid kinds alone", () => {
    for (const kind of ["definitions", "diagnostics", "references", "guidance"]) {
      const args = { targets: [], kind };

      expect(normalizeKnownSlips("read_context", args)).toBe(args);
    }
  });

  it("does not touch other tools or a missing kind", () => {
    const other = { kind: "range" };
    expect(normalizeKnownSlips("propose_edit", other)).toBe(other);
    const noKind = { targets: [] };
    expect(normalizeKnownSlips("read_context", noKind)).toBe(noKind);
  });

  it("does not change the caller's object", () => {
    const args = { targets: [], kind: "range" };

    normalizeKnownSlips("read_context", args);

    expect(args.kind).toBe("range");
  });
});
