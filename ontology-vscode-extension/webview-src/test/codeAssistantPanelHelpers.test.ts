import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  buildSystemPrompt,
  buildConversationHistory,
  toFriendlyErrorMessage,
  MAX_HISTORY_TURNS,
  loadStoredChatEntries,
  saveStoredChatEntries,
  clearStoredChatEntries,
  compactEntryForStorage,
  describeLoopStage,
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

describe("review summaries in history", () => {
  it("tells the model what it proposed and what the user did with each group", () => {
    const history = buildConversationHistory([
      { role: "user", text: "label Dog and Cat" },
      {
        role: "assistant",
        kind: "review",
        groups: [
          { serverGroupId: "g1", diff: [{ targetPath: "turtle", before: "ex:Dog a owl:Class .", after: "ex:Dog a owl:Class ; rdfs:label \"Dog\" .", startLine: 4 }] },
          { serverGroupId: "g2", diff: [{ targetPath: "turtle", before: "ex:Cat a owl:Class .", after: "ex:Cat a owl:Class ; rdfs:label \"Cat\" ." }] },
        ],
        decisions: { g1: "applied", g2: "skipped" },
      },
      { role: "user", text: "now do Rabbit" },
    ]);
    expect(history[1].role).toBe("assistant");
    expect(history[1].text).toContain("I proposed 2 change groups for review.");
    expect(history[1].text).toContain("group 1 (turtle line 5");
    expect(history[1].text).toContain("is applied");
    expect(history[1].text).toContain("group 2 (turtle");
    expect(history[1].text).toContain("is skipped");
  });

  it("warns the model that line numbers moved after an applied insert", () => {
    const history = buildConversationHistory([
      { role: "user", text: "add two classes" },
      {
        role: "assistant",
        kind: "review",
        groups: [{ serverGroupId: "g1", diff: [{ targetPath: "turtle", before: "", after: "ex:A a owl:Class .\n\nex:B a owl:Class .", startLine: 397 }] }],
        decisions: { g1: "applied" },
      },
      { role: "user", text: "add more" },
    ]);
    expect(history[1].text).toContain("net +3 lines");
    expect(history[1].text).toContain("Call read_context again");
  });

  it("adds no stale-line note when nothing was applied", () => {
    const history = buildConversationHistory([
      { role: "user", text: "add a class" },
      {
        role: "assistant",
        kind: "review",
        groups: [{ serverGroupId: "g1", diff: [{ targetPath: "turtle", before: "", after: "ex:A a owl:Class .", startLine: 3 }] }],
        decisions: { g1: "skipped" },
      },
    ]);
    expect(history[1].text).not.toContain("stale");
  });
});

describe("compactEntryForStorage", () => {
  it("trims large tool results but keeps the rest of the entry", () => {
    const entry = { id: "e1", role: "assistant", kind: "answer", text: "ok", contextUsed: [{ tool: "read_context", result: "x".repeat(5000) }] };
    const compact = compactEntryForStorage(entry);
    expect(compact.text).toBe("ok");
    expect(String(compact.contextUsed[0].result).length).toBeLessThan(500);
    expect(String(compact.contextUsed[0].result)).toContain("trimmed when saved");
  });

  it("leaves entries without tool results untouched", () => {
    const entry = { id: "u1", role: "user", text: "hi" };
    expect(compactEntryForStorage(entry)).toBe(entry);
  });
});

describe("describeLoopStage", () => {
  it("prefixes the step count when the loop reports it", () => {
    expect(describeLoopStage({ stage: "calling-tool", detail: "read_context", step: 3, maxSteps: 12 })).toBe(
      "Step 3 of 12 · Running read_context...",
    );
    expect(describeLoopStage({ stage: "calling-provider" })).toBe("Thinking...");
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

describe("buildSystemPrompt", () => {
  it("points local edits at statement reads for exact ranges and at propose_rename for renames", () => {
    const prompt = buildSystemPrompt("local-edit", "a.ttl");
    expect(prompt).toContain("propose_rename");
    expect(prompt).toMatch(/read_context with a "statement" target/);
    expect(prompt).toContain("(a.ttl)");
    expect(prompt).toContain("Never claim an edit was applied");
  });

  it("tells read-only modes that neither proposal tool will work", () => {
    for (const action of ["ask", "project-findings"] as const) {
      const prompt = buildSystemPrompt(action);
      expect(prompt).toContain("propose_edit and propose_rename will always be rejected");
      expect(prompt).not.toContain("\"statement\" target");
    }
  });

  it("stays short", () => {
    for (const action of ["ask", "local-edit", "project-findings"] as const) {
      expect(buildSystemPrompt(action, "a.ttl").length).toBeLessThan(1200);
    }
  });
});
