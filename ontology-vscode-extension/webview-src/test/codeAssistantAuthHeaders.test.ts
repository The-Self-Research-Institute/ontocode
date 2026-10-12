import { afterEach, describe, expect, it } from "vitest";
import { assistantAuthHeaders, DESKTOP_LAUNCH_KEY_HEADER } from "../services/codeAssistantAuthHeaders";

type LaunchKeyWindow = { __DESKTOP_LAUNCH_KEY__?: unknown };

describe("assistantAuthHeaders", () => {
  afterEach(() => {
    delete (window as LaunchKeyWindow).__DESKTOP_LAUNCH_KEY__;
  });

  it("sends the sign-in token when there is one", () => {
    (window as LaunchKeyWindow).__DESKTOP_LAUNCH_KEY__ = "launch-key";
    expect(assistantAuthHeaders("jwt-123")).toEqual({ Authorization: "Bearer jwt-123" });
  });

  it("falls back to the desktop launch key when there is no token", () => {
    (window as LaunchKeyWindow).__DESKTOP_LAUNCH_KEY__ = "launch-key";
    expect(assistantAuthHeaders(undefined)).toEqual({ [DESKTOP_LAUNCH_KEY_HEADER]: "launch-key" });
    expect(assistantAuthHeaders("")).toEqual({ [DESKTOP_LAUNCH_KEY_HEADER]: "launch-key" });
  });

  it("sends nothing on the web, where there is no launch key", () => {
    expect(assistantAuthHeaders(undefined)).toEqual({});
  });
});
