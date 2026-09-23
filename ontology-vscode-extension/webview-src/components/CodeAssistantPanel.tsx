import React, { useState } from "react";
import { Bot, X, MessageSquare, Pencil, Search, Settings } from "lucide-react";
import { hasApiKey } from "../services/LlmInsightsService";
import LLMSettingsPanel from "./LLMSettingsPanel";

export type CodeAssistantAction = "ask" | "local-edit" | "project-findings";

interface CodeAssistantPanelProps {
  projectId?: string;
  projectName?: string;
  documentPath?: string;
  onClose: () => void;
}

type Phase = "action-picker" | "configure-provider" | "not-available";

const ACTIONS: Array<{ id: CodeAssistantAction; label: string; description: string; icon: React.ElementType }> = [
  {
    id: "ask",
    label: "Ask",
    description: "Ask a question about this document, grounded in its actual content.",
    icon: MessageSquare,
  },
  {
    id: "local-edit",
    label: "Local edit",
    description: "Propose a change scoped to this document, reviewed before anything is applied.",
    icon: Pencil,
  },
  {
    id: "project-findings",
    label: "Project findings",
    description: "Ask a question grounded in the whole project, not just this document.",
    icon: Search,
  },
];

export const CodeAssistantPanel: React.FC<CodeAssistantPanelProps> = ({
  projectName,
  documentPath,
  onClose,
}) => {
  const [phase, setPhase] = useState<Phase>(hasApiKey() ? "action-picker" : "configure-provider");
  const [selectedAction, setSelectedAction] = useState<CodeAssistantAction | null>(null);

  const chooseAction = (action: CodeAssistantAction) => {
    if (!hasApiKey()) {
      setPhase("configure-provider");
      return;
    }
    setSelectedAction(action);
    setPhase("not-available");
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
            onClick={onClose}
            className="text-white hover:bg-white hover:bg-opacity-20 p-2 rounded-lg transition-colors"
          >
            <X size={22} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-6">
          {phase === "action-picker" && (
            <div className="space-y-3">
              <p className="text-sm text-gray-600">Choose what you want the assistant to do.</p>
              {ACTIONS.map(({ id, label, description, icon: Icon }) => (
                <button
                  key={id}
                  onClick={() => chooseAction(id)}
                  className="w-full text-left px-4 py-3 border-2 border-gray-200 rounded-lg hover:border-purple-400 hover:bg-purple-50 transition-colors flex items-start gap-3"
                >
                  <Icon size={20} className="text-purple-600 flex-shrink-0 mt-0.5" />
                  <div>
                    <div className="font-semibold text-gray-800">{label}</div>
                    <div className="text-xs text-gray-500 mt-0.5">{description}</div>
                  </div>
                </button>
              ))}
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

          {phase === "not-available" && (
            <div className="space-y-3">
              <div className="px-4 py-3 bg-blue-50 border border-blue-200 rounded-lg text-blue-900 text-sm">
                {selectedAction ? ACTIONS.find((a) => a.id === selectedAction)?.label : "This"} is still being
                built. Nothing has been sent anywhere yet.
              </div>
              <button
                onClick={() => {
                  setSelectedAction(null);
                  setPhase("action-picker");
                }}
                className="px-4 py-2 text-sm font-semibold text-purple-700 bg-purple-50 border border-purple-200 rounded-lg hover:bg-purple-100 transition-colors"
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
