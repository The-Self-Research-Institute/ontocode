export type FeedbackType = "bug" | "feature";

export interface FeedbackContext {
  projectName?: string;
  projectId?: string;
  ontologyFilePath?: string;
}

export interface FeedbackRequest extends FeedbackContext {
  type: FeedbackType;
}

export const FEEDBACK_EVENT = "navigate-feedback";

const FEEDBACK_PATHS: Record<string, FeedbackType> = {
  "report-bug": "bug",
  "request-feature": "feature",
};

export const openFeedbackPage = (type: FeedbackType, context: FeedbackContext = {}) => {
  window.dispatchEvent(new CustomEvent<FeedbackRequest>(FEEDBACK_EVENT, { detail: { type, ...context } }));
};

const currentRoutePart = (): string => {
  const hash = window.location.hash.startsWith("#/") ? window.location.hash.substring(2) : "";
  const source = hash || window.location.pathname;
  return source.split("?")[0].split("/").filter(Boolean)[0] || "";
};

export const feedbackTypeFromLocation = (): FeedbackType | null => FEEDBACK_PATHS[currentRoutePart()] ?? null;

export const isDownloadLocation = (): boolean => currentRoutePart() === "download";

export const leaveStandalonePage = () => {
  const hashRouted = window.location.hash.startsWith("#/") || window.location.protocol !== "https:" && window.location.protocol !== "http:";
  if (hashRouted) {
    window.location.hash = "";
  } else {
    window.history.replaceState(null, "", "/");
  }
};
