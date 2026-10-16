import { useEffect, useRef } from 'react';
import { COMMITTED_CHANGE_EVENT, type CommittedChange } from '../utils/committedChange';

const DEBOUNCE_MS = 250;

export function useCommittedChangeRefresh(
  projectId: string | undefined,
  refresh: () => void,
  directions: ReadonlyArray<CommittedChange['direction']> = ['UNDO', 'REDO'],
): void {
  const latest = useRef(refresh);
  latest.current = refresh;
  const wanted = directions.join(',');

  useEffect(() => {
    if (!projectId) {
      return undefined;
    }
    let timer: ReturnType<typeof setTimeout> | undefined;
    const onChange = (event: Event) => {
      const change = (event as CustomEvent<CommittedChange>).detail;
      if (!change || change.projectId !== projectId || !wanted.split(',').includes(change.direction)) {
        return;
      }
      if (timer) {
        clearTimeout(timer);
      }
      timer = setTimeout(() => latest.current(), DEBOUNCE_MS);
    };
    window.addEventListener(COMMITTED_CHANGE_EVENT, onChange);
    return () => {
      window.removeEventListener(COMMITTED_CHANGE_EVENT, onChange);
      if (timer) {
        clearTimeout(timer);
      }
    };
  }, [projectId, wanted]);
}
