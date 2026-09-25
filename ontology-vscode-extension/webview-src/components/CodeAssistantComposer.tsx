import React, { useEffect, useRef, useState } from "react";
import { Send } from "lucide-react";
import type { CodeAssistantAction } from "./codeAssistantPanelHelpers";

export interface SlashCommand {
  cmd: string;
  label: string;
  description: string;
  disabled: boolean;
  run: () => void;
}

interface CommandOptions {
  editLocked: boolean;
  editLockedMessage: string;
  clearDisabled: boolean;
  canLogout: boolean;
  setAction: (action: CodeAssistantAction) => void;
  clearChat: () => void;
  logout: () => void;
}

export function buildSlashCommands(options: CommandOptions): SlashCommand[] {
  const { setAction } = options;
  const commands: SlashCommand[] = [
    { cmd: "/ask", label: "Ask", description: "Ask a question about this document", disabled: false, run: () => setAction("ask") },
    {
      cmd: "/edit",
      label: "Local edit",
      description: options.editLocked ? options.editLockedMessage : "Request a change, reviewed before anything is applied",
      disabled: options.editLocked,
      run: () => setAction("local-edit"),
    },
    { cmd: "/find", label: "Project findings", description: "Ask a question grounded in the whole project", disabled: false, run: () => setAction("project-findings") },
    { cmd: "/clear", label: "Clear chat", description: "Start a new conversation", disabled: options.clearDisabled, run: options.clearChat },
  ];
  if (options.canLogout) {
    commands.push({ cmd: "/logout", label: "Log out", description: "Remove your saved API key", disabled: false, run: options.logout });
  }
  return commands;
}

interface CodeAssistantComposerProps {
  input: string;
  setInput: (value: string) => void;
  commands: SlashCommand[];
  disabled: boolean;
  placeholder: string;
  onSubmit: () => void;
}

export const CodeAssistantComposer: React.FC<CodeAssistantComposerProps> = (props) => {
  const { input, setInput, commands } = props;
  const [commandIndex, setCommandIndex] = useState(0);
  const composerRef = useRef<HTMLTextAreaElement | null>(null);
  const commandQuery = input.startsWith("/") && !input.includes(" ") ? input.toLowerCase() : null;
  const matches = commandQuery ? commands.filter((c) => c.cmd.startsWith(commandQuery)) : [];
  const activeIndex = Math.min(commandIndex, Math.max(matches.length - 1, 0));

  useEffect(() => {
    const el = composerRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [input]);

  const runCommand = (command: SlashCommand) => {
    if (command.disabled) return;
    command.run();
    setInput("");
    setCommandIndex(0);
  };

  const handleCommandKey = (e: React.KeyboardEvent<HTMLTextAreaElement>): boolean => {
    const moves: Record<string, number> = { ArrowDown: 1, ArrowUp: -1 };
    if (e.key in moves) {
      setCommandIndex((i) => (i + moves[e.key] + matches.length) % matches.length);
    } else if (e.key === "Tab" || e.key === "Enter") {
      runCommand(matches[activeIndex]);
    } else if (e.key === "Escape") {
      setInput("");
    } else {
      return false;
    }
    e.preventDefault();
    return true;
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (matches.length > 0 && handleCommandKey(e)) return;
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      props.onSubmit();
    }
  };

  return (
    <div className="relative flex items-end gap-2">
      {matches.length > 0 && (
        <div className="absolute bottom-full left-0 mb-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg overflow-hidden z-10">
          {matches.map((c, idx) => (
            <button
              key={c.cmd}
              onClick={() => runCommand(c)}
              disabled={c.disabled}
              className={`w-full text-left px-3 py-2 flex items-center justify-between gap-2 disabled:opacity-50 disabled:cursor-not-allowed ${
                idx === activeIndex ? "bg-purple-50" : "hover:bg-gray-50"
              }`}
            >
              <span className="text-sm font-semibold text-purple-700">{c.cmd}</span>
              <span className="text-xs text-gray-500 truncate">{c.description}</span>
            </button>
          ))}
        </div>
      )}
      <textarea
        ref={composerRef}
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={handleKeyDown}
        rows={1}
        disabled={props.disabled}
        placeholder={props.placeholder}
        className="flex-1 px-3 py-2 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm resize-none disabled:opacity-50 min-h-[44px] max-h-[160px] overflow-y-auto"
      />
      <button
        onClick={props.onSubmit}
        disabled={props.disabled || !input.trim()}
        className="p-2.5 text-white bg-purple-600 rounded-lg hover:bg-purple-700 active:scale-90 transition-transform disabled:opacity-50 disabled:active:scale-100 flex-shrink-0"
        title="Send"
      >
        <Send size={16} />
      </button>
    </div>
  );
};
