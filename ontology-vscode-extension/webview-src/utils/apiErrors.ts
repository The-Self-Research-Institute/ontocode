import { ApiError } from "../services/apiClient";

/** User-facing message for timeouts and proxy errors (avoids misleading “CORS” confusion). */
export function friendlyApiErrorMessage(err: unknown, context?: string): string {
  const prefix = context ? `${context}: ` : "";
  if (err instanceof ApiError) {
    if (err.status === 504 || err.status === 502 || err.status === 503) {
      return `${prefix}The server is busy or this request took too long. Wait a moment and try again.`;
    }
    if (err.status === 408 || err.code === "TIMEOUT" || err.code === "ECONNABORTED") {
      return `${prefix}This query is taking longer than expected on a large ontology. Try again in a few seconds.`;
    }
    if (err.status === 0 || err.code === "ERR_NETWORK") {
      return `${prefix}Network connection lost. Check your connection and retry.`;
    }
    if (err.message) return `${prefix}${err.message}`;
  }
  if (err instanceof Error && err.message) {
    if (/network|timeout|cors/i.test(err.message)) {
      return `${prefix}Request timed out or the server is busy. Please retry.`;
    }
    return `${prefix}${err.message}`;
  }
  return `${prefix}Something went wrong. Please try again.`;
}
