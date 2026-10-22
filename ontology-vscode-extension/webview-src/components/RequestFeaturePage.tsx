import React from "react";
import { IssueReportForm } from "./IssueReportForm";
import type { FeedbackContext } from "../utils/feedback";

interface Props extends FeedbackContext {
  onBack: () => void;
  onSwitch: () => void;
}

export const RequestFeaturePage: React.FC<Props> = ({ onBack, onSwitch, ...context }) => (
  <IssueReportForm kind="feature" onBack={onBack} onSwitchKind={onSwitch} {...context} />
);

export default RequestFeaturePage;
