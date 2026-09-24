import React, { useEffect, useRef, useState } from "react";
import { AlertCircle, Send, X, Lock, Copy, Check } from "lucide-react";
import { AskAiIcon } from "./AskAiIcon";
import { hasApiKey, setStoredApiKey } from "../services/LlmInsightsService";
import { CodeAssistantModelSwitcher } from "./CodeAssistantModelSwitcher";
import { CodeAssistantContextUsed } from "./CodeAssistantContextUsed";
import { CodeAssistantReviewGroups, type GroupDecision, type ApplyAllRunState } from "./CodeAssistantReviewGroups";
import { useAuth } from "../custom-hook/useAuth";
import { useSubscription } from "../hooks/useSubscription";
import { createAssistantSession, applyEditGroup, type AssistantSession, type ProposedEditGroupResult } from "../services/codeAssistantSession";
import { runAssistantLoop, type LoopOutcome, type ContextEvent } from "../services/codeAssistantLoop";
import { CodeAssistantDeadEndNotice } from "./CodeAssistantDeadEndNotice";
import { CodeAssistantRecoveryBanner } from "./CodeAssistantRecoveryBanner";
import {
  clearRecoveryLock,
  fetchRecoveryState,
  RecoveryApiError,
  restorePreviousVersion,
  UNLOCKED_RECOVERY_STATE,
  type RecoveryState,
} from "../services/codeAssistantRecovery";
import {
  ACTIONS,
  getApiBaseUrl,
  buildConversationHistory,
  buildSystemPrompt,
  describeLoopStage,
  resolveApplyBlock,
  toFriendlyErrorMessage,
  type CodeAssistantAction,
} from "./codeAssistantPanelHelpers";
import {
  applyRemapResult,
  buildApplyAllQueue,
  classifyApplyFailure,
  formatApplyAllSummary,
  groupLabel,
  runApplyAll,
  type ApplyOneResult,
} from "../services/codeAssistantApplyQueue";
import { errorSignalFrom, parseHttpStatus, toDeadEnd, type DeadEnd } from "../services/codeAssistantDeadEnd";

export type { CodeAssistantAction };

interface CodeAssistantPanelProps {
  projectId?: string;
  projectName?: string;
  documentPath?: string;
  hasUnsavedCodeViewChanges?: boolean;
  onClose?: () => void;
  onProjectRestored?: () => void;
}

type ChatEntry =
  | { id: string; role: "user"; text: string; action: CodeAssistantAction }
  | { id: string; role: "assistant"; kind: "answer"; text: string; contextUsed: ContextEvent[] }
  | {
      id: string;
      role: "assistant";
      kind: "review";
      sessionId: string;
      groups: ProposedEditGroupResult[];
      decisions: Record<string, GroupDecision>;
      errors: Record<string, string>;
      contextUsed: ContextEvent[];
      applyAllRun?: ApplyAllRunState | null;
      applyAllSummary?: string | null;
    }
  | {
      id: string;
      role: "assistant";
      kind: "error";
      text: string;
      deadEnd?: DeadEnd;
      retry?: PromptToRetry;
      retryAt?: number | null;
    };

interface PromptToRetry {
  text: string;
  action: CodeAssistantAction;
}

