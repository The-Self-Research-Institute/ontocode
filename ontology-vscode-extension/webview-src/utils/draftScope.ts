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

export function withDraftAndUser(url: string): string {
  if (!isDraftScopeActive()) return url;
  const uid = resolveMutationActor().userId;
  const sep = url.includes("?") ? "&" : "?";
  return `${url}${sep}draft=true&userId=${encodeURIComponent(uid || "")}`;
}

export function draftBodyFields(): Record<string, string> {
  if (!isDraftScopeActive()) return {};
  return { draft: "true", userId: resolveMutationActor().userId || "" };
}

export function draftControlsVisible(syncMode: string, desktop: boolean): boolean {
  return !desktop && syncMode === "private";
}
