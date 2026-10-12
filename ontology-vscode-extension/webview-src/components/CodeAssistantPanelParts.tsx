import React from "react";
import { hasApiKey } from "../services/LlmInsightsService";
import { apiKeyPersistence } from "../services/assistantKeyStore";
import { CodeAssistantModelSwitcher } from "./CodeAssistantModelSwitcher";
import { CodeAssistantRecoveryBanner } from "./CodeAssistantRecoveryBanner";
import type { CodeAssistantPanelController } from "../hooks/useCodeAssistantPanel";

export const RecoveryNotice: React.FC<{ c: CodeAssistantPanelController; shownByHost?: boolean }> = ({ c, shownByHost }) => {
  if (!c.recoveryLocked) return null;
  if (shownByHost) {
    return (
      <div role="status" className="mx-4 mt-3 px-3 py-2 text-xs text-amber-900 bg-amber-50 border border-amber-200 rounded-md">
        Changes are paused until the project is checked. See the notice above the code.
      </div>
    );
  }
  return (
    <CodeAssistantRecoveryBanner
      state={c.recovery.recoveryState}
      busy={c.recovery.recoveryBusy}
      error={c.recovery.recoveryError}
      onRestore={() => void c.recovery.restoreProject()}
      onClear={() => void c.recovery.unlockProject()}
    />
  );
};

export const ProviderFooter: React.FC<{ c: CodeAssistantPanelController }> = ({ c }) =>
  c.managedProvider ? (
    <p className="text-xs text-gray-500" data-managed-provider>
      Managed by your organization · {c.managedProvider.provider} · {c.managedProvider.model}
    </p>
  ) : (
    <>
      <CodeAssistantModelSwitcher onChange={() => c.setConfigured(hasApiKey())} />
      {c.configured && apiKeyPersistence() === "session" && (
        <p className="text-xs text-gray-500" data-session-key-notice>
          Your API key is kept for this session only. You'll need to enter it again after closing.
        </p>
      )}
    </>
  );
