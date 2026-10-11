import ontologyMutationService from "../services/ontologyMutationService";
import { isDesktop } from "./desktop";
import { resolveMutationActor } from "./mutationActor";

export function isDraftScopeActive(): boolean {
  return !isDesktop() && ontologyMutationService.isPrivateEditMode();
}

export function withDraftScope(url: string): string {
  if (!isDraftScopeActive()) return url;
  return url + (url.includes("?") ? "&draft=true" : "?draft=true");
}

/**
 * Like withDraftScope but also appends userId — required by the ontology-metadata WRITE
 * endpoints (annotations/imports/iri) to scope the write to the caller's draft graph. Reads
 * can rely on the header userId, but these writes read userId explicitly from the request.
 */
export function withDraftAndUser(url: string): string {
  if (!isDraftScopeActive()) return url;
  const uid = resolveMutationActor().userId;
  const sep = url.includes("?") ? "&" : "?";
  return `${url}${sep}draft=true&userId=${encodeURIComponent(uid || "")}`;
}
