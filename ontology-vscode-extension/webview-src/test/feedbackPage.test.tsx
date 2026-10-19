import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  closeFeedbackPage,
  feedbackRequestFromLocation,
  feedbackRoute,
  openFeedbackPage,
  replaceFeedbackPage,
} from "../utils/feedback";

vi.mock("../custom-hook/useAuth", () => ({
  useAuth: () => ({ user: { token: "jwt", email: "real@x.org" } }),
}));

import FeedbackHost from "../components/FeedbackHost";

const context = { projectName: "gene research", projectId: "p1", ontologyFilePath: "a b/c.owl" };

describe("feedback routes", () => {
  beforeEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("builds a route that carries the project context", () => {
    expect(feedbackRoute("bug", context)).toBe("report-bug?project=gene+research&projectId=p1&file=a+b%2Fc.owl");
    expect(feedbackRoute("feature")).toBe("request-feature");
  });

  it("changes the address when the page is opened and reads the context back", () => {
    openFeedbackPage("bug", context);
    expect(window.location.pathname).toBe("/report-bug");
    expect(feedbackRequestFromLocation()).toEqual({ type: "bug", ...context });
  });

  it("switches kind without adding a history entry", () => {
    openFeedbackPage("bug", context);
    const length = window.history.length;
    replaceFeedbackPage("feature", context);
    expect(window.location.pathname).toBe("/request-feature");
    expect(window.history.length).toBe(length);
  });

  it("returns to the app root when there is nothing to go back to", () => {
    window.history.replaceState(null, "", "/report-bug");
    closeFeedbackPage();
    expect(window.location.pathname).toBe("/");
    expect(feedbackRequestFromLocation()).toBeNull();
  });

  it("ignores other routes", () => {
    window.history.replaceState(null, "", "/somewhere");
    expect(feedbackRequestFromLocation()).toBeNull();
  });
});

describe("FeedbackHost page", () => {
  let root: Root;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState(null, "", "/");
    host = document.createElement("div");
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  const render = async () => {
    await act(async () => {
      root.render(<FeedbackHost />);
    });
  };

  const until = async (check: () => boolean) => {
    for (let attempt = 0; attempt < 100 && !check(); attempt++) {
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 30));
      });
    }
    expect(check()).toBe(true);
  };

  const settle = () => until(() => host.querySelector("header") !== null);

  const button = (label: string) =>
    Array.from(host.querySelectorAll("button")).find((el) => el.textContent?.trim() === label) as HTMLButtonElement;

  it("renders nothing until the route is opened", async () => {
    await render();
    expect(host.textContent).toBe("");
  });

  it("opens as a full page with the project, the form and a way back", async () => {
    await render();
    await act(async () => openFeedbackPage("bug", context));
    await settle();
    expect(host.textContent).toContain("Report a Bug");
    expect(host.textContent).toContain("gene research");
    expect(host.textContent).toContain("Reporting as");
    expect(host.textContent).toContain("real@x.org");
    expect(host.querySelector("header")).not.toBeNull();
    expect(host.querySelector("footer")).not.toBeNull();
    expect(button("Back to app")).toBeTruthy();
    expect(host.querySelector('[aria-label="Back"]')).toBeNull();
  });

  it("switches between the bug and feature forms and updates the address", async () => {
    await render();
    await act(async () => openFeedbackPage("bug", context));
    await settle();
    await act(async () => button("Request a Feature").click());
    await until(() => (host.textContent || "").includes("What problem would this solve?"));
    expect(window.location.pathname).toBe("/request-feature");
    expect(host.textContent).not.toContain("Steps to Reproduce");
  });

  it("closes and returns to the app when Back to app is pressed", async () => {
    await render();
    await act(async () => openFeedbackPage("bug", context));
    await settle();
    await act(async () => button("Back to app").click());
    await until(() => host.textContent === "");
  });

  it("follows the browser back button", async () => {
    await render();
    await act(async () => openFeedbackPage("bug", context));
    await settle();
    expect(host.textContent).toContain("Report a Bug");
    window.history.replaceState(null, "", "/");
    await act(async () => {
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(host.textContent).toBe("");
  });

  it("keeps submit disabled until the title and description are filled in", async () => {
    await render();
    await act(async () => openFeedbackPage("bug", context));
    await settle();
    expect(button("Submit Bug Report").disabled).toBe(true);
  });
});