type ReviewEntry = Extract<ChatEntry, { kind: "review" }>;

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
  hasUnsavedCodeViewChanges = false,
  onClose,
  onProjectRestored,
}) => {
  const { user, logout } = useAuth();
  const { isFree, getUpgradeMessage } = useSubscription();
  const [configured, setConfigured] = useState(hasApiKey());
  const [action, setAction] = useState<CodeAssistantAction>("ask");
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [statusText, setStatusText] = useState("");
  const [entries, setEntries] = useState<ChatEntry[]>([]);
  const [commandIndex, setCommandIndex] = useState(0);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const busyRef = useRef(false);
  const abortControllerRef = useRef<AbortController | null>(null);
  const transcriptEndRef = useRef<HTMLDivElement | null>(null);
  const transcriptScrollRef = useRef<HTMLDivElement | null>(null);
  const composerRef = useRef<HTMLTextAreaElement | null>(null);
  const entriesRef = useRef<ChatEntry[]>(entries);
  const cancelApplyAllRef = useRef<Set<string>>(new Set());
  const applyBusyRef = useRef(false);
  const [applyBusy, setApplyBusy] = useState(false);
  const mountedRef = useRef(true);
  const [recoveryState, setRecoveryState] = useState<RecoveryState>(UNLOCKED_RECOVERY_STATE);
  const [recoveryBusy, setRecoveryBusy] = useState(false);
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const recoveryRequestRef = useRef(0);
  const projectIdRef = useRef(projectId);
  projectIdRef.current = projectId;
  const tokenRef = useRef(user?.token);
  tokenRef.current = user?.token;
  const recoveryLocked = recoveryState.locked;
  const recoveryLockedRef = useRef(recoveryLocked);
  recoveryLockedRef.current = recoveryLocked;
  const applyBlock = resolveApplyBlock({ hasUnsavedCodeViewChanges, recoveryLocked });
  const applyBlockRef = useRef(applyBlock);
  applyBlockRef.current = applyBlock;

  const commitEntries = (updater: (prev: ChatEntry[]) => ChatEntry[]) => {
    entriesRef.current = updater(entriesRef.current);
    setEntries(entriesRef.current);
  };

  const findReviewEntry = (entryId: string): ReviewEntry | undefined => {
    const found = entriesRef.current.find((e) => e.id === entryId);
    return found && found.role === "assistant" && found.kind === "review" ? found : undefined;
  };

  const setApplyBusyNow = (value: boolean) => {
    applyBusyRef.current = value;
    setApplyBusy(value);
  };

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
    { cmd: "/clear", label: "Clear chat", description: "Start a new conversation", disabled: applyBusy, run: () => commitEntries(() => []) },
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
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      abortControllerRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    const el = composerRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [input]);

  useEffect(() => {
    const container = transcriptScrollRef.current;
    if (!container) return;
    const distanceFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
    if (distanceFromBottom < 120) {
      transcriptEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
    }
  }, [entries, busy, statusText]);

  const lastEntry = entries.length > 0 ? entries[entries.length - 1] : undefined;
  const pendingRetryAt =
    lastEntry && lastEntry.role === "assistant" && lastEntry.kind === "error" && lastEntry.retryAt ? lastEntry.retryAt : null;
  useEffect(() => {
    if (pendingRetryAt === null || pendingRetryAt <= Date.now()) return;
    const timer = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= pendingRetryAt) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [pendingRetryAt]);

  const updateReviewEntry = (
    entryId: string,
    updater: (entry: ReviewEntry) => ReviewEntry,
  ) => {
    commitEntries((prev) =>
      prev.map((e) => (e.id === entryId && e.role === "assistant" && e.kind === "review" ? updater(e) : e)),
    );
  };

  const refreshRecovery = async (): Promise<void> => {
    const pid = projectIdRef.current;
    const requestId = ++recoveryRequestRef.current;
    if (!pid) {
      setRecoveryState(UNLOCKED_RECOVERY_STATE);
      return;
    }
    try {
      const next = await fetchRecoveryState(getApiBaseUrl(), tokenRef.current, pid);
      if (requestId === recoveryRequestRef.current && mountedRef.current) setRecoveryState(next);
    } catch (e) {
      if (requestId !== recoveryRequestRef.current || !mountedRef.current) return;
      if (e instanceof RecoveryApiError && e.status === 404) setRecoveryState(UNLOCKED_RECOVERY_STATE);
    }
  };

  const noteRecoveryProblem = () => {
    recoveryLockedRef.current = true;
    setRecoveryState((prev) => (prev.locked ? prev : { ...prev, locked: true }));
    void refreshRecovery();
  };

  useEffect(() => {
    setRecoveryError(null);
    void refreshRecovery();
  }, [projectId]);

  const runRecoveryAction = async (
    request: (apiBaseUrl: string, token: string | undefined, projectId: string) => Promise<void>,
    failurePrefix: string,
    onSuccess?: () => void,
  ) => {
    const pid = projectIdRef.current;
    if (!pid) return;
    setRecoveryBusy(true);
    setRecoveryError(null);
    try {
      await request(getApiBaseUrl(), tokenRef.current, pid);
      onSuccess?.();
    } catch (e) {
      const message = e instanceof Error ? toFriendlyErrorMessage(e.message) : "unexpected error";
      if (mountedRef.current) setRecoveryError(`${failurePrefix}: ${message}`);
    } finally {
      await refreshRecovery();
      if (mountedRef.current) setRecoveryBusy(false);
    }
  };

  const restoreProject = () =>
    runRecoveryAction(restorePreviousVersion, "Couldn't restore the previous version", () => onProjectRestored?.());

  const unlockProject = () => runRecoveryAction(clearRecoveryLock, "Couldn't unlock the project");

  const setBusyNow = (value: boolean) => {
    busyRef.current = value;
    setBusy(value);
  };

  const appendError = (text: string) => {
    commitEntries((prev) => [...prev, { id: nextEntryId(), role: "assistant", kind: "error", text }]);
  };

  const reportFailure = (source: unknown, fallbackMessage: string, prompt: PromptToRetry) => {
    const signal = errorSignalFrom(source, fallbackMessage);
    const deadEnd = toDeadEnd(signal);
    if (!deadEnd) {
      appendError(toFriendlyErrorMessage(signal.message));
      return;
    }
    if (deadEnd.action.kind === "recovery") noteRecoveryProblem();
    const seconds = deadEnd.action.kind === "retry-after" ? deadEnd.action.seconds : null;
    const startedAt = Date.now();
    setNow(startedAt);
    commitEntries((prev) => [
      ...prev,
      {
        id: nextEntryId(),
        role: "assistant",
        kind: "error",
        text: deadEnd.message,
        deadEnd,
        retry: prompt,
        retryAt: seconds ? startedAt + seconds * 1000 : null,
      },
    ]);
    setInput((current) => (current.trim() ? current : prompt.text));
    setAction(prompt.action);
  };

  const appendOutcome = (outcome: LoopOutcome, sessionId: string, contextUsed: ContextEvent[], prompt: PromptToRetry) => {
    if (outcome.kind === "answer") {
      commitEntries((prev) => [...prev, { id: nextEntryId(), role: "assistant", kind: "answer", text: outcome.text, contextUsed }]);
      return;
    }
    if (outcome.kind === "propose") {
      const decisions: Record<string, GroupDecision> = {};
      outcome.result.groups.forEach((g) => {
        decisions[g.serverGroupId] = g.validation.passed ? "pending" : "failed";
      });
      commitEntries((prev) => [
        ...prev,
        { id: nextEntryId(), role: "assistant", kind: "review", sessionId, groups: outcome.result.groups, decisions, errors: {}, contextUsed },
      ]);
      return;
    }
    if (outcome.errorCode) {
      reportFailure(outcome, outcome.reason, prompt);
      return;
    }
    appendError(toFriendlyErrorMessage(outcome.reason));
  };

  const submitMessage = async (override?: PromptToRetry) => {
    const text = (override?.text ?? input).trim();
    const turnAction = override?.action ?? action;
    if (!text || busyRef.current || recoveryLockedRef.current) return;
    if (!projectId) {
      appendError("No project is open. Open a project, then try again.");
      return;
    }
    const prompt: PromptToRetry = { text, action: turnAction };
    const history = buildConversationHistory(entriesRef.current);
    commitEntries((prev) => [...prev, { id: nextEntryId(), role: "user", text, action: turnAction }]);
    setInput((current) => (!override || current.trim() === text ? "" : current));
    setBusyNow(true);
    setStatusText("Starting...");
    const controller = new AbortController();
    abortControllerRef.current = controller;
    try {
      const apiBaseUrl = getApiBaseUrl();
      const token = user?.token;
      const session: AssistantSession = await createAssistantSession(
        apiBaseUrl,
        token,
        { projectId, documentPath: documentPath ?? "", actionType: turnAction, actionContext: JSON.stringify({}) },
        controller.signal,
      );
      const contextEvents: ContextEvent[] = [];
      const outcome = await runAssistantLoop(
        { apiBaseUrl, token, session },
        buildSystemPrompt(turnAction, documentPath),
        text,
        (event) => setStatusText(describeLoopStage(event)),
        controller.signal,
        history,
        (event) => contextEvents.push(event),
      );
      if (controller.signal.aborted) return;
      appendOutcome(outcome, session.sessionId, contextEvents, prompt);
    } catch (e) {
      if (controller.signal.aborted) return;
      reportFailure(e, "Something went wrong talking to the assistant.", prompt);
    } finally {
      if (abortControllerRef.current === controller) {
        setBusyNow(false);
        setStatusText("");
      }
    }
  };

  const cancelRun = () => {
    abortControllerRef.current?.abort();
    setBusyNow(false);
    setStatusText("");
  };

  const applyGroupOnce = async (entryId: string, sessionId: string, serverGroupId: string): Promise<ApplyOneResult> => {
    updateReviewEntry(entryId, (e) => {
      const errors = { ...e.errors };
      delete errors[serverGroupId];
      return { ...e, decisions: { ...e.decisions, [serverGroupId]: "applying" }, errors };
    });
    try {
      const result = await applyEditGroup(getApiBaseUrl(), user?.token, sessionId, serverGroupId);
      updateReviewEntry(entryId, (e) => ({
        ...e,
        decisions: applyRemapResult(e.decisions, serverGroupId, result.remappedPendingGroups ?? []),
      }));
      return { ok: true };
    } catch (err) {
      const signal = errorSignalFrom(err, "Apply failed unexpectedly.");
      const failure = classifyApplyFailure(
        signal.errorCode,
        signal.status ?? parseHttpStatus(signal.message),
        toFriendlyErrorMessage(signal.message),
      );
      updateReviewEntry(entryId, (e) => ({
        ...e,
        decisions: { ...e.decisions, [serverGroupId]: failure.decision },
        errors: { ...e.errors, [serverGroupId]: failure.message },
      }));
      if (failure.checkRecovery) noteRecoveryProblem();
      return { ok: false, failure };
    }
  };

  const currentApplyBlock = (): string | null => {
    if (recoveryLockedRef.current) return "the project is locked for recovery";
    return applyBlockRef.current?.shortReason ?? null;
  };

  const applyGroup = async (entryId: string, sessionId: string, serverGroupId: string) => {
    if (applyBusyRef.current || currentApplyBlock()) return;
    const entry = findReviewEntry(entryId);
    if (!entry || (entry.decisions[serverGroupId] ?? "pending") !== "pending") return;
    setApplyBusyNow(true);
    try {
      await applyGroupOnce(entryId, sessionId, serverGroupId);
    } finally {
      setApplyBusyNow(false);
    }
  };

  const skipGroup = (entryId: string, serverGroupId: string) => {
    updateReviewEntry(entryId, (e) => ({ ...e, decisions: { ...e.decisions, [serverGroupId]: "skipped" } }));
  };

  const applyAllPending = async (entryId: string, sessionId: string) => {
    if (applyBusyRef.current || currentApplyBlock()) return;
    const entry = findReviewEntry(entryId);
    if (!entry) return;
    const queue = buildApplyAllQueue(entry.groups, entry.decisions);
    if (queue.length === 0) return;
    cancelApplyAllRef.current.delete(entryId);
    setApplyBusyNow(true);
    updateReviewEntry(entryId, (e) => ({
      ...e,
      applyAllRun: { running: true, position: 0, total: queue.length, cancelRequested: false },
      applyAllSummary: null,
    }));
    try {
      const report = await runApplyAll(queue, {
        readDecisions: () => findReviewEntry(entryId)?.decisions ?? {},
        applyOne: (serverGroupId) => applyGroupOnce(entryId, sessionId, serverGroupId),
        isCancelRequested: () => cancelApplyAllRef.current.has(entryId),
        blockedReason: () => {
          if (!mountedRef.current) return "the assistant panel was closed";
          if (!findReviewEntry(entryId)) return "the conversation was cleared";
          return currentApplyBlock();
        },
        onProgress: (progress) =>
          updateReviewEntry(entryId, (e) => ({
            ...e,
            applyAllRun: {
              running: true,
              position: progress.position,
              total: progress.total,
              cancelRequested: e.applyAllRun?.cancelRequested ?? false,
            },
          })),
      });
      updateReviewEntry(entryId, (e) => ({
        ...e,
        applyAllRun: null,
        applyAllSummary: formatApplyAllSummary(report, (id) => groupLabel(e.groups, id)),
      }));
    } finally {
      cancelApplyAllRef.current.delete(entryId);
      setApplyBusyNow(false);
    }
  };

  const cancelApplyAll = (entryId: string) => {
    cancelApplyAllRef.current.add(entryId);
    updateReviewEntry(entryId, (e) =>
      e.applyAllRun ? { ...e, applyAllRun: { ...e.applyAllRun, cancelRequested: true } } : e,
    );
  };

  const copyText = async (entryId: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(entryId);
      setTimeout(() => setCopiedId((current) => (current === entryId ? null : current)), 1500);
    } catch {
      /* clipboard unavailable or denied */
    }
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

  const resubmit = (retry: PromptToRetry | undefined) => {
    if (!retry) return;
    void submitMessage(retry);
  };

  return (
    <div className="flex h-full flex-col" style={{ backgroundColor: "var(--color-background)" }}>
      <div className="bg-gradient-to-r from-purple-600 to-indigo-600 px-4 py-3 flex items-center justify-between flex-shrink-0">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="bg-white bg-opacity-20 p-1.5 rounded-[50%_50%_50%_4px] flex-shrink-0">
            <AskAiIcon className="text-white" size={20} />
          </div>
          <div className="min-w-0">
            <h2 className="text-sm font-bold text-white leading-tight">Ask AI</h2>
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

      {recoveryLocked && (
        <CodeAssistantRecoveryBanner
          state={recoveryState}
          busy={recoveryBusy}
          error={recoveryError}
          onRestore={() => void restoreProject()}
          onClear={() => void unlockProject()}
        />
      )}
      <div ref={transcriptScrollRef} className="flex-1 overflow-y-auto px-4 py-4 space-y-3">
        {entries.length === 0 && (
          <div className="space-y-3">
            <p className="text-sm text-gray-500">
              {configured
                ? "Ask a question about this document, or request an edit. Answers are grounded in the actual ontology content."
                : "Pick a model below to add your API key, then ask a question or request an edit."}
            </p>
            <div className="flex flex-wrap gap-2">
                  {ACTIONS.map(({ id, label, icon: Icon }, index) => {
                    const planLocked = id === "local-edit" && isFree;
                    return (
                      <button
                        key={id}
                        onClick={() => setAction(id)}
                        disabled={planLocked}
                        title={planLocked ? getUpgradeMessage("AI-assisted editing") : undefined}
                        style={{ animationDelay: `${index * 70}ms` }}
                        className={`chat-chip-enter px-3 py-1.5 text-xs font-semibold rounded-full border flex items-center gap-1.5 disabled:opacity-50 ${
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
                  <div key={entry.id} className="flex justify-end chat-message-enter">
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
                  <div key={entry.id} className="flex flex-col items-start chat-message-enter">
                    <div className="max-w-[85%] bg-gray-100 rounded-lg rounded-bl-sm px-3 py-2 text-sm text-gray-800 whitespace-pre-wrap">
                      {entry.text}
                    </div>
                    <div className="flex items-center gap-3 mt-1">
                      <button
                        onClick={() => copyText(entry.id, entry.text)}
                        className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600"
                      >
                        {copiedId === entry.id ? <Check size={12} /> : <Copy size={12} />}
                        {copiedId === entry.id ? "Copied" : "Copy"}
                      </button>
                      <CodeAssistantContextUsed events={entry.contextUsed} />
                    </div>
                  </div>
                );
              }
              if (entry.kind === "review") {
                return (
                  <div key={entry.id} className="bg-gray-50 border border-gray-200 rounded-lg p-3 chat-message-enter">
                    <CodeAssistantReviewGroups
                      groups={entry.groups}
                      decisions={entry.decisions}
                      errors={entry.errors}
                      onApply={(groupId) => applyGroup(entry.id, entry.sessionId, groupId)}
                      onSkip={(groupId) => skipGroup(entry.id, groupId)}
                      onApplyAll={() => applyAllPending(entry.id, entry.sessionId)}
                      onCancelApplyAll={() => cancelApplyAll(entry.id)}
                      applyAllRun={entry.applyAllRun ?? null}
                      applyAllSummary={entry.applyAllSummary ?? null}
                      applyBusy={applyBusy}
                      applyBlockedReason={applyBlock?.message ?? null}
                    />
                    <CodeAssistantContextUsed events={entry.contextUsed} />
                  </div>
                );
              }
              if (entry.deadEnd) {
                return (
                  <CodeAssistantDeadEndNotice
                    key={entry.id}
                    deadEnd={entry.deadEnd}
                    active={!busy && entry.id === lastEntry?.id}
                    retryAt={entry.retryAt ?? null}
                    now={now}
                    onResubmit={() => resubmit(entry.retry)}
                    onSignIn={() => logout(true)}
                  />
                );
              }
              return (
                <div key={entry.id} className="flex items-start gap-2 px-3 py-2 bg-red-50 border border-red-200 rounded-lg text-red-900 text-sm chat-message-enter">
                  <AlertCircle size={16} className="flex-shrink-0 mt-0.5" />
                  <span>{entry.text}</span>
                </div>
              );
            })}

            {busy && (
              <div className="flex items-center gap-2 text-gray-500 text-sm px-1">
                <span className="flex items-center gap-0.5">
                  <span className="w-1.5 h-1.5 rounded-full bg-purple-500 animate-bounce" style={{ animationDelay: "0ms" }} />
                  <span className="w-1.5 h-1.5 rounded-full bg-purple-500 animate-bounce" style={{ animationDelay: "150ms" }} />
                  <span className="w-1.5 h-1.5 rounded-full bg-purple-500 animate-bounce" style={{ animationDelay: "300ms" }} />
                </span>
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
                {ACTIONS.map(({ id, label, icon: Icon }, index) => {
                  const planLocked = id === "local-edit" && isFree;
                  return (
                    <button
                      key={id}
                      onClick={() => setAction(id)}
                      disabled={planLocked}
                      title={planLocked ? getUpgradeMessage("AI-assisted editing") : undefined}
                      style={{ animationDelay: `${index * 70}ms` }}
                      className={`chat-chip-enter px-2 py-1 text-[11px] font-semibold rounded-full border flex items-center gap-1 disabled:opacity-50 ${
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
                ref={composerRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleComposerKeyDown}
                rows={1}
                disabled={busy || !projectId || !configured || recoveryLocked}
                placeholder={
                  recoveryLocked
                    ? "Paused until the recovery notice above is resolved..."
                    : !configured
                    ? "Add an API key using the model picker below..."
                    : action === "local-edit"
                      ? "Describe the change you want..."
                      : "Type your question..."
                }
                className="flex-1 px-3 py-2 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm resize-none disabled:opacity-50 min-h-[44px] max-h-[160px] overflow-y-auto"
              />
              <button
                onClick={() => void submitMessage()}
                disabled={busy || !input.trim() || !projectId || !configured || recoveryLocked}
                className="p-2.5 text-white bg-purple-600 rounded-lg hover:bg-purple-700 active:scale-90 transition-transform disabled:opacity-50 disabled:active:scale-100 flex-shrink-0"
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
