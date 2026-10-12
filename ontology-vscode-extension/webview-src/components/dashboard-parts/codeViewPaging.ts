import apiClient from "../../services/apiClient";

export const CODE_VIEW_PAGE_LINES = 10_000;

export const CODE_VIEW_STREAMING_FORMATS = new Set(["turtle", "rdfxml", "ntriples", "jsonld"]);
const CODE_VIEW_OWLAPI_CEILING_BYTES = 10 * 1024 * 1024;
const CODE_VIEW_STREAMING_CEILING_BYTES = 60 * 1024 * 1024;

export type CodeViewFormat = "rdfxml" | "turtle" | "ntriples" | "owlxml" | "manchester" | "functional" | "jsonld";

export interface CodeViewPageWindow {
  startLine: number;
  lineCount: number;
  totalLines: number;
  totalBytes: number;
}

export interface CodeViewPageResponse {
  success: boolean;
  content: string;
  startLine: number;
  lineCount: number;
  totalLines: number;
  totalBytes: number;
  sourceVersion?: number;
  error?: string;
}

export function getCodeViewEditableCeiling(format: CodeViewFormat): number {
  return CODE_VIEW_STREAMING_FORMATS.has(format) ? CODE_VIEW_STREAMING_CEILING_BYTES : CODE_VIEW_OWLAPI_CEILING_BYTES;
}

export function requestCodeViewPage(projectId: string, format: string, startLine: number) {
  return apiClient.get<CodeViewPageResponse>(`/api/ontology/${projectId}/content-page`, {
    format,
    startLine: String(startLine),
    lineCount: String(CODE_VIEW_PAGE_LINES),
  });
}

export function toCodeViewPage(response: CodeViewPageResponse, startLine: number): CodeViewPageWindow {
  return {
    startLine,
    lineCount: Number(response.lineCount) || 0,
    totalLines: Number(response.totalLines) || 0,
    totalBytes: Number(response.totalBytes) || 0,
  };
}
