import { useEffect, useRef, useState, type MutableRefObject } from "react";
import { loadStoredChatEntries, saveStoredChatEntries } from "../components/codeAssistantPanelHelpers";
import { useDebouncedChatSave } from "../components/useDebouncedChatSave";
import {
  nextEntryId,
  sanitizeEntryForStorage,
  type ChatEntry,
  type ReviewEntry,
} from "../components/codeAssistantChatEntries";

const SWITCHED_PROJECT_MESSAGE = "Cancelled because you switched to another project. Ask again to continue.";

function loadEntries(projectId: string | undefined): ChatEntry[] {
  return (projectId ? (loadStoredChatEntries<ChatEntry>(projectId) ?? []) : []).map(sanitizeEntryForStorage);
}

export function useCodeAssistantEntries(
  projectId: string | undefined,
  projectIdRef: MutableRefObject<string | undefined>,
  cancelRunFor: (previousProjectId: string | undefined) => boolean,
) {
  const [entries, setEntries] = useState<ChatEntry[]>(() => loadEntries(projectId));
  const [now, setNow] = useState(() => Date.now());
  const entriesRef = useRef<ChatEntry[]>(entries);
  const lastLoadedProjectIdRef = useRef<string | undefined>(projectId);
  const cancelRunForRef = useRef(cancelRunFor);
  cancelRunForRef.current = cancelRunFor;

  const commitEntries = (updater: (prev: ChatEntry[]) => ChatEntry[]) => {
    entriesRef.current = updater(entriesRef.current);
    setEntries(entriesRef.current);
  };

  const findReviewEntry = (entryId: string): ReviewEntry | undefined => {
    const found = entriesRef.current.find((e) => e.id === entryId);
    return found && found.role === "assistant" && found.kind === "review" ? found : undefined;
  };

  const updateReviewEntry = (entryId: string, updater: (entry: ReviewEntry) => ReviewEntry) => {
    commitEntries((prev) =>
      prev.map((e) => (e.id === entryId && e.role === "assistant" && e.kind === "review" ? updater(e) : e)),
    );
  };

  const appendError = (text: string) => {
    commitEntries((prev) => [...prev, { id: nextEntryId(), role: "assistant", kind: "error", text }]);
  };

  const flushPendingSave = useDebouncedChatSave(projectIdRef, entries, sanitizeEntryForStorage);

  useEffect(() => {
    if (!projectId || projectId === lastLoadedProjectIdRef.current) return;
    const previousProjectId = lastLoadedProjectIdRef.current;
    lastLoadedProjectIdRef.current = projectId;
    flushPendingSave();
    if (cancelRunForRef.current(previousProjectId) && previousProjectId) {
      const cancelled: ChatEntry = { id: nextEntryId(), role: "assistant", kind: "error", text: SWITCHED_PROJECT_MESSAGE };
      saveStoredChatEntries(previousProjectId, [...entriesRef.current, cancelled].map(sanitizeEntryForStorage));
    }
    const loaded = loadEntries(projectId);
    commitEntries(() => loaded);
  }, [projectId]);

  const lastEntry = entries.length > 0 ? entries[entries.length - 1] : undefined;
  const pendingRetryAt =
    lastEntry && lastEntry.role === "assistant" && lastEntry.kind === "error" && lastEntry.retryAt ? lastEntry.retryAt : null;
  useEffect(() => {
    if (pendingRetryAt === null || pendingRetryAt <= Date.now()) return;
    const timer = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= pendingRetryAt) clearInterval(timer);
    }, 1000);
    return () => clearInterval(timer);
  }, [pendingRetryAt]);

  return { entries, entriesRef, lastEntry, now, setNow, commitEntries, findReviewEntry, updateReviewEntry, appendError };
}

export type CodeAssistantEntries = ReturnType<typeof useCodeAssistantEntries>;
