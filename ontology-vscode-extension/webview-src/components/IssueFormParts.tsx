import React from "react";
import { Bug, ListOrdered, Mail, X } from "lucide-react";
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
    <div className="bg-gradient-to-r from-purple-600 to-indigo-600 px-6 py-5 flex items-center justify-between">
      <div className="flex items-center gap-3">
        <div className="bg-white bg-opacity-20 p-2 rounded-lg">
          <Icon className="text-white" size={28} />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-white">{isFeature ? "Request a Feature" : "Report a Bug"}</h1>
          {projectName && <p className="text-purple-100 text-sm mt-1">Project: {projectName}</p>}
        </div>
      </div>
      <div className="flex items-center gap-3">
        <button
          onClick={onSwitchKind}
          disabled={busy}
          className="hidden sm:inline text-sm text-purple-100 hover:text-white underline underline-offset-2"
        >
          {isFeature ? "Found a bug instead?" : "Want a feature instead?"}
        </button>
        <button
          onClick={onBack}
          disabled={busy}
          title="Back"
          aria-label="Back"
          className="text-white hover:bg-white hover:bg-opacity-20 p-2 rounded-lg transition-colors"
        >
          <X size={24} />
        </button>
      </div>
    </div>
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
        <Mail size={16} className="text-purple-600" />
        Reporting as <span className="font-semibold text-gray-800">{signedInEmail}</span>
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
    <div className="border-t-2 border-gray-200 bg-gray-50 px-6 py-5 flex justify-between items-center">
      <p className="text-xs text-gray-600">
        <span className="text-red-500">*</span> Required fields
      </p>
      <div className="flex gap-3">
        <button
          onClick={onBack}
          disabled={submitting}
          className="px-6 py-2.5 text-sm font-semibold text-gray-700 bg-white border-2 border-gray-300 rounded-lg hover:bg-gray-50 hover:border-gray-400 disabled:opacity-50 transition-all"
        >
          Back
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
  );
};
