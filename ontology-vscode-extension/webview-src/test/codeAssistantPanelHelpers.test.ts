import { describe, it, expect, vi } from "vitest";
import { toFriendlyErrorMessage } from "../components/codeAssistantPanelHelpers";

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
