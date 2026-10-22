import React from "react";
import { AlertCircle, Bug, CheckCircle, Flag, ListOrdered } from "lucide-react";
import type { SubmitResult } from "../utils/issueSubmit";
import type { FeedbackType } from "../utils/feedback";

const PRIORITY_COLORS: Record<string, string> = {
  Highest: "bg-red-100 text-red-800 border-red-300",
  High: "bg-orange-100 text-orange-800 border-orange-300",
  Medium: "bg-yellow-100 text-yellow-800 border-yellow-300",
  Low: "bg-blue-100 text-blue-800 border-blue-300",
  Lowest: "bg-gray-100 text-gray-800 border-gray-300",
};

interface Props {
  result: SubmitResult;
  kind: FeedbackType;
  priority: string;
}

export const IssueSubmitBanner: React.FC<Props> = ({ result, kind, priority }) => {
  const partial = result.success && !!result.trackerFailureReason;
  const tone = partial
    ? { box: "from-amber-50 to-orange-50 border-amber-300", icon: "bg-amber-100", text: "text-amber-900" }
    : result.success
      ? { box: "from-green-50 to-emerald-50 border-green-300", icon: "bg-green-100", text: "text-green-900" }
      : { box: "from-red-50 to-pink-50 border-red-300", icon: "bg-red-100", text: "text-red-900" };
  const Icon = result.success && !partial ? CheckCircle : AlertCircle;
  const TypeIcon = kind === "feature" ? ListOrdered : Bug;

  return (
    <div role="alert" className={`mx-6 mt-6 p-5 rounded-lg flex items-start gap-4 shadow-md bg-gradient-to-r border-2 ${tone.box}`}>
      <div className={`flex-shrink-0 p-2 rounded-full ${tone.icon}`}>
        <Icon className={tone.text} size={24} />
      </div>
      <div className="flex-1">
        <p className={`text-base font-semibold mb-2 ${tone.text}`}>{result.message}</p>
        {result.trackerFailureReason && (
          <p className="text-sm text-amber-900 bg-amber-100 border border-amber-200 rounded-md px-3 py-2">
            OpenProject sync failed: {result.trackerFailureReason}
          </p>
        )}
        {result.success && (
          <div className="flex items-center gap-3 mt-3">
            <span className={`inline-flex items-center gap-1 px-3 py-1 rounded-full text-xs font-semibold border ${kind === "feature" ? "bg-blue-100 text-blue-800 border-blue-300" : "bg-red-100 text-red-800 border-red-300"}`}>
              <TypeIcon size={14} />
              {kind === "feature" ? "Feature Request" : "Bug"}
            </span>
            {kind === "bug" && (
              <span className={`inline-flex items-center gap-1 px-3 py-1 rounded-full text-xs font-semibold border ${PRIORITY_COLORS[priority] ?? PRIORITY_COLORS.Lowest}`}>
                <Flag size={14} />
                {priority}
              </span>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default IssueSubmitBanner;
