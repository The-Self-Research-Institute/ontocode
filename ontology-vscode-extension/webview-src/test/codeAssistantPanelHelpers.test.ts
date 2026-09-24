import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  buildConversationHistory,
  toFriendlyErrorMessage,
  MAX_HISTORY_TURNS,
  loadStoredChatEntries,
  saveStoredChatEntries,
  clearStoredChatEntries,
} from "../components/codeAssistantPanelHelpers";

describe("toFriendlyErrorMessage", () => {
  it("leaves a short, already-friendly message untouched", () => {
    expect(toFriendlyErrorMessage("No project is open. Open a project, then try again.")).toBe(
      "No project is open. Open a project, then try again.",
    );
  });

  it("collapses a raw MongoDB driver exception into a short timeout message", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const raw =
      "Timed out while waiting for a server that matches ReadPreferenceServerSelector{readPreference=primary}. " +
      "Client view of cluster state is {type=UNKNOWN, servers=[{address=127.0.0.1:27018, type=UNKNOWN, " +
      "state=CONNECTING, exception={com.mongodb.MongoSocketReadException: Prematurely reached end of stream}}]}";

    expect(toFriendlyErrorMessage(raw)).toBe("The server took too long to respond. Try again in a moment.");
  });

  it("collapses a raw connection-refused exception into a short unreachable message", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const raw =
      "java.net.ConnectException: Connection refused (Connection refused); nested exception is " +
      "org.springframework.dao.DataAccessResourceFailureException: Socket connection error";

    expect(toFriendlyErrorMessage(raw)).toBe("Couldn't reach a required service on the server. Try again shortly.");
  });

  it("falls back to a generic message for other long technical dumps", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const raw = "com.example.SomeInternalException: " + "x".repeat(200);

    expect(toFriendlyErrorMessage(raw)).toBe("Something went wrong on the server. Try again in a moment.");
  });
});

describe("buildConversationHistory", () => {
  it("keeps every answered turn in order when under the cap", () => {
    const entries = Array.from({ length: 10 }, (_, i) =>
      i % 2 === 0 ? { role: "user" as const, text: `q${i}` } : { role: "assistant" as const, kind: "answer", text: `a${i}` },
    );
    const history = buildConversationHistory(entries);
    expect(history).toHaveLength(10);
    expect(history[0]).toEqual({ role: "user", text: "q0" });
    expect(history[9]).toEqual({ role: "assistant", text: "a9" });
  });

  it("caps to the most recent turns once a persisted thread grows past the limit", () => {
    const entries = Array.from({ length: MAX_HISTORY_TURNS + 10 }, (_, i) =>
      i % 2 === 0
        ? { role: "user" as const, text: `q${i}` }
        : { role: "assistant" as const, kind: "answer", text: `a${i}` },
    );
    const history = buildConversationHistory(entries);
    expect(history).toHaveLength(MAX_HISTORY_TURNS);
    expect(history[0]).toEqual({ role: "user", text: "q10" });
    expect(history[history.length - 1]).toEqual({
      role: "assistant",
      text: `a${MAX_HISTORY_TURNS + 9}`,
    });
  });

  it("drops a prompt that only produced an error so a retry is not sent twice", () => {
    const history = buildConversationHistory([
      { role: "user", text: "first" },
      { role: "assistant", kind: "answer", text: "answer" },
      { role: "user", text: "failed" },
      { role: "assistant", kind: "error", text: "boom" },
      { role: "user", text: "proposal" },
      { role: "assistant", kind: "review" },
    ]);
    expect(history).toEqual([
      { role: "user", text: "first" },
      { role: "assistant", text: "answer" },
      { role: "user", text: "proposal" },
    ]);
  });
});

describe("chat history storage", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns null when nothing is stored yet", () => {
    expect(loadStoredChatEntries("proj-1")).toBeNull();
  });

  it("round-trips entries through save and load", () => {
    const entries = [{ id: "ca-entry-1", role: "user", text: "hi" }];
    saveStoredChatEntries("proj-1", entries);
    expect(loadStoredChatEntries("proj-1")).toEqual(entries);
  });

  it("keeps different projects' threads separate", () => {
    saveStoredChatEntries("proj-1", [{ id: "a" }]);
    saveStoredChatEntries("proj-2", [{ id: "b" }]);
    expect(loadStoredChatEntries("proj-1")).toEqual([{ id: "a" }]);
    expect(loadStoredChatEntries("proj-2")).toEqual([{ id: "b" }]);
  });

  it("caps stored entries to the most recent ones", () => {
    const entries = Array.from({ length: 150 }, (_, i) => ({ id: `ca-entry-${i}` }));
    saveStoredChatEntries("proj-1", entries);
    const stored = loadStoredChatEntries<{ id: string }>("proj-1");
    expect(stored?.length).toBeLessThan(150);
    expect(stored?.[stored.length - 1]).toEqual({ id: "ca-entry-149" });
  });

  it("removes the stored thread on clear", () => {
    saveStoredChatEntries("proj-1", [{ id: "a" }]);
    clearStoredChatEntries("proj-1");
    expect(loadStoredChatEntries("proj-1")).toBeNull();
  });
});
