import type { LlmProvider } from "./LlmInsightsService";
import type { HistoryTurn, ToolResultProvenance } from "./codeAssistantProviders";

export const VERBATIM_HISTORY_TURNS = 6;
export const COMPACT_HISTORY_TURN_CHARS = 200;
export const KEEP_RECENT_TOOL_ROUNDS = 2;
export const COMPACTED_TOOL_RESULT_NOTE = "re-run the tool if you need this again";

export const EARLIER_CONVERSATION_HEADER =
  "[Earlier conversation, compacted. Each turn below is truncated and may be outdated; the ontology may have changed since. " +
  "Re-check anything you rely on with the tools.]";
export const EARLIER_CONVERSATION_ACK = "Understood. I will treat that earlier conversation as possibly outdated.";

export interface CompactedToolResultStub {
  compacted: true;
  tool: string;
  provenance?: ToolResultProvenance;
  note: string;
}

function truncateTurnText(text: string): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > COMPACT_HISTORY_TURN_CHARS ? `${flat.slice(0, COMPACT_HISTORY_TURN_CHARS)}...` : flat;
}

export function compactHistory(history: HistoryTurn[]): HistoryTurn[] {
  if (history.length <= VERBATIM_HISTORY_TURNS) return history;
  const older = history.slice(0, history.length - VERBATIM_HISTORY_TURNS);
  const recent = history.slice(history.length - VERBATIM_HISTORY_TURNS);
  const lines = older.map((turn) => `${turn.role === "assistant" ? "Assistant" : "User"}: ${truncateTurnText(turn.text)}`);
  const note: HistoryTurn = { role: "user", text: `${EARLIER_CONVERSATION_HEADER}\n${lines.join("\n")}` };
  const bridge: HistoryTurn[] = recent[0]?.role === "user" ? [{ role: "assistant", text: EARLIER_CONVERSATION_ACK }] : [];
  return [note, ...bridge, ...recent];
}

type NativeRecord = Record<string, unknown>;

function asRecord(value: unknown): NativeRecord | null {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as NativeRecord) : null;
}

function parseJson(text: unknown): unknown {
  if (typeof text !== "string") return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function isCompactedStub(value: unknown): boolean {
  return asRecord(value)?.compacted === true;
}

function provenanceOf(payload: unknown): ToolResultProvenance | undefined {
  const provenance = asRecord(asRecord(payload)?.provenance);
  return provenance ? (provenance as unknown as ToolResultProvenance) : undefined;
}

export function makeCompactedStub(tool: string, payload: unknown): CompactedToolResultStub {
  const provenance = provenanceOf(payload);
  return {
    compacted: true,
    tool: provenance?.tool ?? tool,
    ...(provenance ? { provenance } : {}),
    note: COMPACTED_TOOL_RESULT_NOTE,
  };
}

function isClaudeToolResultMessage(message: NativeRecord): boolean {
  return (
    message.role === "user" &&
    Array.isArray(message.content) &&
    message.content.length > 0 &&
    (message.content as unknown[]).every((b) => asRecord(b)?.type === "tool_result")
  );
}

function isGeminiFunctionResponseMessage(message: NativeRecord): boolean {
  return (
    message.role === "user" &&
    Array.isArray(message.parts) &&
    message.parts.length > 0 &&
    (message.parts as unknown[]).every((p) => asRecord(asRecord(p)?.functionResponse) !== null)
  );
}

function roundIndexes(provider: LlmProvider, messages: unknown[]): number[][] {
  const rounds: number[][] = [];
  if (provider === "openai") {
    let current: number[] | null = null;
    messages.forEach((m, idx) => {
      const rec = asRecord(m);
      if (rec?.role === "tool") {
        if (!current) {
          current = [];
          rounds.push(current);
        }
        current.push(idx);
      } else {
        current = null;
      }
    });
    return rounds;
  }
  messages.forEach((m, idx) => {
    const rec = asRecord(m);
    if (!rec) return;
    if (provider === "claude" ? isClaudeToolResultMessage(rec) : isGeminiFunctionResponseMessage(rec)) {
      rounds.push([idx]);
    }
  });
  return rounds;
}

function toolNamesById(provider: LlmProvider, messages: unknown[]): Map<string, string> {
  const names = new Map<string, string>();
  for (const m of messages) {
    const rec = asRecord(m);
    if (!rec) continue;
    if (provider === "openai" && Array.isArray(rec.tool_calls)) {
      for (const call of rec.tool_calls as unknown[]) {
        const c = asRecord(call);
        const fn = asRecord(c?.function);
        if (typeof c?.id === "string" && typeof fn?.name === "string") names.set(c.id, fn.name);
      }
    }
    if (provider === "claude" && rec.role === "assistant" && Array.isArray(rec.content)) {
      for (const block of rec.content as unknown[]) {
        const b = asRecord(block);
        if (b?.type === "tool_use" && typeof b.id === "string" && typeof b.name === "string") names.set(b.id, b.name);
      }
    }
  }
  return names;
}

function compactOpenAiMessage(message: NativeRecord, names: Map<string, string>): NativeRecord {
  const payload = parseJson(message.content);
  if (isCompactedStub(payload)) return message;
  const tool = names.get(String(message.tool_call_id ?? "")) ?? "unknown";
  return { ...message, content: JSON.stringify(makeCompactedStub(tool, payload)) };
}

function compactClaudeMessage(message: NativeRecord, names: Map<string, string>): NativeRecord {
  const content = (message.content as unknown[]).map((block) => {
    const b = asRecord(block) as NativeRecord;
    const payload = parseJson(b.content);
    if (isCompactedStub(payload)) return b;
    const tool = names.get(String(b.tool_use_id ?? "")) ?? "unknown";
    return { ...b, content: JSON.stringify(makeCompactedStub(tool, payload)) };
  });
  return { ...message, content };
}

function compactGeminiMessage(message: NativeRecord): NativeRecord {
  const parts = (message.parts as unknown[]).map((part) => {
    const p = asRecord(part) as NativeRecord;
    const fr = asRecord(p.functionResponse) as NativeRecord;
    if (isCompactedStub(fr.response)) return p;
    const tool = typeof fr.name === "string" ? fr.name : "unknown";
    return { ...p, functionResponse: { ...fr, response: makeCompactedStub(tool, fr.response) } };
  });
  return { ...message, parts };
}

export function compactOlderToolResults(
  provider: LlmProvider,
  messages: unknown[],
  keepRecentRounds: number = KEEP_RECENT_TOOL_ROUNDS,
): { messages: unknown[]; compactedRounds: number } {
  const rounds = roundIndexes(provider, messages);
  const older = rounds.slice(0, Math.max(0, rounds.length - keepRecentRounds));
  if (older.length === 0) return { messages, compactedRounds: 0 };
  const targets = new Set(older.flat());
  const names = toolNamesById(provider, messages);
  const next = messages.map((m, idx) => {
    if (!targets.has(idx)) return m;
    const rec = asRecord(m) as NativeRecord;
    if (provider === "openai") return compactOpenAiMessage(rec, names);
    if (provider === "claude") return compactClaudeMessage(rec, names);
    return compactGeminiMessage(rec);
  });
  return { messages: next, compactedRounds: older.length };
}
