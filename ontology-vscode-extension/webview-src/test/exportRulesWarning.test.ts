import { describe, it, expect, vi, beforeEach } from "vitest";

const get = vi.fn();
const warning = vi.fn();
const draftActive = vi.fn(() => false);

vi.mock("../services/apiClient", () => ({ default: { get: (...args: unknown[]) => get(...args) } }));
vi.mock("../services/notificationService", () => ({ notificationService: { warning: (...args: unknown[]) => warning(...args) } }));
vi.mock("../utils/draftScope", () => ({ isDraftScopeActive: () => draftActive() }));
vi.mock("../utils/mutationActor", () => ({ resolveMutationActor: () => ({ userId: "u1" }) }));

import { warnAboutRulesInExport } from "../services/exportRulesWarning";

beforeEach(() => {
  get.mockReset();
  warning.mockReset();
  draftActive.mockReturnValue(false);
});

describe("export rules warning", () => {
  it("shows the server warning for the chosen format", async () => {
    get.mockResolvedValue({ warning: "The OBO format does not support rules, so the rules are not included." });
    await warnAboutRulesInExport("http://gw", "p 1", "obo");
    expect(get).toHaveBeenCalledWith("http://gw/api/ontology/p%201/rules/export-check?format=obo");
    expect(warning).toHaveBeenCalledWith("Rules in this export", "The OBO format does not support rules, so the rules are not included.");
  });

  it("shows nothing when the ontology has no rules", async () => {
    get.mockResolvedValue({ warning: null });
    await warnAboutRulesInExport("http://gw", "p", "manchester");
    expect(warning).not.toHaveBeenCalled();
  });

  it("asks for the private draft when draft editing is active", async () => {
    draftActive.mockReturnValue(true);
    get.mockResolvedValue({});
    await warnAboutRulesInExport("http://gw", "p", "manchester");
    expect(get).toHaveBeenCalledWith("http://gw/api/ontology/p/rules/export-check?format=manchester&draft=true&userId=u1");
  });

  it("never blocks the export when the check fails", async () => {
    get.mockRejectedValue(new Error("down"));
    await expect(warnAboutRulesInExport("http://gw", "p", "manchester")).resolves.toBeUndefined();
    expect(warning).not.toHaveBeenCalled();
  });
});
