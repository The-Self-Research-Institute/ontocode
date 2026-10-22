import React from "react";
import { ArrowLeft, Bug, ListOrdered, Mail } from "lucide-react";
import type { FeedbackType } from "../utils/feedback";

export const INPUT_CLASS =
  "w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400 transition-all";
export const LABEL_CLASS = "flex items-center gap-2 text-sm font-semibold text-gray-800";

export const PRIORITIES = [
  { value: "Highest", label: "🔴 Highest" },
  { value: "High", label: "🟠 High" },
  { value: "Medium", label: "🟡 Medium" },
  { value: "Low", label: "🔵 Low" },
  { value: "Lowest", label: "⚪ Lowest" },
];

const tabClass = (active: boolean) =>
  `px-4 py-2 text-sm font-semibold rounded-md transition-colors ${
    active ? "bg-purple-600 text-white shadow" : "text-gray-600 hover:text-gray-900 hover:bg-gray-100"
  }`;

interface HeaderProps {
  kind: FeedbackType;
  projectName?: string;
  busy: boolean;
  onBack: () => void;
  onSwitchKind: () => void;
}

export const IssueFormHeader: React.FC<HeaderProps> = ({ kind, projectName, busy, onBack, onSwitchKind }) => {
  const isFeature = kind === "feature";
  const Icon = isFeature ? ListOrdered : Bug;
  return (
    <header className="sticky top-0 z-10 bg-white border-b border-gray-200">
      <div className="w-full max-w-6xl mx-auto px-6 py-3 flex flex-wrap items-center gap-4">
        <button
          onClick={onBack}
          disabled={busy}
          className="flex items-center gap-2 text-sm font-semibold text-gray-700 hover:text-purple-700 disabled:opacity-50"
        >
          <ArrowLeft size={18} />
          Back to app
        </button>
        <div className="flex items-center gap-3 min-w-0">
          <div className="bg-gradient-to-br from-purple-600 to-indigo-600 p-2 rounded-lg">
            <Icon className="text-white" size={20} />
          </div>
          <div className="min-w-0">
            <h1 className="text-lg font-bold text-gray-900 leading-tight">{isFeature ? "Request a Feature" : "Report a Bug"}</h1>
            {projectName && <p className="text-xs text-gray-500 truncate">Project: {projectName}</p>}
          </div>
        </div>
        <div className="ml-auto flex items-center gap-1 rounded-lg border border-gray-200 bg-gray-50 p-1" role="tablist">
          <button role="tab" aria-selected={!isFeature} disabled={busy} onClick={isFeature ? onSwitchKind : undefined} className={tabClass(!isFeature)}>
            Report a Bug
          </button>
          <button role="tab" aria-selected={isFeature} disabled={busy} onClick={isFeature ? undefined : onSwitchKind} className={tabClass(isFeature)}>
            Request a Feature
          </button>
        </div>
      </div>
    </header>
  );
};

interface SidebarProps {
  kind: FeedbackType;
  projectName?: string;
  ontologyFilePath?: string;
  children?: React.ReactNode;
}

export const IssueSidebar: React.FC<SidebarProps> = ({ kind, projectName, ontologyFilePath, children }) => {
  const tips =
    kind === "feature"
      ? ["Describe the problem you are trying to solve", "Say who needs it and how often", "Mention anything you do today instead"]
      : ["Say what you expected and what happened", "List the steps so we can repeat it", "Attach a screenshot or the log file if you can"];
  return (
    <aside className="space-y-4 lg:sticky lg:top-24 self-start">
      <div className="rounded-xl border border-gray-200 bg-white p-5 space-y-4">
        {children}
        {(projectName || ontologyFilePath) && (
          <dl className="space-y-2 text-sm">
            {projectName && (
              <div>
                <dt className="text-xs uppercase tracking-wide text-gray-500">Project</dt>
                <dd className="font-medium text-gray-800 break-words">{projectName}</dd>
              </div>
            )}
            {ontologyFilePath && (
              <div>
                <dt className="text-xs uppercase tracking-wide text-gray-500">File</dt>
                <dd className="font-medium text-gray-800 break-all">{ontologyFilePath}</dd>
              </div>
            )}
          </dl>
        )}
      </div>
      <div className="rounded-xl border border-purple-200 bg-purple-50 p-5">
        <p className="text-sm font-semibold text-purple-900 mb-2">A good report has</p>
        <ul className="space-y-1.5 text-sm text-purple-900 list-disc pl-5">
          {tips.map((tip) => (
            <li key={tip}>{tip}</li>
          ))}
        </ul>
      </div>
    </aside>
  );
};

