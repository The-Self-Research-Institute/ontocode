import React from "react";
import { Lock } from "lucide-react";
import { ACTIONS, type CodeAssistantAction } from "./codeAssistantPanelHelpers";

interface CodeAssistantActionChipsProps {
  action: CodeAssistantAction;
  onSelect: (action: CodeAssistantAction) => void;
  editLocked: boolean;
  editLockedMessage: string;
  compact?: boolean;
}

export const CodeAssistantActionChips: React.FC<CodeAssistantActionChipsProps> = ({
  action,
  onSelect,
  editLocked,
  editLockedMessage,
  compact = false,
}) => {
  const iconSize = compact ? 10 : 12;
  const sizing = compact ? "px-2 py-1 text-[11px] gap-1" : "px-3 py-1.5 text-xs gap-1.5";
  const idleText = compact ? "text-gray-600" : "text-gray-700";
  return (
    <div className={`flex flex-wrap ${compact ? "gap-1.5" : "gap-2"}`}>
      {ACTIONS.map(({ id, label, icon: Icon }, index) => {
        const planLocked = id === "local-edit" && editLocked;
        return (
          <button
            key={id}
            onClick={() => onSelect(id)}
            disabled={planLocked}
            title={planLocked ? editLockedMessage : undefined}
            style={{ animationDelay: `${index * 70}ms` }}
            className={`chat-chip-enter ${sizing} font-semibold rounded-full border flex items-center disabled:opacity-50 ${
              action === id
                ? "bg-purple-600 border-purple-600 text-white"
                : `bg-white border-gray-300 ${idleText} hover:border-purple-400`
            }`}
          >
            {planLocked ? <Lock size={iconSize} /> : <Icon size={iconSize} />}
            {label}
          </button>
        );
      })}
    </div>
  );
};
