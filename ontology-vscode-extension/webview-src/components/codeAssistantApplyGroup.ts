import { applyEditGroup } from "../services/codeAssistantSession";
import { applyRemapResult, classifyApplyFailure, type ApplyOneResult } from "../services/codeAssistantApplyQueue";
import { errorSignalFrom, parseHttpStatus } from "../services/codeAssistantDeadEnd";
import { getApiBaseUrl, toFriendlyErrorMessage } from "./codeAssistantPanelHelpers";
import type { AppliedChanges } from "./codeAssistantChatEntries";
import type { CodeAssistantEntries } from "../hooks/useCodeAssistantEntries";

export interface ApplyGroupDeps {
  chat: CodeAssistantEntries;
  token: string | undefined;
  onApplied: (entryProjectId: string | undefined, changes: AppliedChanges) => void;
  noteRecoveryProblem: () => void;
}

function failureFrom(err: unknown) {
  const signal = errorSignalFrom(err, "Apply failed unexpectedly.");
  return classifyApplyFailure(
    signal.errorCode,
    signal.status ?? parseHttpStatus(signal.message),
    toFriendlyErrorMessage(signal.message),
  );
}

export async function applyReviewGroup(
  deps: ApplyGroupDeps,
  target: { entryId: string; sessionId: string; serverGroupId: string },
  deferredChanges?: AppliedChanges,
): Promise<ApplyOneResult> {
  const { chat } = deps;
  const { entryId, sessionId, serverGroupId } = target;
  const reviewEntry = chat.findReviewEntry(entryId);
  const appliedGroup = reviewEntry?.groups.find((g) => g.serverGroupId === serverGroupId);
  chat.updateReviewEntry(entryId, (e) => {
    const errors = { ...e.errors };
    delete errors[serverGroupId];
    return { ...e, decisions: { ...e.decisions, [serverGroupId]: "applying" }, errors };
  });
  const applyStartedAt = performance.now();
  try {
    const result = await applyEditGroup(getApiBaseUrl(), deps.token, sessionId, serverGroupId);
    console.log(
      `[CodeAssistant] [PERF] apply group=${serverGroupId} response=${Math.round(performance.now() - applyStartedAt)}ms ` +
        `siblings=${result.remappedPendingGroups?.length ?? 0}`,
    );
    chat.updateReviewEntry(entryId, (e) => ({
      ...e,
      decisions: applyRemapResult(e.decisions, serverGroupId, result.remappedPendingGroups ?? []),
    }));
    const changes: AppliedChanges = { texts: appliedGroup?.diff.map((d) => d.after) ?? [], ranges: result.appliedRanges ?? [] };
    if (deferredChanges) {
      deferredChanges.texts.push(...changes.texts);
      deferredChanges.ranges.push(...changes.ranges);
    } else {
      deps.onApplied(reviewEntry?.projectId, changes);
    }
    return { ok: true };
  } catch (err) {
    const failure = failureFrom(err);
    chat.updateReviewEntry(entryId, (e) => ({
      ...e,
      decisions: { ...e.decisions, [serverGroupId]: failure.decision },
      errors: { ...e.errors, [serverGroupId]: failure.message },
    }));
    if (failure.checkRecovery) deps.noteRecoveryProblem();
    return { ok: false, failure };
  }
}
