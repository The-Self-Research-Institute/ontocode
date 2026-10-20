import React, { Suspense, lazy, useEffect, useState } from "react";
import {
  FEEDBACK_EVENT,
  FeedbackRequest,
  closeFeedbackPage,
  feedbackRequestFromLocation,
  replaceFeedbackPage,
} from "../utils/feedback";

const ReportBugPage = lazy(() => import("./ReportBugPage"));
const RequestFeaturePage = lazy(() => import("./RequestFeaturePage"));

export const FeedbackHost: React.FC = () => {
  const [request, setRequest] = useState<FeedbackRequest | null>(() => feedbackRequestFromLocation());

  useEffect(() => {
    const fromLocation = () => setRequest(feedbackRequestFromLocation());
    const fromEvent = (event: Event) => setRequest((event as CustomEvent<FeedbackRequest>).detail);
    window.addEventListener(FEEDBACK_EVENT, fromEvent);
    window.addEventListener("popstate", fromLocation);
    window.addEventListener("hashchange", fromLocation);
    return () => {
      window.removeEventListener(FEEDBACK_EVENT, fromEvent);
      window.removeEventListener("popstate", fromLocation);
      window.removeEventListener("hashchange", fromLocation);
    };
  }, []);

  useEffect(() => {
    if (!request) {return undefined;}
    const previous = document.title;
    document.title = request.type === "bug" ? "Report a Bug" : "Request a Feature";
    return () => {
      document.title = previous;
    };
  }, [request?.type]);

  if (!request) {return null;}

  const { type, ...context } = request;
  const close = () => {
    closeFeedbackPage();
    setRequest(null);
  };
  const switchKind = () => {
    const next = type === "bug" ? "feature" : "bug";
    replaceFeedbackPage(next, context);
    setRequest({ ...context, type: next });
  };
  const Page = type === "bug" ? ReportBugPage : RequestFeaturePage;

  return (
    <div className="fixed inset-0 z-[100] overflow-y-auto bg-gray-50">
      <Suspense fallback={<div className="min-h-screen bg-gray-50" />}>
        <Page {...context} onBack={close} onSwitch={switchKind} />
      </Suspense>
    </div>
  );
};

export default FeedbackHost;
