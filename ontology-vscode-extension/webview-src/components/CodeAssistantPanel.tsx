import React, { useEffect, useRef, useState } from "react";
import { Bot, Loader2, AlertCircle, Send, X, Lock } from "lucide-react";
import { hasApiKey, setStoredApiKey } from "../services/LlmInsightsService";
import { CodeAssistantModelSwitcher } from "./CodeAssistantModelSwitcher";
import { CodeAssistantReviewGroups, type GroupDecision } from "./CodeAssistantReviewGroups";
import { useAuth } from "../custom-hook/useAuth";
import { useSubscription } from "../hooks/useSubscription";
import { createAssistantSession, applyEditGroup, AssistantApiError, type AssistantSession, type ProposedEditGroupResult } from "../services/codeAssistantSession";
import { runAssistantLoop, type LoopOutcome } from "../services/codeAssistantLoop";
import { ACTIONS, getApiBaseUrl, buildSystemPrompt, describeLoopStage, toFriendlyErrorMessage, type CodeAssistantAction } from "./codeAssistantPanelHelpers";

export type { CodeAssistantAction };

interface CodeAssistantPanelProps {
  projectId?: string;
  projectName?: string;
  documentPath?: string;
  onClose?: () => void;
}

type ChatEntry =
  | { id: string; role: "user"; text: string; action: CodeAssistantAction }
  | { id: string; role: "assistant"; kind: "answer"; text: string }
  | {
      id: string;
      role: "assistant";
      kind: "review";
      sessionId: string;
      groups: ProposedEditGroupResult[];
      decisions: Record<string, GroupDecision>;
      errors: Record<string, string>;
    }
  | { id: string; role: "assistant"; kind: "error"; text: string };

let entryCounter = 0;
function nextEntryId(): string {
  entryCounter += 1;
  return `ca-entry-${entryCounter}`;
}

function actionLabel(action: CodeAssistantAction): string {
  return ACTIONS.find((a) => a.id === action)?.label ?? action;
}

