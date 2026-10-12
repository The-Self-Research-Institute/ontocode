import { useCallback, useEffect, useState } from 'react';
import { runOperation } from './changeSetApi';
import { errorMessage } from './previewText';
import { OperationOutcome, OperationResponse, OperationTarget } from './types';

export type PreviewPhase = 'loading' | 'ready' | 'applying' | 'failed';

export function useUndoPreview(
  projectId: string,
  target: OperationTarget,
  onApplied: (target: OperationTarget, data: OperationResponse) => void,
  onRefresh: () => void
) {
  const [phase, setPhase] = useState<PreviewPhase>('loading');
  const [outcome, setOutcome] = useState<OperationOutcome | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setPhase('loading');
    setError(null);
    runOperation(projectId, target, true).then(result => {
      if (!live) return;
      setOutcome(result);
      const message = result.status === 409 ? null : errorMessage(result, target.direction);
      setError(message);
      setPhase(message ? 'failed' : 'ready');
    });
    return () => { live = false; };
  }, [projectId, target]);

  const apply = useCallback(async () => {
    setPhase('applying');
    setError(null);
    const result = await runOperation(projectId, target, false);
    if (result.data?.success && result.status < 400) {
      onApplied(target, result.data);
      return;
    }
    setOutcome(result);
    setError(errorMessage(result, target.direction) || 'Something went wrong. Try again.');
    setPhase('failed');
    if (result.status === 409) onRefresh();
  }, [projectId, target, onApplied, onRefresh]);

  return { phase, outcome, error, apply };
}
