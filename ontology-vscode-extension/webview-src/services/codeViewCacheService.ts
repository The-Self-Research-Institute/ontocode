import apiClient from "./apiClient";
import { draftBodyFields, withDraftScope } from "../utils/draftScope";

export interface CodeViewCacheEntry {
  content: string;
  format: string;
  citationUrn?: string;
  referencedEntity?: string;
}

function cacheUrl(projectId: string): string {
  return `/api/ontology/${projectId}/code-view-cache`;
}

export const codeViewCacheService = {
  store(projectId: string, entry: CodeViewCacheEntry, scope = draftBodyFields()) {
    return apiClient.post(cacheUrl(projectId), { ...entry, ...scope });
  },

  clear(projectId: string) {
    return apiClient.delete(withDraftScope(cacheUrl(projectId)));
  },
};

export default codeViewCacheService;
