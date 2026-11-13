import { describe, expect, it } from "vitest";
import {
  EMPTY_SHARE_SELECTION,
  extractCreateProjectError,
  filterVisibleMembers,
  prepareShareableMembers,
  privilegedBadgeLabel,
  shareSelectionForVisibility,
  type WorkspaceMember,
} from "../utils/projectShare";

const m = (over: Partial<WorkspaceMember>): WorkspaceMember => ({
  userId: "u",
  username: "name",
  email: "a@x.com",
  ...over,
});

describe("prepareShareableMembers", () => {
  it("drops inactive, incomplete and current-user members, trims, defaults username and sorts", () => {
    const result = prepareShareableMembers(
      [
        m({ userId: "1", username: "zed", email: " zed@x.com " }),
        m({ userId: "2", username: "", email: "amy@x.com" }),
        m({ userId: "3", username: "gone", email: "gone@x.com", status: "REMOVED" }),
        m({ userId: "me", username: "me", email: "me@x.com" }),
        m({ userId: "4", username: "dup", email: "ME@x.com" }),
        m({ userId: "", username: "noid", email: "n@x.com" }),
      ],
      "me",
      "Me@x.com",
    );
    expect(result.map((r) => [r.username, r.email])).toEqual([
      ["amy", "amy@x.com"],
      ["zed", "zed@x.com"],
    ]);
  });
});

describe("filterVisibleMembers", () => {
  const members = [
    m({ userId: "1", username: "bob", email: "bob@x.com" }),
    m({ userId: "2", username: "adm", email: "adm@x.com", role: "ADMIN" }),
    m({ userId: "3", username: "own", email: "own@y.com", role: "owner" }),
  ];

  it("puts owners and admins first", () => {
    expect(filterVisibleMembers(members, "").map((x) => x.userId)).toEqual(["2", "3", "1"]);
  });

  it("matches name or email case-insensitively", () => {
    expect(filterVisibleMembers(members, " BOB ").map((x) => x.userId)).toEqual(["1"]);
    expect(filterVisibleMembers(members, "y.com").map((x) => x.userId)).toEqual(["3"]);
    expect(filterVisibleMembers(members, "zzz")).toEqual([]);
  });
});

describe("privilegedBadgeLabel", () => {
  it("labels owners Owner and everyone else Editor", () => {
    expect(privilegedBadgeLabel(m({ role: "OWNER" }))).toBe("Owner");
    expect(privilegedBadgeLabel(m({ role: "owner" }))).toBe("Owner");
    expect(privilegedBadgeLabel(m({ role: "ADMIN" }))).toBe("Editor");
    expect(privilegedBadgeLabel(m({}))).toBe("Editor");
  });
});

describe("shareSelectionForVisibility", () => {
  it("maps visibility to a share selection", () => {
    expect(shareSelectionForVisibility("WORKSPACE").shareWith).toBe("all");
    expect(shareSelectionForVisibility("SPECIFIC").shareWith).toBe("specific");
    expect(shareSelectionForVisibility("PRIVATE").shareWith).toBe("none");
    expect(shareSelectionForVisibility(undefined)).toEqual(EMPTY_SHARE_SELECTION);
  });

  it("shows specific members when a project saved as private or unset already has other members", () => {
    expect(shareSelectionForVisibility("PRIVATE", true).shareWith).toBe("specific");
    expect(shareSelectionForVisibility(undefined, true).shareWith).toBe("specific");
  });

  it("keeps only me for a project with no other members", () => {
    expect(shareSelectionForVisibility("PRIVATE", false)).toEqual(EMPTY_SHARE_SELECTION);
    expect(shareSelectionForVisibility(undefined, false)).toEqual(EMPTY_SHARE_SELECTION);
  });

  it("lets the workspace-wide setting win over the member count", () => {
    expect(shareSelectionForVisibility("WORKSPACE", true).shareWith).toBe("all");
  });
});

describe("prepareShareableMembers exclusions", () => {
  it("keeps only active, non-excluded members sorted by name", () => {
    const out = prepareShareableMembers(
      [
        m({ userId: "1", username: "zed", email: "z@x.io" }),
        m({ userId: "2", username: "", email: "amy@x.io" }),
        m({ userId: "3", username: "gone", email: "g@x.io", status: "PENDING" }),
        m({ userId: "4", username: "skip", email: "S@x.io" }),
      ],
      null,
      null,
      new Set(["s@x.io"]),
    );
    expect(out.map((x) => x.username)).toEqual(["amy", "zed"]);
  });
});

describe("extractCreateProjectError", () => {
  it("prefers data.error, then data.message, then response, then message, then default", () => {
    expect(extractCreateProjectError({ data: { error: "e", message: "m" } })).toBe("e");
    expect(extractCreateProjectError({ data: { message: "m" } })).toBe("m");
    expect(extractCreateProjectError({ response: { data: { error: "re" } } })).toBe("re");
    expect(extractCreateProjectError({ response: { data: { message: "rm" } } })).toBe("rm");
    expect(extractCreateProjectError({ message: "plain" })).toBe("plain");
    expect(extractCreateProjectError({})).toBe("Failed to create project. Please try again.");
  });
});
