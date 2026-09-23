import React, { useEffect, useRef, useState } from "react";
import { Bot, X, Settings, Loader2, AlertCircle } from "lucide-react";
import { hasApiKey } from "../services/LlmInsightsService";
import LLMSettingsPanel from "./LLMSettingsPanel";
import { CodeAssistantReviewGroups, type GroupDecision } from "./CodeAssistantReviewGroups";
import { useAuth } from "../custom-hook/useAuth";
import { useSubscription } from "../hooks/useSubscription";
import { createAssistantSession, applyEditGroup, AssistantApiError, type AssistantSession, type ProposedEditGroupResult } from "../services/codeAssistantSession";
import { runAssistantLoop, type LoopOutcome } from "../services/codeAssistantLoop";
import { ACTIONS, getApiBaseUrl, buildSystemPrompt, describeLoopStage, type CodeAssistantAction } from "./codeAssistantPanelHelpers";

export type { CodeAssistantAction };

interface CodeAssistantPanelProps {
  projectId?: string;
  projectName?: string;
  documentPath?: string;
  onClose: () => void;
}

type Phase = "action-picker" | "configure-provider" | "compose" | "running" | "answer" | "review" | "error";

export const CodeAssistantPanel: React.FC<CodeAssistantPanelProps> = ({
  projectId,
  projectName,
  documentPath,
  onClose,
}) => {
  const { user } = useAuth();
  const { isFree, getUpgradeMessage } = useSubscription();
  const [phase, setPhase] = useState<Phase>(hasApiKey() ? "action-picker" : "configure-provider");
  const [selectedAction, setSelectedAction] = useState<CodeAssistantAction | null>(null);
  const [message, setMessage] = useState("");
  const [stageLabel, setStageLabel] = useState("");
  const [answerText, setAnswerText] = useState("");
  const [reviewGroups, setReviewGroups] = useState<ProposedEditGroupResult[]>([]);
  const [groupDecisions, setGroupDecisions] = useState<Record<string, GroupDecision>>({});
  const [errorText, setErrorText] = useState("");
  const [sessionRef, setSessionRef] = useState<AssistantSession | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
    };
  }, []);

  const handleClose = () => {
    abortControllerRef.current?.abort();
    onClose();
  };

  const chooseAction = (action: CodeAssistantAction) => {
    if (action === "local-edit" && isFree) return;
    if (!hasApiKey()) {
      setPhase("configure-provider");
      return;
    }
    setSelectedAction(action);
    setMessage("");
    setPhase("compose");
  };

  const handleOutcome = (outcome: LoopOutcome) => {
    if (outcome.kind === "answer") {
      setAnswerText(outcome.text);
      setPhase("answer");
      return;
    }
    if (outcome.kind === "propose") {
      setReviewGroups(outcome.result.groups);
      const decisions: Record<string, GroupDecision> = {};
      outcome.result.groups.forEach((g) => {
        decisions[g.serverGroupId] = g.validation.passed ? "pending" : "failed";
      });
      setGroupDecisions(decisions);
      setPhase("review");
      return;
    }
    setErrorText(outcome.reason);
    setPhase("error");
  };

  const submitMessage = async () => {
    if (!selectedAction || !message.trim()) return;
    if (!projectId) {
      setErrorText("No project is open. Open a project, then try again.");
      setPhase("error");
      return;
    }
    setPhase("running");
    setStageLabel("Starting...");
    const controller = new AbortController();
    abortControllerRef.current = controller;
    try {
      const apiBaseUrl = getApiBaseUrl();
      const token = user?.token;
      const session = await createAssistantSession(
        apiBaseUrl,
        token,
        { projectId, documentPath: documentPath ?? "", actionType: selectedAction, actionContext: {} },
        controller.signal,
      );
      setSessionRef(session);

      const outcome = await runAssistantLoop(
        { apiBaseUrl, token, session },
        buildSystemPrompt(selectedAction, documentPath),
        message.trim(),
        (event) => setStageLabel(describeLoopStage(event)),
        controller.signal,
      );
      handleOutcome(outcome);
    } catch (e) {
      if (controller.signal.aborted) return;
      setErrorText(
        e instanceof AssistantApiError || e instanceof Error ? e.message : "Something went wrong talking to the assistant.",
      );
      setPhase("error");
    }
  };

  const applyGroup = async (serverGroupId: string) => {
    if (!sessionRef) return;
    setGroupDecisions((prev) => ({ ...prev, [serverGroupId]: "applying" }));
    try {
      const apiBaseUrl = getApiBaseUrl();
      const result = await applyEditGroup(apiBaseUrl, user?.token, sessionRef.sessionId, serverGroupId);
      setGroupDecisions((prev) => {
        const next: Record<string, GroupDecision> = { ...prev, [serverGroupId]: "applied" };
        for (const remap of result.remappedPendingGroups) {
          if (remap.remapped && next[remap.serverGroupId] === "pending") {
            next[remap.serverGroupId] = "stale";
          }
        }
        return next;
      });
    } catch {
      setGroupDecisions((prev) => ({ ...prev, [serverGroupId]: "failed" }));
    }
  };

  const skipGroup = (serverGroupId: string) => {
    setGroupDecisions((prev) => ({ ...prev, [serverGroupId]: "skipped" }));
  };

  const backToStart = () => {
    setSelectedAction(null);
    setMessage("");
    setSessionRef(null);
    setReviewGroups([]);
    setGroupDecisions({});
    setErrorText("");
    setPhase("action-picker");
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-40 z-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-2xl max-h-[85vh] overflow-hidden flex flex-col">
        <div className="bg-gradient-to-r from-purple-600 to-indigo-600 px-6 py-5 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="bg-white bg-opacity-20 p-2 rounded-lg">
              <Bot className="text-white" size={26} />
            </div>
            <div>
              <h2 className="text-xl font-bold text-white">Fix with AI</h2>
              {projectName && <p className="text-purple-100 text-sm mt-0.5">Project: {projectName}</p>}
              {documentPath && (
                <p className="text-purple-100 text-xs mt-0.5 truncate max-w-md">{documentPath}</p>
              )}
            </div>
          </div>
          <button
            onClick={handleClose}
            className="text-white hover:bg-white hover:bg-opacity-20 p-2 rounded-lg transition-colors"
          >
            <X size={22} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-6">
          {phase === "action-picker" && (
            <div className="space-y-3">
              <p className="text-sm text-gray-600">Choose what you want the assistant to do.</p>
              {!projectId && (
                <div className="px-4 py-3 bg-amber-50 border border-amber-200 rounded-lg text-amber-900 text-sm">
                  Open a project first — the assistant needs one to work against.
                </div>
              )}
              {ACTIONS.map(({ id, label, description, icon: Icon }) => {
                const planLocked = id === "local-edit" && isFree;
                return (
                  <button
                    key={id}
                    onClick={() => chooseAction(id)}
                    disabled={!projectId || planLocked}
                    title={planLocked ? getUpgradeMessage("AI-assisted editing") : undefined}
                    className="w-full text-left px-4 py-3 border-2 border-gray-200 rounded-lg hover:border-purple-400 hover:bg-purple-50 disabled:opacity-50 disabled:hover:border-gray-200 disabled:hover:bg-white transition-colors flex items-start gap-3"
                  >
                    <Icon size={20} className="text-purple-600 flex-shrink-0 mt-0.5" />
                    <div>
                      <div className="font-semibold text-gray-800">{label}</div>
                      <div className="text-xs text-gray-500 mt-0.5">
                        {planLocked ? getUpgradeMessage("AI-assisted editing") : description}
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>
          )}

          {phase === "configure-provider" && (
            <div className="space-y-4">
              <div className="flex items-start gap-3 px-4 py-3 bg-amber-50 border border-amber-200 rounded-lg text-amber-900 text-sm">
                <Settings size={18} className="flex-shrink-0 mt-0.5" />
                <span>Configure an AI provider and API key to use the assistant.</span>
              </div>
              <LLMSettingsPanel onSave={() => setPhase("action-picker")} />
            </div>
          )}

          {phase === "compose" && selectedAction && (
            <div className="space-y-3">
              <p className="text-sm text-gray-600">{ACTIONS.find((a) => a.id === selectedAction)?.description}</p>
              <textarea
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                rows={5}
                placeholder={selectedAction === "local-edit" ? "Describe the change you want..." : "Type your question..."}
                className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm resize-none"
              />
              <div className="flex gap-3">
                <button
                  onClick={() => setPhase("action-picker")}
                  className="px-4 py-2 text-sm font-semibold text-gray-700 bg-white border-2 border-gray-300 rounded-lg hover:bg-gray-50"
                >
                  Back
                </button>
                <button
                  onClick={submitMessage}
                  disabled={!message.trim()}
                  className="px-4 py-2 text-sm font-semibold text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:opacity-50"
                >
                  Submit
                </button>
              </div>
            </div>
          )}

          {phase === "running" && (
            <div className="flex flex-col items-center justify-center py-10 gap-3 text-gray-600">
              <Loader2 size={28} className="animate-spin text-purple-600" />
              <p className="text-sm">{stageLabel}</p>
            </div>
          )}

          {phase === "answer" && (
            <div className="space-y-4">
              <div className="px-4 py-3 bg-gray-50 border border-gray-200 rounded-lg text-sm text-gray-800 whitespace-pre-wrap">
                {answerText}
              </div>
              <button
                onClick={backToStart}
                className="px-4 py-2 text-sm font-semibold text-purple-700 bg-purple-50 border border-purple-200 rounded-lg hover:bg-purple-100"
              >
                Ask something else
              </button>
            </div>
          )}

          {phase === "review" && (
            <div className="space-y-4">
              <CodeAssistantReviewGroups
                groups={reviewGroups}
                decisions={groupDecisions}
                onApply={applyGroup}
                onSkip={skipGroup}
              />
              <button
                onClick={backToStart}
                className="px-4 py-2 text-sm font-semibold text-purple-700 bg-purple-50 border border-purple-200 rounded-lg hover:bg-purple-100"
              >
                Done
              </button>
            </div>
          )}

          {phase === "error" && (
            <div className="space-y-3">
              <div className="flex items-start gap-3 px-4 py-3 bg-red-50 border border-red-200 rounded-lg text-red-900 text-sm">
                <AlertCircle size={18} className="flex-shrink-0 mt-0.5" />
                <span>{errorText}</span>
              </div>
              <button
                onClick={backToStart}
                className="px-4 py-2 text-sm font-semibold text-purple-700 bg-purple-50 border border-purple-200 rounded-lg hover:bg-purple-100"
              >
                Back
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default CodeAssistantPanel;
