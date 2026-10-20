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

const PATH_OF: Record<FeedbackType, string> = {
  bug: "report-bug",
  feature: "request-feature",
};

const QUERY_OF: Record<keyof FeedbackContext, string> = {
  projectName: "project",
  projectId: "projectId",
  ontologyFilePath: "file",
};

const isHashRouted = (): boolean =>
  window.location.hash.startsWith("#/") ||
  (window.location.protocol !== "https:" && window.location.protocol !== "http:");

const queryOf = (context: FeedbackContext): string => {
  const params = new URLSearchParams();
  (Object.keys(QUERY_OF) as Array<keyof FeedbackContext>).forEach((key) => {
    const value = context[key];
    if (value) {params.set(QUERY_OF[key], value);}
  });
  const text = params.toString();
  return text ? `?${text}` : "";
};

export const feedbackRoute = (type: FeedbackType, context: FeedbackContext = {}): string =>
  `${PATH_OF[type]}${queryOf(context)}`;

export const openFeedbackPage = (type: FeedbackType, context: FeedbackContext = {}) => {
  const route = feedbackRoute(type, context);
  if (isHashRouted()) {
    window.location.hash = `#/${route}`;
  } else {
    window.history.pushState({ feedback: true }, "", `/${route}`);
  }
  window.dispatchEvent(new CustomEvent<FeedbackRequest>(FEEDBACK_EVENT, { detail: { type, ...context } }));
};

export const replaceFeedbackPage = (type: FeedbackType, context: FeedbackContext = {}) => {
  const route = feedbackRoute(type, context);
  if (isHashRouted()) {
    window.history.replaceState(window.history.state, "", `#/${route}`);
  } else {
    window.history.replaceState({ feedback: true }, "", `/${route}`);
  }
};

const currentLocation = (): { part: string; search: string } => {
  const hash = window.location.hash.startsWith("#/") ? window.location.hash.substring(2) : "";
  const source = hash || window.location.pathname + window.location.search;
  const [path, search = ""] = source.split("?");
  return { part: path.split("/").filter(Boolean)[0] || "", search };
};

export const feedbackTypeFromLocation = (): FeedbackType | null => FEEDBACK_PATHS[currentLocation().part] ?? null;

export const feedbackRequestFromLocation = (): FeedbackRequest | null => {
  const type = feedbackTypeFromLocation();
  if (!type) {return null;}
  const params = new URLSearchParams(currentLocation().search);
  const request: FeedbackRequest = { type };
  (Object.keys(QUERY_OF) as Array<keyof FeedbackContext>).forEach((key) => {
    const value = params.get(QUERY_OF[key]);
    if (value) {request[key] = value;}
  });
  return request;
};

export const isDownloadLocation = (): boolean => currentLocation().part === "download";

export const leaveStandalonePage = () => {
  if (isHashRouted()) {
    window.location.hash = "";
  } else {
    window.history.replaceState(null, "", "/");
  }
};

export const closeFeedbackPage = () => {
  if (window.history.state?.feedback && window.history.length > 1) {
    window.history.back();
  } else {
    leaveStandalonePage();
  }
};
