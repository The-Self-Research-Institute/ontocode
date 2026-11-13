import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SyncModeControls from "../components/topbar/SyncModeControls";
import { resolveSyncModeView } from "../utils/syncModeControls";
import { serverReason } from "../utils/serverReason";

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

type Props = React.ComponentProps<typeof SyncModeControls>;

function mount(props: Partial<Props>) {
  const onToggleSyncMode = vi.fn();
  const onSwitchToDraftMode = vi.fn();
  const onToggleRequireDraftForMembers = vi.fn();
  act(() =>
    root.render(
      <SyncModeControls
        syncMode="public"
        onToggleSyncMode={onToggleSyncMode}
        onSwitchToDraftMode={onSwitchToDraftMode}
        onToggleRequireDraftForMembers={onToggleRequireDraftForMembers}
        {...props}
      />,
    ),
  );
  return { onToggleSyncMode, onSwitchToDraftMode, onToggleRequireDraftForMembers };
}

const text = () => host.textContent || "";
const buttons = () => Array.from(host.querySelectorAll("button"));
const titles = () => buttons().map((b) => b.getAttribute("title"));

describe("resolveSyncModeView", () => {
  it("shows view only with no control for a viewer in either mode", () => {
    expect(resolveSyncModeView({ syncMode: "public", isViewerRole: true })).toEqual({ label: "View only", tone: "gray", control: "none" });
    expect(resolveSyncModeView({ syncMode: "private", isViewerRole: true }).control).toBe("none");
  });

  it("lets the viewer flag win over requireDraftForMembers and draft editor", () => {
    const v = resolveSyncModeView({ syncMode: "public", isViewerRole: true, requireDraftForMembers: true, isDraftEditorRole: true });
    expect(v.label).toBe("View only");
    expect(v.control).toBe("none");
  });

  it("gives an owner and an editor the live toggle", () => {
    expect(resolveSyncModeView({ syncMode: "public", isProjectOwner: true })).toEqual({ label: "Public (Live)", tone: "green", control: "liveToggle" });
    expect(resolveSyncModeView({ syncMode: "private" })).toEqual({ label: "Private (Draft)", tone: "gray", control: "liveToggle" });
  });

  it("keeps the owner on the live toggle when drafts are required for members", () => {
    expect(resolveSyncModeView({ syncMode: "public", isProjectOwner: true, requireDraftForMembers: true }).control).toBe("liveToggle");
  });

  it("gives a draft editor view only plus a switch button in public mode", () => {
    expect(resolveSyncModeView({ syncMode: "public", isDraftEditorRole: true })).toEqual({ label: "Public (View Only)", tone: "amber", control: "switchToDraft" });
  });

  it("gives a draft editor a way back to public in private mode", () => {
    expect(resolveSyncModeView({ syncMode: "private", isDraftEditorRole: true })).toEqual({ label: "Draft Mode", tone: "gray", control: "backToPublic" });
  });

  it("treats a non-owner as a draft editor when drafts are required", () => {
    expect(resolveSyncModeView({ syncMode: "public", requireDraftForMembers: true }).control).toBe("switchToDraft");
  });
});

describe("SyncModeControls", () => {
  it("renders View only and no toggle and no switch button for a viewer", () => {
    mount({ isViewerRole: true });
    expect(text()).toContain("View only");
    expect(text()).not.toContain("Public (Live)");
    expect(text()).not.toContain("Switch to Draft Mode");
    expect(buttons()).toHaveLength(0);
  });

  it("renders no controls for a viewer in private mode either", () => {
    mount({ isViewerRole: true, syncMode: "private" });
    expect(buttons()).toHaveLength(0);
  });

  it("renders the live toggle for an owner and calls it", () => {
    const { onToggleSyncMode } = mount({ isProjectOwner: true });
    expect(text()).toContain("Public (Live)");
    expect(titles()).toEqual(["Switch to Draft Mode"]);
    act(() => buttons()[0].click());
    expect(onToggleSyncMode).toHaveBeenCalledTimes(1);
  });

  it("renders the live toggle for an editor or admin", () => {
    mount({});
    expect(text()).toContain("Public (Live)");
    expect(buttons()).toHaveLength(1);
  });

  it("renders Public (View Only) and the Switch to Draft Mode button for a draft editor", () => {
    const { onSwitchToDraftMode } = mount({ isDraftEditorRole: true });
    expect(text()).toContain("Public (View Only)");
    expect(text()).not.toContain("Public (Live)");
    const btn = buttons().find((b) => b.textContent === "Switch to Draft Mode");
    expect(btn).toBeTruthy();
    act(() => btn!.click());
    expect(onSwitchToDraftMode).toHaveBeenCalledTimes(1);
  });

  it("renders Draft Mode with a back-to-public toggle for a draft editor in private mode", () => {
    mount({ isDraftEditorRole: true, syncMode: "private" });
    expect(text()).toContain("Draft Mode");
    expect(titles()).toEqual(["Switch back to Public view-only mode"]);
  });

  it("renders the draft-only view for a non-owner when drafts are required", () => {
    mount({ requireDraftForMembers: true });
    expect(text()).toContain("Public (View Only)");
    expect(text()).toContain("Switch to Draft Mode");
  });

  it("shows the Draft required badge only for the owner", () => {
    const { onToggleRequireDraftForMembers } = mount({ isProjectOwner: true, requireDraftForMembers: true });
    const badge = buttons().find((b) => b.textContent?.includes("Draft required"));
    expect(badge).toBeTruthy();
    act(() => badge!.click());
    expect(onToggleRequireDraftForMembers).toHaveBeenCalledTimes(1);
    mount({ requireDraftForMembers: true, isProjectShared: true });
    expect(text()).not.toContain("Draft required");
  });

  it("shows Allow public to a shared-project owner and hides the badge for a viewer", () => {
    mount({ isProjectOwner: true, isProjectShared: true });
    expect(text()).toContain("Allow public");
    mount({ isViewerRole: true, isProjectShared: true });
    expect(text()).not.toContain("Allow public");
  });

  it("shows the draft copy status text", () => {
    mount({ autoDraftStatus: "copying" });
    expect(text()).toContain("Setting up draft");
    mount({ autoDraftStatus: "ready" });
    expect(text()).toContain("Draft ready");
  });
});

describe("serverReason", () => {
  it("prefers the server message from data.error, data.message and response.data", () => {
    expect(serverReason({ data: { error: "a" } }, "f")).toBe("a");
    expect(serverReason({ data: { message: "b" } }, "f")).toBe("b");
    expect(serverReason({ response: { data: { error: "c" } } }, "f")).toBe("c");
    expect(serverReason({ response: { data: { message: "d" } } }, "f")).toBe("d");
  });

  it("falls back when there is no reason", () => {
    expect(serverReason(new Error("x"), "f")).toBe("f");
    expect(serverReason(undefined, "f")).toBe("f");
    expect(serverReason({ data: { error: "  " } }, "f")).toBe("f");
    expect(serverReason({ data: { error: 5 } }, "f")).toBe("f");
  });
});
