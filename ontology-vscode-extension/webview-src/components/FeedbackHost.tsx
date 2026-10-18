import React, { Suspense, lazy, useEffect, useState } from "react";
import {
  FEEDBACK_EVENT,
  FeedbackRequest,
  feedbackTypeFromLocation,
  leaveStandalonePage,
} from "../utils/feedback";

const ReportBugPage = lazy(() => import("./ReportBugPage"));
const RequestFeaturePage = lazy(() => import("./RequestFeaturePage"));

export const FeedbackHost: React.FC = () => {
  const [request, setRequest] = useState<FeedbackRequest | null>(() => {
    const type = feedbackTypeFromLocation();
    return type ? { type } : null;
  });

  useEffect(() => {
    const handler = (event: Event) => setRequest((event as CustomEvent<FeedbackRequest>).detail);
    window.addEventListener(FEEDBACK_EVENT, handler);
    return () => window.removeEventListener(FEEDBACK_EVENT, handler);
  }, []);

  if (!request) {return null;}

  const { type, ...context } = request;
  const close = () => {
    if (feedbackTypeFromLocation()) {leaveStandalonePage();}
    setRequest(null);
  };
  const switchKind = () => setRequest({ ...context, type: type === "bug" ? "feature" : "bug" });
  const Page = type === "bug" ? ReportBugPage : RequestFeaturePage;

  return (
    <div className="fixed inset-0 z-[100] bg-white">
      <Suspense fallback={<div className="min-h-screen bg-white" />}>
        <Page {...context} onBack={close} onSwitch={switchKind} />
      </Suspense>
    </div>
  );
};

export default FeedbackHost;
