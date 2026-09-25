import React from "react";
import { X } from "lucide-react";
import { AskAiIcon } from "./AskAiIcon";
import { setStoredApiKey } from "../services/LlmInsightsService";
import { ProviderFooter, RecoveryNotice } from "./CodeAssistantPanelParts";
import { CodeAssistantActionChips } from "./CodeAssistantActionChips";
import { CodeAssistantTranscript } from "./CodeAssistantTranscript";
import { CodeAssistantComposer, buildSlashCommands } from "./CodeAssistantComposer";
import type { ReviewHandlers } from "./CodeAssistantTranscriptEntry";
import { useCodeAssistantPanel, type CodeAssistantPanelController } from "../hooks/useCodeAssistantPanel";
import type { AppliedRange } from "../services/codeAssistantSession";
import { clearStoredChatEntries, type CodeAssistantAction } from "./codeAssistantPanelHelpers";
import type { EditorSelectionContext } from "./codeSelection";
import type { PromptToRetry } from "./codeAssistantChatEntries";
import { SelectionChip } from "./CodeAssistantSelectionChip";

export type { CodeAssistantAction };

export interface CodeAssistantPanelProps {
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

type PanelController = CodeAssistantPanelController;

function buildPanelCommands(c: PanelController, projectId: string | undefined) {
  return buildSlashCommands({
    editLocked: c.isFree,
    editLockedMessage: c.editLockedMessage,
    clearDisabled: c.apply.applyBusy,
    canLogout: !c.managedProvider,
    setAction: c.setAction,
    clearChat: () => {
      c.run.abandonRun();
      c.chat.commitEntries(() => []);
      if (projectId) clearStoredChatEntries(projectId);
    },
    logout: () => {
      setStoredApiKey("");
      c.setConfigured(false);
    },
  });
}

function buildReviewHandlers(c: PanelController, props: CodeAssistantPanelProps): ReviewHandlers {
  return {
    onApply: (entry, groupId) => void c.apply.applyGroup(entry.id, entry.sessionId, groupId),
    onSkip: (entry, groupId) => c.apply.skipGroup(entry.id, groupId),
    onApplyAll: (entry) => void c.apply.applyAllPending(entry.id, entry.sessionId),
    onCancelApplyAll: (entry) => c.apply.cancelApplyAll(entry.id),
    applyBusy: c.apply.applyBusy,
    applyBlockedReason: c.applyBlock?.message ?? null,
    showInCodeViewFor: (entry) =>
      props.onShowInCodeView && (!entry.projectId || entry.projectId === props.projectId) ? props.onShowInCodeView : undefined,
  };
}

const PanelFooter: React.FC<{
  c: PanelController;
  projectId?: string;
  editorSelection: PanelEditorSelection | null;
  onClearEditorSelection?: () => void;
  onSubmit: () => void;
}> = ({ c, projectId, editorSelection, onClearEditorSelection, onSubmit }) => (
  <div className="border-t border-gray-200 px-4 py-3 flex-shrink-0 space-y-2">
    {c.chat.entries.length > 0 && (
      <CodeAssistantActionChips action={c.action} onSelect={c.setAction} editLocked={c.isFree} editLockedMessage={c.editLockedMessage} compact />
    )}
    {!projectId && (
      <div className="px-3 py-1.5 bg-amber-50 border border-amber-200 rounded-md text-amber-900 text-xs">
        Open a project first — the assistant needs one to work against.
      </div>
    )}
    {editorSelection && <SelectionChip selection={editorSelection} onClear={onClearEditorSelection} />}
    <CodeAssistantComposer
      input={c.input}
      setInput={c.setInput}
      commands={buildPanelCommands(c, projectId)}
      disabled={c.run.busy || !projectId || !c.ready || c.recoveryLocked}
      placeholder={composerPlaceholder(c.recoveryLocked, c.ready, c.action)}
      onSubmit={onSubmit}
    />
    <ProviderFooter c={c} />
  </div>
);

export const CodeAssistantPanel: React.FC<CodeAssistantPanelProps> = (props) => {
  const { projectId, documentPath, editorSelection = null } = props;
  const c = useCodeAssistantPanel(props);

  const submitMessage = (override?: PromptToRetry) =>
    void c.run.submit({
      text: (override?.text ?? c.input).trim(),
      action: override?.action ?? c.action,
      projectId,
      documentPath,
      token: c.user?.token,
      selection: override ? null : editorSelection,
      fromRetry: Boolean(override),
      onSelectionUsed: props.onClearEditorSelection,
    });

  return (
    <div className="flex h-full flex-col" style={{ backgroundColor: "var(--color-background)" }}>
      <PanelHeader documentPath={documentPath} onClose={props.onClose} />
      <RecoveryNotice c={c} shownByHost={props.recoveryShownByHost} />
      <CodeAssistantTranscript
        entries={c.chat.entries}
        ready={c.ready}
        action={c.action}
        onSelectAction={c.setAction}
        editLocked={c.isFree}
        editLockedMessage={c.editLockedMessage}
        busy={c.run.busy}
        statusText={c.run.statusText}
        draft={c.run.draft}
        now={c.chat.now}
        review={buildReviewHandlers(c, props)}
        onResubmit={(retry) => retry && submitMessage(retry)}
        onSignIn={() => c.logout(true)}
        onCancel={c.run.cancelRun}
      />
      <PanelFooter
        c={c}
        projectId={projectId}
        editorSelection={editorSelection}
        onClearEditorSelection={props.onClearEditorSelection}
        onSubmit={() => submitMessage()}
      />
    </div>
  );
};

export default CodeAssistantPanel;
