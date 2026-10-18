import { afterEach, describe, expect, it, vi } from "vitest";
import { feedbackTypeFromLocation, isDownloadLocation } from "../utils/feedback";
import { findUnsupportedFiles } from "../utils/issueAttachments";
import { submitIssueReport } from "../utils/issueSubmit";

const baseBug = {
  kind: "bug" as const,
  title: "Crash on save",
  description: "It closes",
  stepsToReproduce: "1. Save",
  priority: "High",
  email: null,
  attachments: [] as File[],
};

const mockFetch = (body: unknown) => {
  const fetchMock = vi.fn().mockResolvedValue({ json: () => Promise.resolve(body) });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

const sentForm = (fetchMock: ReturnType<typeof vi.fn>): FormData => fetchMock.mock.calls[0][1].body as FormData;

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("feedback page paths", () => {
  it("recognises the bug and feature paths", () => {
    window.history.replaceState(null, "", "/report-bug");
    expect(feedbackTypeFromLocation()).toBe("bug");
    window.history.replaceState(null, "", "/request-feature");
    expect(feedbackTypeFromLocation()).toBe("feature");
  });

  it("ignores other paths and recognises the download path", () => {
    window.history.replaceState(null, "", "/login");
    expect(feedbackTypeFromLocation()).toBeNull();
    expect(isDownloadLocation()).toBe(false);
    window.history.replaceState(null, "", "/download");
    expect(isDownloadLocation()).toBe(true);
  });
});

describe("findUnsupportedFiles", () => {
  it("accepts logs and ontology files and rejects executables", () => {
    const ok = new File(["x"], "debug.log", { type: "" });
    const owl = new File(["x"], "pizza.owl", { type: "" });
    const exe = new File(["x"], "setup.exe", { type: "application/x-msdownload" });
    expect(findUnsupportedFiles([ok, owl, exe]).map((f) => f.name)).toEqual(["setup.exe"]);
  });
});

describe("submitIssueReport", () => {
  it("sends priority and steps for a bug and the contact email when not signed in", async () => {
    const fetchMock = mockFetch({ success: true });
    const result = await submitIssueReport({ ...baseBug, email: "person@example.com" });
    const form = sentForm(fetchMock);
    expect(result.success).toBe(true);
    expect(result.message).toBe("Bug reported successfully!");
    expect(form.get("issueType")).toBe("Bug");
    expect(form.get("priority")).toBe("High");
    expect(form.get("stepsToReproduce")).toBe("1. Save");
    expect(form.get("userEmail")).toBe("person@example.com");
  });

  it("sends a feature request without priority or steps and without an email header when signed in", async () => {
    const fetchMock = mockFetch({ success: true });
    const result = await submitIssueReport({ ...baseBug, kind: "feature", token: "jwt-token" });
    const form = sentForm(fetchMock);
    expect(result.message).toBe("Feature request submitted successfully!");
    expect(form.get("issueType")).toBe("Task");
    expect(form.get("priority")).toBeNull();
    expect(form.get("stepsToReproduce")).toBeNull();
    expect(form.get("userEmail")).toBeNull();
    expect(fetchMock.mock.calls[0][1].headers).toEqual({ Authorization: "Bearer jwt-token" });
  });

  it("returns the server message when the report is rejected", async () => {
    mockFetch({ success: false, message: "An email address is required so we can reply to you." });
    const result = await submitIssueReport(baseBug);
    expect(result.success).toBe(false);
    expect(result.message).toBe("An email address is required so we can reply to you.");
  });

  it("reports a network error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const result = await submitIssueReport(baseBug);
    expect(result.success).toBe(false);
    expect(result.message).toContain("Network error");
  });
});
