import { describe, it, expect, vi } from "vitest";
import { buildConversationHistory, toFriendlyErrorMessage } from "../components/codeAssistantPanelHelpers";

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
  it("keeps every answered turn in order without trimming", () => {
    const entries = Array.from({ length: 40 }, (_, i) =>
      i % 2 === 0 ? { role: "user" as const, text: `q${i}` } : { role: "assistant" as const, kind: "answer", text: `a${i}` },
    );
    const history = buildConversationHistory(entries);
    expect(history).toHaveLength(40);
    expect(history[0]).toEqual({ role: "user", text: "q0" });
    expect(history[39]).toEqual({ role: "assistant", text: "a39" });
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
