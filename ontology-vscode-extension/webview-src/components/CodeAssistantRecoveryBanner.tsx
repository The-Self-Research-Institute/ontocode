import React, { useState } from "react";
import { ShieldAlert, Loader2 } from "lucide-react";
import { describeLockedAt, type RecoveryState } from "../services/codeAssistantRecovery";

interface CodeAssistantRecoveryBannerProps {
  state: RecoveryState;
  busy: boolean;
  error: string | null;
  onRestore: () => void;
  onClear: () => void;
}

type Confirming = "restore" | "clear" | null;

const primaryButton =
  "px-3 py-1.5 text-xs font-semibold text-white bg-red-700 rounded-md hover:bg-red-800 disabled:opacity-50";
const secondaryButton =
  "px-3 py-1.5 text-xs font-semibold text-red-900 bg-white border border-red-300 rounded-md hover:bg-red-100 disabled:opacity-50";

const RecoveryIntro: React.FC<{ state: RecoveryState; lockedAt: string | null }> = ({ state, lockedAt }) => (
  <div className="flex items-start gap-2">
    <ShieldAlert size={16} className="flex-shrink-0 mt-0.5" />
    <div className="space-y-1">
      <p className="font-semibold text-sm">This project may be inconsistent</p>
      <p>
        A change didn&apos;t finish cleanly, so part of it may have been saved and part not. Asking and applying are paused
        until you restore the previous version or confirm the project looks right.
      </p>
      {state.reason && <p className="text-red-800">Reason: {state.reason}</p>}
      {lockedAt && <p className="text-red-800">Since: {lockedAt}</p>}
    </div>
  </div>
);

const RecoveryChoices: React.FC<{ canRestore: boolean; busy: boolean; setConfirming: (value: Confirming) => void }> = ({
  canRestore,
  busy,
  setConfirming,
}) => (
  <div className="flex flex-wrap gap-2">
    {canRestore && (
      <button onClick={() => setConfirming("restore")} disabled={busy} className={primaryButton}>
        Restore previous version
      </button>
    )}
    <button onClick={() => setConfirming("clear")} disabled={busy} className={secondaryButton}>
      I&apos;ve checked it — unlock
    </button>
    {busy && <Loader2 size={16} className="animate-spin self-center" />}
  </div>
);

const ConfirmStep: React.FC<{
  prompt: React.ReactNode;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}> = ({ prompt, confirmLabel, busy, onConfirm, onCancel }) => (
  <div className="space-y-2">
    <p>{prompt}</p>
    <div className="flex gap-2">
      <button onClick={onConfirm} disabled={busy} className={primaryButton}>
        {confirmLabel}
      </button>
      <button onClick={onCancel} className={secondaryButton}>
        Cancel
      </button>
    </div>
  </div>
);

export const CodeAssistantRecoveryBanner: React.FC<CodeAssistantRecoveryBannerProps> = ({ state, busy, error, onRestore, onClear }) => {
  const [confirming, setConfirming] = useState<Confirming>(null);
  const lockedAt = describeLockedAt(state.lockedAt);

  const confirm = () => {
    const choice = confirming;
    setConfirming(null);
    if (choice === "restore") onRestore();
    if (choice === "clear") onClear();
  };
  const cancel = () => setConfirming(null);

  return (
    <div role="alert" className="mx-4 mt-3 px-3 py-3 bg-red-50 border border-red-300 rounded-lg text-red-900 text-xs space-y-2" data-recovery-banner>
      <RecoveryIntro state={state} lockedAt={lockedAt} />
      {error && <p className="font-semibold">{error}</p>}
      {confirming === null && <RecoveryChoices canRestore={state.canRestore} busy={busy} setConfirming={setConfirming} />}
      {confirming === "restore" && (
        <ConfirmStep
          prompt="Restore the version from before the unfinished change? Anything changed since then will be replaced."
          confirmLabel="Yes, restore it"
          busy={busy}
          onConfirm={confirm}
          onCancel={cancel}
        />
      )}
      {confirming === "clear" && (
        <ConfirmStep
          prompt={<>Unlock without restoring? Only do this if you&apos;ve checked the project and it looks right.</>}
          confirmLabel="Yes, unlock"
          busy={busy}
          onConfirm={confirm}
          onCancel={cancel}
        />
      )}
    </div>
  );
};

export default CodeAssistantRecoveryBanner;