export const CodeAssistantPanel: React.FC<CodeAssistantPanelProps> = ({
  projectId,
  projectName,
  documentPath,
  onClose,
}) => {
  const { user } = useAuth();
  const { isFree, getUpgradeMessage } = useSubscription();
  const [configured, setConfigured] = useState(hasApiKey());
  const [action, setAction] = useState<CodeAssistantAction>("ask");
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [statusText, setStatusText] = useState("");
  const [entries, setEntries] = useState<ChatEntry[]>([]);
  const [commandIndex, setCommandIndex] = useState(0);
  const abortControllerRef = useRef<AbortController | null>(null);
  const transcriptEndRef = useRef<HTMLDivElement | null>(null);

  const slashCommands = [
    { cmd: "/ask", label: "Ask", description: "Ask a question about this document", disabled: false, run: () => setAction("ask") },
    {
      cmd: "/edit",
      label: "Local edit",
      description: isFree ? getUpgradeMessage("AI-assisted editing") : "Request a change, reviewed before anything is applied",
      disabled: isFree,
      run: () => setAction("local-edit"),
    },
    { cmd: "/find", label: "Project findings", description: "Ask a question grounded in the whole project", disabled: false, run: () => setAction("project-findings") },
    { cmd: "/clear", label: "Clear chat", description: "Start a new conversation", disabled: false, run: () => setEntries([]) },
    {
      cmd: "/logout",
      label: "Log out",
      description: "Remove your saved API key",
      disabled: false,
      run: () => {
        setStoredApiKey("");
        setConfigured(false);
      },
    },
  ];
  const commandQuery = input.startsWith("/") && !input.includes(" ") ? input.toLowerCase() : null;
  const commandMatches = commandQuery ? slashCommands.filter((c) => c.cmd.startsWith(commandQuery)) : [];
  const activeCommandIndex = Math.min(commandIndex, Math.max(commandMatches.length - 1, 0));

  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    transcriptEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [entries, busy, statusText]);

  const updateReviewEntry = (
    entryId: string,
    updater: (entry: Extract<ChatEntry, { kind: "review" }>) => Extract<ChatEntry, { kind: "review" }>,
  ) => {
    setEntries((prev) =>
      prev.map((e) => (e.id === entryId && e.role === "assistant" && e.kind === "review" ? updater(e) : e)),
    );
  };

  const appendOutcome = (outcome: LoopOutcome, sessionId: string) => {
    if (outcome.kind === "answer") {
      setEntries((prev) => [...prev, { id: nextEntryId(), role: "assistant", kind: "answer", text: outcome.text }]);
      return;
    }
    if (outcome.kind === "propose") {
      const decisions: Record<string, GroupDecision> = {};
      outcome.result.groups.forEach((g) => {
        decisions[g.serverGroupId] = g.validation.passed ? "pending" : "failed";
      });
      setEntries((prev) => [
        ...prev,
        { id: nextEntryId(), role: "assistant", kind: "review", sessionId, groups: outcome.result.groups, decisions, errors: {} },
      ]);
      return;
    }
    setEntries((prev) => [...prev, { id: nextEntryId(), role: "assistant", kind: "error", text: outcome.reason }]);
  };

  const submitMessage = async () => {
    const text = input.trim();
    if (!text || busy) return;
    if (!projectId) {
      setEntries((prev) => [
        ...prev,
        { id: nextEntryId(), role: "assistant", kind: "error", text: "No project is open. Open a project, then try again." },
      ]);
      return;
    }
    setEntries((prev) => [...prev, { id: nextEntryId(), role: "user", text, action }]);
    setInput("");
    setBusy(true);
    setStatusText("Starting...");
    const controller = new AbortController();
    abortControllerRef.current = controller;
    try {
      const apiBaseUrl = getApiBaseUrl();
      const token = user?.token;
      const session: AssistantSession = await createAssistantSession(
        apiBaseUrl,
        token,
        { projectId, documentPath: documentPath ?? "", actionType: action, actionContext: JSON.stringify({}) },
        controller.signal,
      );
      const outcome = await runAssistantLoop(
        { apiBaseUrl, token, session },
        buildSystemPrompt(action, documentPath),
        text,
        (event) => setStatusText(describeLoopStage(event)),
        controller.signal,
      );
      appendOutcome(outcome, session.sessionId);
    } catch (e) {
      if (controller.signal.aborted) return;
      const raw = e instanceof AssistantApiError || e instanceof Error ? e.message : "Something went wrong talking to the assistant.";
      setEntries((prev) => [...prev, { id: nextEntryId(), role: "assistant", kind: "error", text: toFriendlyErrorMessage(raw) }]);
    } finally {
      setBusy(false);
      setStatusText("");
    }
  };

  const cancelRun = () => {
    abortControllerRef.current?.abort();
    setBusy(false);
    setStatusText("");
  };

  const applyGroup = async (entryId: string, sessionId: string, serverGroupId: string) => {
    updateReviewEntry(entryId, (e) => ({ ...e, decisions: { ...e.decisions, [serverGroupId]: "applying" } }));
    try {
      const apiBaseUrl = getApiBaseUrl();
      const result = await applyEditGroup(apiBaseUrl, user?.token, sessionId, serverGroupId);
      updateReviewEntry(entryId, (e) => {
        const next: Record<string, GroupDecision> = { ...e.decisions, [serverGroupId]: "applied" };
        for (const remap of result.remappedPendingGroups) {
          if (remap.remapped && next[remap.serverGroupId] === "pending") {
            next[remap.serverGroupId] = "stale";
          }
        }
        return { ...e, decisions: next };
      });
    } catch (e) {
      const raw = e instanceof AssistantApiError ? e.message : "Apply failed unexpectedly.";
      updateReviewEntry(entryId, (e2) => ({
        ...e2,
        decisions: { ...e2.decisions, [serverGroupId]: "failed" },
        errors: { ...e2.errors, [serverGroupId]: toFriendlyErrorMessage(raw) },
      }));
    }
  };

  const skipGroup = (entryId: string, serverGroupId: string) => {
    updateReviewEntry(entryId, (e) => ({ ...e, decisions: { ...e.decisions, [serverGroupId]: "skipped" } }));
  };

  const runCommand = (command: (typeof slashCommands)[number]) => {
    if (command.disabled) return;
    command.run();
    setInput("");
    setCommandIndex(0);
  };

  const handleComposerKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (commandMatches.length > 0) {
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setCommandIndex((i) => (i + 1) % commandMatches.length);
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        setCommandIndex((i) => (i - 1 + commandMatches.length) % commandMatches.length);
        return;
      }
      if (e.key === "Tab" || e.key === "Enter") {
        e.preventDefault();
        runCommand(commandMatches[activeCommandIndex]);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        setInput("");
        return;
      }
    }
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void submitMessage();
    }
  };

  return (
    <div className="flex h-full flex-col" style={{ backgroundColor: "var(--color-background)" }}>
      <div className="bg-gradient-to-r from-purple-600 to-indigo-600 px-4 py-3 flex items-center justify-between flex-shrink-0">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="bg-white bg-opacity-20 p-1.5 rounded-lg flex-shrink-0">
            <Bot className="text-white" size={20} />
          </div>
          <div className="min-w-0">
            <h2 className="text-sm font-bold text-white leading-tight">Fix with AI</h2>
            {documentPath && <p className="text-purple-100 text-xs truncate max-w-[280px]">{documentPath}</p>}
          </div>
        </div>
        {onClose && (
          <button
            onClick={onClose}
            className="text-purple-100 hover:text-white hover:bg-white hover:bg-opacity-10 rounded-md p-1 flex-shrink-0"
            title="Close"
          >
            <X size={16} />
          </button>
        )}
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-3">
        {entries.length === 0 && (
          <div className="space-y-3">
            <p className="text-sm text-gray-500">
              {configured
                ? "Ask a question about this document, or request an edit. Answers are grounded in the actual ontology content."
                : "Pick a model below to add your API key, then ask a question or request an edit."}
            </p>
            <div className="flex flex-wrap gap-2">
                  {ACTIONS.map(({ id, label, icon: Icon }) => {
                    const planLocked = id === "local-edit" && isFree;
                    return (
                      <button
                        key={id}
                        onClick={() => setAction(id)}
                        disabled={planLocked}
                        title={planLocked ? getUpgradeMessage("AI-assisted editing") : undefined}
                        className={`px-3 py-1.5 text-xs font-semibold rounded-full border flex items-center gap-1.5 disabled:opacity-50 ${
                          action === id
                            ? "bg-purple-600 border-purple-600 text-white"
                            : "bg-white border-gray-300 text-gray-700 hover:border-purple-400"
                        }`}
                      >
                        {planLocked ? <Lock size={12} /> : <Icon size={12} />}
                        {label}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {entries.map((entry) => {
              if (entry.role === "user") {
                return (
                  <div key={entry.id} className="flex justify-end">
                    <div className="max-w-[85%] bg-purple-600 text-white rounded-lg rounded-br-sm px-3 py-2">
                      <div className="text-[10px] uppercase tracking-wide text-purple-200 mb-0.5">
                        {actionLabel(entry.action)}
                      </div>
                      <div className="text-sm whitespace-pre-wrap">{entry.text}</div>
                    </div>
                  </div>
                );
              }
              if (entry.kind === "answer") {
                return (
                  <div key={entry.id} className="flex justify-start">
                    <div className="max-w-[85%] bg-gray-100 rounded-lg rounded-bl-sm px-3 py-2 text-sm text-gray-800 whitespace-pre-wrap">
                      {entry.text}
                    </div>
                  </div>
                );
              }
              if (entry.kind === "review") {
                return (
                  <div key={entry.id} className="bg-gray-50 border border-gray-200 rounded-lg p-3">
                    <CodeAssistantReviewGroups
                      groups={entry.groups}
                      decisions={entry.decisions}
                      errors={entry.errors}
                      onApply={(groupId) => applyGroup(entry.id, entry.sessionId, groupId)}
                      onSkip={(groupId) => skipGroup(entry.id, groupId)}
                    />
                  </div>
                );
              }
              return (
                <div key={entry.id} className="flex items-start gap-2 px-3 py-2 bg-red-50 border border-red-200 rounded-lg text-red-900 text-sm">
                  <AlertCircle size={16} className="flex-shrink-0 mt-0.5" />
                  <span>{entry.text}</span>
                </div>
              );
            })}

            {busy && (
              <div className="flex items-center gap-2 text-gray-500 text-sm px-1">
                <Loader2 size={14} className="animate-spin" />
                <span>{statusText || "Working..."}</span>
                <button onClick={cancelRun} className="ml-auto text-xs font-semibold text-purple-700 hover:underline">
                  Cancel
                </button>
              </div>
            )}
            <div ref={transcriptEndRef} />
          </div>

          <div className="border-t border-gray-200 px-4 py-3 flex-shrink-0 space-y-2">
            {entries.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {ACTIONS.map(({ id, label, icon: Icon }) => {
                  const planLocked = id === "local-edit" && isFree;
                  return (
                    <button
                      key={id}
                      onClick={() => setAction(id)}
                      disabled={planLocked}
                      title={planLocked ? getUpgradeMessage("AI-assisted editing") : undefined}
                      className={`px-2 py-1 text-[11px] font-semibold rounded-full border flex items-center gap-1 disabled:opacity-50 ${
                        action === id
                          ? "bg-purple-600 border-purple-600 text-white"
                          : "bg-white border-gray-300 text-gray-600 hover:border-purple-400"
                      }`}
                    >
                      {planLocked ? <Lock size={10} /> : <Icon size={10} />}
                      {label}
                    </button>
                  );
                })}
              </div>
            )}
            {!projectId && (
              <div className="px-3 py-1.5 bg-amber-50 border border-amber-200 rounded-md text-amber-900 text-xs">
                Open a project first — the assistant needs one to work against.
              </div>
            )}
            <div className="relative flex items-end gap-2">
              {commandMatches.length > 0 && (
                <div className="absolute bottom-full left-0 mb-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg overflow-hidden z-10">
                  {commandMatches.map((c, idx) => (
                    <button
                      key={c.cmd}
                      onClick={() => runCommand(c)}
                      disabled={c.disabled}
                      className={`w-full text-left px-3 py-2 flex items-center justify-between gap-2 disabled:opacity-50 disabled:cursor-not-allowed ${
                        idx === activeCommandIndex ? "bg-purple-50" : "hover:bg-gray-50"
                      }`}
                    >
                      <span className="text-sm font-semibold text-purple-700">{c.cmd}</span>
                      <span className="text-xs text-gray-500 truncate">{c.description}</span>
                    </button>
                  ))}
                </div>
              )}
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleComposerKeyDown}
                rows={2}
                disabled={busy || !projectId || !configured}
                placeholder={
                  !configured
                    ? "Add an API key using the model picker below..."
                    : action === "local-edit"
                      ? "Describe the change you want..."
                      : "Type your question..."
                }
                className="flex-1 px-3 py-2 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm resize-none disabled:opacity-50"
              />
              <button
                onClick={submitMessage}
                disabled={busy || !input.trim() || !projectId || !configured}
                className="p-2.5 text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:opacity-50 flex-shrink-0"
                title="Send"
              >
                <Send size={16} />
              </button>
            </div>
            <CodeAssistantModelSwitcher onChange={() => setConfigured(hasApiKey())} />
          </div>
    </div>
  );
};

export default CodeAssistantPanel;
