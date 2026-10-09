export function isSaveDisabledInDraft(syncMode: string, desktop: boolean): boolean {
  return !desktop && syncMode === "private";
}
