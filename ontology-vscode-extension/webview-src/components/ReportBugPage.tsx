import React from "react";
import { IssueReportForm } from "./IssueReportForm";
import type { FeedbackContext } from "../utils/feedback";

interface Props extends FeedbackContext {
  onBack: () => void;
  onSwitch: () => void;
}

export const ReportBugPage: React.FC<Props> = ({ onBack, onSwitch, ...context }) => (
  <IssueReportForm kind="bug" onBack={onBack} onSwitchKind={onSwitch} {...context} />
);

export default ReportBugPage;
