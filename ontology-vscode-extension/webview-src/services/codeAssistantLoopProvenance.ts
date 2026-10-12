import type { ToolResultProvenance } from "./codeAssistantProviderTypes";
import type { AssistantSession } from "./codeAssistantSessionTypes";

const PROVENANCE_REASON_CHARS = 160;

function shorten(text: string, max: number = PROVENANCE_REASON_CHARS): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max)}...` : flat;
}

function rangeTargetParts(value: string): { format?: string; range?: string } {
  const match = /^([a-z]+):(\d+-\d+)$/i.exec(value.trim());
  return match ? { format: match[1].toLowerCase(), range: match[2] } : {};
}

export function buildToolProvenance(
  session: AssistantSession,
  name: string,
  args: Record<string, unknown>,
  revision: number | undefined,
  step: number,
): ToolResultProvenance {
  const base = { tool: name, revision: typeof revision === "number" ? revision : session.snapshot.revision, step };
  if (name === "read_context") {
    const targets = (Array.isArray(args.targets) ? args.targets : []).map((t) => {
      const rec = (t ?? {}) as Record<string, unknown>;
      return { type: String(rec.type ?? ""), value: String(rec.value ?? "") };
    });
    const rangeParts = targets.filter((t) => t.type === "range").map((t) => rangeTargetParts(t.value));
    const format = rangeParts.find((p) => p.format)?.format;
    const ranges = rangeParts.map((p) => p.range).filter((r): r is string => Boolean(r));
    const kind = typeof args.kind === "string" ? args.kind : "definitions";
    const reason = shorten(`${kind} for ${targets.map((t) => `${t.type} ${t.value}`).join(", ") || "no targets"}`);
    return {
      ...base,
      ...(format ? { format } : { targetPath: session.snapshot.documentPath }),
      ...(ranges.length > 0 ? { range: ranges.join(",") } : {}),
      reason,
    };
  }
  if (name === "run_sparql") {
    return { ...base, format: "sparql", reason: shorten(`query: ${String(args.query ?? "")}`) };
  }
  return { ...base, targetPath: session.snapshot.documentPath, reason: shorten(`${name} ${JSON.stringify(args ?? {})}`) };
}
