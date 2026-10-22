export function withDraft(path: string, draftMode: boolean): string {
  if (!draftMode) {return path;}
  return path + (path.includes('?') ? '&draft=true' : '?draft=true');
}
