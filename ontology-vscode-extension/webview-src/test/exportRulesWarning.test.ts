import { describe, it, expect, vi, beforeEach } from "vitest";

const get = vi.fn();
const notify = vi.fn();
const draftActive = vi.fn(() => false);

vi.mock("../services/apiClient", () => ({ default: { get: (...args: unknown[]) => get(...args) } }));
vi.mock("../services/notificationService", () => ({ notificationService: { notify: (...args: unknown[]) => notify(...args) } }));
vi.mock("../utils/draftScope", () => ({ isDraftScopeActive: () => draftActive() }));
vi.mock("../utils/mutationActor", () => ({ resolveMutationActor: () => ({ userId: "u1" }) }));

import { warnAboutRulesInExport } from "../services/exportRulesWarning";

beforeEach(() => {
  get.mockReset();
  notify.mockReset();
  draftActive.mockReturnValue(false);
});

describe("export rules warning", () => {
  it("shows the server warning for the chosen format", async () => {
    get.mockResolvedValue({ warning: "The OBO format does not support rules, so the rules are not included." });
    await warnAboutRulesInExport("http://gw", "p 1", "obo");
    expect(get).toHaveBeenCalledWith("http://gw/api/ontology/p%201/rules/export-check?format=obo");
    expect(notify).toHaveBeenCalledWith({
      title: "Rules in this export",
      message: "The OBO format does not support rules, so the rules are not included.",
      type: "warning",
      duration: 15000,
    });
  });

  it("shows nothing when the ontology has no rules", async () => {
    get.mockResolvedValue({ warning: null });
    await warnAboutRulesInExport("http://gw", "p", "manchester");
    expect(notify).not.toHaveBeenCalled();
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
    expect(notify).not.toHaveBeenCalled();
  });
});
