import React, { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { ContextEvent } from "../services/codeAssistantLoop";

interface CodeAssistantContextUsedProps {
  events: ContextEvent[];
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

export const CodeAssistantContextUsed: React.FC<CodeAssistantContextUsedProps> = ({ events }) => {
  const [open, setOpen] = useState(false);
  if (events.length === 0) return null;

  return (
    <div className="mt-1">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1 text-xs text-gray-500 hover:text-gray-700"
      >
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        Context used ({events.length})
      </button>
      {open && (
        <div className="mt-1 space-y-2">
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
