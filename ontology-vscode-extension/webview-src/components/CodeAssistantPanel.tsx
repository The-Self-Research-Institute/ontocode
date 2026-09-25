import React, { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { AskAiIcon } from "./AskAiIcon";
import { hasApiKey, setStoredApiKey } from "../services/LlmInsightsService";
import { CodeAssistantModelSwitcher } from "./CodeAssistantModelSwitcher";
import { CodeAssistantRecoveryBanner } from "./CodeAssistantRecoveryBanner";
import { CodeAssistantActionChips } from "./CodeAssistantActionChips";
import { CodeAssistantTranscript } from "./CodeAssistantTranscript";
import { CodeAssistantComposer, buildSlashCommands } from "./CodeAssistantComposer";
import type { ReviewHandlers } from "./CodeAssistantTranscriptEntry";
import { useAuth } from "../custom-hook/useAuth";
import { useSubscription } from "../hooks/useSubscription";
import { useCodeAssistantRecovery } from "../hooks/useCodeAssistantRecovery";
import { useCodeAssistantEntries } from "../hooks/useCodeAssistantEntries";
import { useCodeAssistantRun } from "../hooks/useCodeAssistantRun";
import { useCodeAssistantApply } from "../hooks/useCodeAssistantApply";
import type { AppliedRange } from "../services/codeAssistantSession";
import { getApiBaseUrl, resolveApplyBlock, clearStoredChatEntries, type CodeAssistantAction } from "./codeAssistantPanelHelpers";
import { getCachedProviderConfig, getProviderConfig, type ProviderConfig } from "../services/codeAssistantProviderConfig";
import type { EditorSelectionContext } from "./codeSelection";
import type { PromptToRetry } from "./codeAssistantChatEntries";
import { SelectionChip } from "./CodeAssistantSelectionChip";

export type { CodeAssistantAction };

interface CodeAssistantPanelProps {
  projectId?: string;
  projectName?: string;
  documentPath?: string;
  hasUnsavedCodeViewChanges?: boolean;
  onClose?: () => void;
  onProjectRestored?: () => void;
  onApplySuccess?: (changedTexts: string[], appliedRanges: AppliedRange[]) => void;
  onShowInCodeView?: (format: string, startLine: number) => void;
  recoveryShownByHost?: boolean;
  recoveryVersion?: number;
  onRecoveryChanged?: () => void;
  editorSelection?: PanelEditorSelection | null;
  onClearEditorSelection?: () => void;
}

export interface PanelEditorSelection extends EditorSelectionContext {
  format: string;
  pageStartLine: number;
}

function composerPlaceholder(recoveryLocked: boolean, ready: boolean, action: CodeAssistantAction): string {
  if (recoveryLocked) return "Paused until the recovery notice above is resolved...";
  if (!ready) return "Add an API key using the model picker below...";
  return action === "local-edit" ? "Describe the change you want..." : "Type your question...";
}

const PanelHeader: React.FC<{ documentPath?: string; onClose?: () => void }> = ({ documentPath, onClose }) => (
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
);

export const CodeAssistantPanel: React.FC<CodeAssistantPanelProps> = (props) => {
  const { projectId, documentPath, hasUnsavedCodeViewChanges = false, editorSelection = null } = props;
  const { user, logout } = useAuth();
  const { isFree, getUpgradeMessage } = useSubscription();
  const [configured, setConfigured] = useState(hasApiKey());
  const [providerConfig, setProviderConfig] = useState<ProviderConfig | null>(() => getCachedProviderConfig());
  const managedProvider = providerConfig?.managed ? providerConfig : null;
  const ready = managedProvider !== null || configured;
  const [action, setAction] = useState<CodeAssistantAction>("ask");
  const [input, setInput] = useState("");
  const mountedRef = useRef(true);
  const projectIdRef = useRef(projectId);
  projectIdRef.current = projectId;
  const tokenRef = useRef(user?.token);
  tokenRef.current = user?.token;
  const editLockedMessage = getUpgradeMessage("AI-assisted editing");

  const recovery = useCodeAssistantRecovery({
    projectId,
    projectIdRef,
    tokenRef,
    token: user?.token,
    mountedRef,
    recoveryVersion: props.recoveryVersion ?? 0,
    onRecoveryChanged: props.onRecoveryChanged,
    onProjectRestored: props.onProjectRestored,
  });
  const { recoveryLocked, recoveryLockedRef } = recovery;
  const applyBlock = resolveApplyBlock({ hasUnsavedCodeViewChanges, recoveryLocked });
  const applyBlockRef = useRef(applyBlock);
  applyBlockRef.current = applyBlock;

  const chat = useCodeAssistantEntries(projectId, projectIdRef, (previous) => run.abandonRunFor(previous));
  const run = useCodeAssistantRun({
    chat,
    projectIdRef,
    mountedRef,
    recoveryLockedRef,
    noteRecoveryProblem: recovery.noteRecoveryProblem,
    setInput,
    setAction,
    setProviderConfig,
  });
  const apply = useCodeAssistantApply({
    chat,
    tokenRef,
    projectIdRef,
    mountedRef,
    blockedReason: () => (recoveryLockedRef.current ? "the project is locked for recovery" : applyBlockRef.current?.shortReason ?? null),
    noteRecoveryProblem: recovery.noteRecoveryProblem,
    onApplySuccess: props.onApplySuccess,
  });

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      run.abortOnUnmount();
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    void getProviderConfig(getApiBaseUrl(), user?.token).then((config) => {
      if (!cancelled) setProviderConfig(config);
    });
    return () => {
      cancelled = true;
    };
  }, [user?.token]);

  const submitMessage = (override?: PromptToRetry) =>
    void run.submit({
      text: (override?.text ?? input).trim(),
      action: override?.action ?? action,
      projectId,
      documentPath,
      token: user?.token,
      selection: override ? null : editorSelection,
      fromRetry: Boolean(override),
      onSelectionUsed: props.onClearEditorSelection,
    });

  const commands = buildSlashCommands({
    editLocked: isFree,
    editLockedMessage,
    clearDisabled: apply.applyBusy,
    canLogout: !managedProvider,
    setAction,
    clearChat: () => {
      run.abandonRun();
      chat.commitEntries(() => []);
      if (projectId) clearStoredChatEntries(projectId);
    },
    logout: () => {
      setStoredApiKey("");
      setConfigured(false);
    },
  });

  const review: ReviewHandlers = {
    onApply: (entry, groupId) => void apply.applyGroup(entry.id, entry.sessionId, groupId),
    onSkip: (entry, groupId) => apply.skipGroup(entry.id, groupId),
    onApplyAll: (entry) => void apply.applyAllPending(entry.id, entry.sessionId),
    onCancelApplyAll: (entry) => apply.cancelApplyAll(entry.id),
    applyBusy: apply.applyBusy,
    applyBlockedReason: applyBlock?.message ?? null,
    showInCodeViewFor: (entry) =>
      props.onShowInCodeView && (!entry.projectId || entry.projectId === projectId) ? props.onShowInCodeView : undefined,
  };

  const composerDisabled = run.busy || !projectId || !ready || recoveryLocked;

  return (
    <div className="flex h-full flex-col" style={{ backgroundColor: "var(--color-background)" }}>
      <PanelHeader documentPath={documentPath} onClose={props.onClose} />
      {recoveryLocked && props.recoveryShownByHost && (
        <div role="status" className="mx-4 mt-3 px-3 py-2 text-xs text-amber-900 bg-amber-50 border border-amber-200 rounded-md">
          Changes are paused until the project is checked. See the notice above the code.
        </div>
      )}
      {recoveryLocked && !props.recoveryShownByHost && (
        <CodeAssistantRecoveryBanner
          state={recovery.recoveryState}
          busy={recovery.recoveryBusy}
          error={recovery.recoveryError}
          onRestore={() => void recovery.restoreProject()}
          onClear={() => void recovery.unlockProject()}
        />
      )}
      <CodeAssistantTranscript
        entries={chat.entries}
        ready={ready}
        action={action}
        onSelectAction={setAction}
        editLocked={isFree}
        editLockedMessage={editLockedMessage}
        busy={run.busy}
        statusText={run.statusText}
        now={chat.now}
        review={review}
        onResubmit={(retry) => retry && submitMessage(retry)}
        onSignIn={() => logout(true)}
        onCancel={run.cancelRun}
      />
      <div className="border-t border-gray-200 px-4 py-3 flex-shrink-0 space-y-2">
        {chat.entries.length > 0 && (
          <CodeAssistantActionChips action={action} onSelect={setAction} editLocked={isFree} editLockedMessage={editLockedMessage} compact />
        )}
        {!projectId && (
          <div className="px-3 py-1.5 bg-amber-50 border border-amber-200 rounded-md text-amber-900 text-xs">
            Open a project first — the assistant needs one to work against.
          </div>
        )}
        {editorSelection && <SelectionChip selection={editorSelection} onClear={props.onClearEditorSelection} />}
        <CodeAssistantComposer
          input={input}
          setInput={setInput}
          commands={commands}
          disabled={composerDisabled}
          placeholder={composerPlaceholder(recoveryLocked, ready, action)}
          onSubmit={() => submitMessage()}
        />
        {managedProvider ? (
          <p className="text-xs text-gray-500" data-managed-provider>
            Managed by your organization · {managedProvider.provider} · {managedProvider.model}
          </p>
        ) : (
          <CodeAssistantModelSwitcher onChange={() => setConfigured(hasApiKey())} />
        )}
      </div>
    </div>
  );
};

export default CodeAssistantPanel;