interface EmailProps {
  signedIn: boolean;
  signedInEmail?: string;
  email: string;
  error?: string;
  disabled: boolean;
  onChange: (value: string) => void;
  onBlur: () => void;
}

export const IssueEmailField: React.FC<EmailProps> = ({ signedIn, signedInEmail, email, error, disabled, onChange, onBlur }) => {
  if (signedIn) {
    return (
      <p className="text-sm text-gray-600 flex items-center gap-2">
        <Mail size={16} className="text-purple-600 flex-shrink-0" />
        <span className="min-w-0 break-all">
          Reporting as <span className="font-semibold text-gray-800">{signedInEmail}</span>
        </span>
      </p>
    );
  }
  return (
    <div className="space-y-2">
      <label className={LABEL_CLASS}>
        <Mail size={16} className="text-purple-600" />
        Your Email <span className="text-red-500">*</span>
      </label>
      <input
        type="email"
        value={email}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        placeholder="you@example.com"
        className={`${INPUT_CLASS} ${error ? "border-red-400" : ""}`}
        disabled={disabled}
      />
      <p className={`text-xs ${error ? "text-red-500 font-medium" : "text-gray-500"}`}>
        {error || "Required. You are not signed in, so this is how we can reply to you."}
      </p>
    </div>
  );
};

interface FooterProps {
  kind: FeedbackType;
  submitting: boolean;
  blockReason?: string;
  onBack: () => void;
  onSubmit: () => void;
}

export const IssueFormFooter: React.FC<FooterProps> = ({ kind, submitting, blockReason, onBack, onSubmit }) => {
  const isFeature = kind === "feature";
  const Icon = isFeature ? ListOrdered : Bug;
  return (
    <footer className="sticky bottom-0 z-10 border-t border-gray-200 bg-white">
      <div className="w-full max-w-6xl mx-auto px-6 py-4 flex flex-wrap justify-between items-center gap-3">
        <p className="text-xs text-gray-600">
          <span className="text-red-500">*</span> Required fields
          {blockReason && <span className="ml-3 text-gray-500">{blockReason}</span>}
        </p>
        <div className="flex gap-3">
          <button
            onClick={onBack}
            disabled={submitting}
            className="px-6 py-2.5 text-sm font-semibold text-gray-700 bg-white border-2 border-gray-300 rounded-lg hover:bg-gray-50 hover:border-gray-400 disabled:opacity-50 transition-all"
          >
            Cancel
          </button>
          <button
            onClick={onSubmit}
            disabled={submitting || !!blockReason}
            title={blockReason}
            className="px-6 py-2.5 text-sm font-semibold text-white bg-gradient-to-r from-purple-600 to-indigo-600 rounded-lg hover:from-purple-700 hover:to-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 shadow-lg hover:shadow-xl transition-all"
          >
            {submitting ? (
              <>
                <div className="animate-spin rounded-full h-4 w-4 border-2 border-white border-t-transparent"></div>
                <span>Submitting...</span>
              </>
            ) : (
              <>
                <Icon size={18} />
                <span>{isFeature ? "Submit Feature Request" : "Submit Bug Report"}</span>
              </>
            )}
          </button>
        </div>
      </div>
    </footer>
  );
};
