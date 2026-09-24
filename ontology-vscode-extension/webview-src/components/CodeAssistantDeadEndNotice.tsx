import React from "react";
import { AlertCircle, RotateCcw, LogIn } from "lucide-react";
import { retryButtonLabel, secondsUntil, type DeadEnd } from "../services/codeAssistantDeadEnd";

interface CodeAssistantDeadEndNoticeProps {
  deadEnd: DeadEnd;
  active: boolean;
  retryAt: number | null;
  now: number;
  onResubmit: () => void;
  onSignIn: () => void;
}

const buttonClass =
  "mt-2 px-3 py-1.5 text-xs font-semibold text-white bg-purple-600 rounded-md hover:bg-purple-700 disabled:opacity-50 flex items-center gap-1.5";

export const CodeAssistantDeadEndNotice: React.FC<CodeAssistantDeadEndNoticeProps> = ({
  deadEnd,
  active,
  retryAt,
  now,
  onResubmit,
  onSignIn,
}) => {
  const { action } = deadEnd;
  let control: React.ReactNode = null;
  if (action.kind === "resubmit") {
    control = (
      <button onClick={onResubmit} disabled={!active} className={buttonClass}>
        <RotateCcw size={12} />
        {action.label}
      </button>
    );
  } else if (action.kind === "retry-after") {
    const secondsLeft = retryAt === null ? 0 : secondsUntil(retryAt, now);
    control = (
      <button onClick={onResubmit} disabled={!active || secondsLeft > 0} className={buttonClass}>
        <RotateCcw size={12} />
        {retryButtonLabel(secondsLeft)}
      </button>
    );
  } else if (action.kind === "sign-in") {
    control = (
      <button onClick={onSignIn} disabled={!active} className={buttonClass}>
        <LogIn size={12} />
        {action.label}
      </button>
    );
  }

  return (
    <div className="px-3 py-2 bg-red-50 border border-red-200 rounded-lg text-red-900 text-sm chat-message-enter" data-dead-end={deadEnd.code}>
      <div className="flex items-start gap-2">
        <AlertCircle size={16} className="flex-shrink-0 mt-0.5" />
        <span>{deadEnd.message}</span>
      </div>
      {control}
    </div>
  );
};

export default CodeAssistantDeadEndNotice;
