import { useEffect, useRef, type MutableRefObject } from "react";
import { compactEntryForStorage, saveStoredChatEntries } from "./codeAssistantPanelHelpers";

const CHAT_SAVE_DEBOUNCE_MS = 300;

export function useDebouncedChatSave<T>(
  projectIdRef: MutableRefObject<string | undefined>,
  entries: T[],
  sanitize: (entry: T) => T,
): () => void {
  const pendingRef = useRef<{ projectId: string; entries: T[] } | null>(null);
  const sanitizeRef = useRef(sanitize);
  sanitizeRef.current = sanitize;
  const flushRef = useRef(() => {
    const pending = pendingRef.current;
    if (!pending) return;
    pendingRef.current = null;
    saveStoredChatEntries(pending.projectId, pending.entries.map((e) => compactEntryForStorage(sanitizeRef.current(e))));
  });

  useEffect(() => {
    const projectId = projectIdRef.current;
    if (!projectId) return;
    pendingRef.current = { projectId, entries };
    const timer = setTimeout(flushRef.current, CHAT_SAVE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [entries, projectIdRef]);

  useEffect(() => () => flushRef.current(), []);

  return flushRef.current;
}
