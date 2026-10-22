import { useEffect, useRef } from 'react';

const EVENTS = ['ontologyRollback', 'ontocode:committed-change'];

export function useUndoRefresh(projectId: string | undefined, refresh: () => void, delayMs: number = 400): void {
  const latest = useRef(refresh);
  latest.current = refresh;

  useEffect(() => {
    if (!projectId) {return undefined;}
    let timer: ReturnType<typeof setTimeout> | undefined;
    const onEvent = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      if (!detail || detail.projectId !== projectId || detail.direction === 'APPLY') {return;}
      if (timer) {clearTimeout(timer);}
      timer = setTimeout(() => latest.current(), delayMs);
    };
    EVENTS.forEach((name) => window.addEventListener(name, onEvent));
    return () => {
      EVENTS.forEach((name) => window.removeEventListener(name, onEvent));
      if (timer) {clearTimeout(timer);}
    };
  }, [projectId, delayMs]);
}
