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

export const CodeAssistantRecoveryBanner: React.FC<CodeAssistantRecoveryBannerProps> = ({ state, busy, error, onRestore, onClear }) => {
  const [confirming, setConfirming] = useState<Confirming>(null);
  const lockedAt = describeLockedAt(state.lockedAt);

  const confirm = () => {
    const choice = confirming;
    setConfirming(null);
    if (choice === "restore") onRestore();
    if (choice === "clear") onClear();
  };

  return (
    <div role="alert" className="mx-4 mt-3 px-3 py-3 bg-red-50 border border-red-300 rounded-lg text-red-900 text-xs space-y-2" data-recovery-banner>
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
      {error && <p className="font-semibold">{error}</p>}
      {confirming === null && (
        <div className="flex flex-wrap gap-2">
          {state.canRestore && (
            <button onClick={() => setConfirming("restore")} disabled={busy} className={primaryButton}>
              Restore previous version
            </button>
          )}
          <button onClick={() => setConfirming("clear")} disabled={busy} className={secondaryButton}>
            I&apos;ve checked it — unlock
          </button>
          {busy && <Loader2 size={16} className="animate-spin self-center" />}
        </div>
      )}
      {confirming === "restore" && (
        <div className="space-y-2">
          <p>Restore the version from before the unfinished change? Anything changed since then will be replaced.</p>
          <div className="flex gap-2">
            <button onClick={confirm} disabled={busy} className={primaryButton}>
              Yes, restore it
            </button>
            <button onClick={() => setConfirming(null)} className={secondaryButton}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {confirming === "clear" && (
        <div className="space-y-2">
          <p>Unlock without restoring? Only do this if you&apos;ve checked the project and it looks right.</p>
          <div className="flex gap-2">
            <button onClick={confirm} disabled={busy} className={primaryButton}>
              Yes, unlock
            </button>
            <button onClick={() => setConfirming(null)} className={secondaryButton}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default CodeAssistantRecoveryBanner;
