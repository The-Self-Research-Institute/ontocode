import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import DraftCopyModal from "../components/dialogs/DraftCopyModal";

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

function show(failureReason?: string) {
  act(() => root.render(<DraftCopyModal phase="failed" failureReason={failureReason} onCancel={() => {}} />));
}

describe("DraftCopyModal failure text", () => {
  it("shows the server reason when there is one", () => {
    show("You have view-only access to this project.");
    expect(host.textContent).toContain("Draft Copy Failed");
    expect(host.textContent).toContain("You have view-only access to this project.");
    expect(host.textContent).not.toContain("Something went wrong");
  });

  it("keeps the generic text when there is no reason", () => {
    show(undefined);
    expect(host.textContent).toContain("Something went wrong while creating your draft.");
    expect(host.textContent).toContain("Please try again.");
  });
});
