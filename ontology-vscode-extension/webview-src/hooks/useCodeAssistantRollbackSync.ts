import { useEffect, useRef } from "react";
import type { CodeAssistantEntries } from "./useCodeAssistantEntries";

export function useCodeAssistantRollbackSync(chat: CodeAssistantEntries) {
  const chatRef = useRef(chat);
  chatRef.current = chat;

  useEffect(() => {
    const onRollback = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      const groupId: string | undefined = detail?.changeSetId;
      const undone = detail?.direction === "UNDO";
      if (!groupId || (!undone && detail?.direction !== "REDO")) {
        return;
      }
      const current = chatRef.current;
      current.entriesRef.current.forEach((entry) => {
        if (entry.role !== "assistant" || entry.kind !== "review") {
          return;
        }
        if (detail.projectId && entry.projectId && detail.projectId !== entry.projectId) {
          return;
        }
        if (!entry.groups.some((g) => g.serverGroupId === groupId) || (entry.undo?.[groupId]?.undone ?? false) === undone) {
          return;
        }
        current.updateReviewEntry(entry.id, (review) => ({
          ...review,
          undo: { ...review.undo, [groupId]: { undone, busy: false, preview: null, error: null } },
        }));
      });
    };
    window.addEventListener("ontologyRollback", onRollback);
    return () => window.removeEventListener("ontologyRollback", onRollback);
  }, []);
}
