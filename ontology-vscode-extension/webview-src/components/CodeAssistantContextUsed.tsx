import React, { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { ContextEvent } from "../services/codeAssistantLoop";
import type { ProviderUsage } from "../services/codeAssistantProviders";
import { formatUsageLine, totalUsage } from "./codeAssistantPanelHelpers";

interface CodeAssistantContextUsedProps {
  events: ContextEvent[];
  usage?: ProviderUsage[];
}

function summarizeArgs(tool: string, args: Record<string, unknown>): string {
  if (tool === "run_sparql" && typeof args.query === "string") return args.query;
  if (tool === "read_context" && Array.isArray(args.targets)) {
    return (args.targets as Array<Record<string, unknown>>).map((t) => String(t.value ?? "")).join(", ");
  }
  return JSON.stringify(args);
}

function summarizeResult(result: unknown): string {
  try {
    const text = JSON.stringify(result, null, 2);
    return text.length > 800 ? `${text.slice(0, 800)}…` : text;
  } catch {
    return String(result);
  }
}

export const CodeAssistantContextUsed: React.FC<CodeAssistantContextUsedProps> = ({ events, usage: rawUsage }) => {
  const [open, setOpen] = useState(false);
  const usage = Array.isArray(rawUsage) ? rawUsage.filter((u): u is ProviderUsage => Boolean(u) && typeof u === "object") : [];
  if (events.length === 0 && usage.length === 0) return null;
  const label = events.length > 0 ? `Context used (${events.length})` : "Usage";

  return (
    <div className="mt-1">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1 text-xs text-gray-500 hover:text-gray-700"
      >
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        {label}
        {usage.length > 0 && <span className="text-gray-400" data-usage-total>· {formatUsageLine(totalUsage(usage))}</span>}
      </button>
      {open && (
        <div className="mt-1 space-y-2">
          {usage.length > 0 && (
            <div className="text-xs rounded p-2 bg-gray-50 text-gray-700" data-usage-turns>
              <div className="font-semibold">Usage per turn</div>
              <ol className="mt-1 space-y-0.5">
                {usage.map((turn, i) => (
                  <li key={i} className="font-mono">
                    <span className="text-gray-500">Turn {i + 1}: </span>
                    {formatUsageLine(turn)}
                  </li>
                ))}
              </ol>
            </div>
          )}
          {events.map((event, i) => (
            <div
              key={i}
              className={`text-xs font-mono rounded p-2 ${event.isError ? "bg-red-50 text-red-800" : "bg-gray-50 text-gray-700"}`}
            >
              <div className="font-semibold">{event.tool}</div>
              <div className="text-gray-500 truncate">{summarizeArgs(event.tool, event.args)}</div>
              <pre className="whitespace-pre-wrap break-words mt-1">{summarizeResult(event.result)}</pre>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default CodeAssistantContextUsed;
