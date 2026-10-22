import { getGatewayUrl, getRemoteApiBaseUrl } from "../config/deploymentConfig";
import { isDesktop } from "./desktop";
import type { FeedbackType } from "./feedback";

export interface SubmitResult {
  success: boolean;
  message: string;
  issueUrl?: string;
  trackerFailureReason?: string;
}

export interface IssuePayload {
  kind: FeedbackType;
  title: string;
  description: string;
  stepsToReproduce: string;
  priority: string;
  email: string | null;
  projectId?: string;
  projectName?: string;
  ontologyFilePath?: string;
  attachments: File[];
  token?: string;
}

const getApiBaseUrl = () => (isDesktop() ? getRemoteApiBaseUrl() : getGatewayUrl());

const getSystemInfo = () => {
  const ua = navigator.userAgent;
  let osName = navigator.platform;
  if (ua.indexOf("Win") !== -1) {osName = "Windows";}
  else if (ua.indexOf("Mac") !== -1) {osName = "macOS";}
  else if (ua.indexOf("Linux") !== -1) {osName = "Linux";}
  const vscodeMatch = ua.match(/Code\/([\d.]+)/);
  return {
    osName,
    osVersion: navigator.platform,
    vsCodeVersion: vscodeMatch ? vscodeMatch[1] : (window as any).vscodeVersion || "Unknown",
    extensionVersion: (window as any).extensionVersion || "Unknown",
  };
};

export const submitIssueReport = async (payload: IssuePayload): Promise<SubmitResult> => {
  const isFeature = payload.kind === "feature";
  const formData = new FormData();
  formData.append("title", payload.title);
  formData.append("description", payload.description);
  formData.append("issueType", isFeature ? "Task" : "Bug");
  if (!isFeature) {
    formData.append("priority", payload.priority);
    if (payload.stepsToReproduce.trim()) {formData.append("stepsToReproduce", payload.stepsToReproduce);}
  }
  if (payload.email) {formData.append("userEmail", payload.email);}
  if (payload.projectId) {formData.append("projectId", payload.projectId);}
  if (payload.projectName) {formData.append("projectName", payload.projectName);}
  if (payload.ontologyFilePath) {formData.append("ontologyFilePath", payload.ontologyFilePath);}
  Object.entries(getSystemInfo()).forEach(([key, value]) => formData.append(key, value));
  payload.attachments.forEach((file) => formData.append("attachments", file));

  try {
    const response = await fetch(`${getApiBaseUrl()}/api/v1/issues/report`, {
      method: "POST",
      body: formData,
      headers: payload.token ? { Authorization: `Bearer ${payload.token}` } : {},
    });
    const result = await response.json();
    if (result.success) {
      return {
        success: true,
        message: isFeature ? "Feature request submitted successfully!" : "Bug reported successfully!",
        issueUrl: result.issueUrl,
        trackerFailureReason: result.trackerFailureReason || undefined,
      };
    }
    return {
      success: false,
      message: result.message || (isFeature ? "Failed to submit feature request" : "Failed to submit issue report"),
    };
  } catch (error) {
    console.error("Failed to submit issue report:", error);
    return { success: false, message: "Network error: Failed to submit issue report. Please try again." };
  }
};
