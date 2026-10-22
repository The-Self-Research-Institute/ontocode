import { useCallback, useEffect, useRef } from 'react';

export function useLastCallTimer(): (run: () => void, delayMs: number) => void {
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(
    () => () => {
      if (timer.current) {
        clearTimeout(timer.current);
      }
    },
    [],
  );

  return useCallback((run: () => void, delayMs: number) => {
    if (timer.current) {
      clearTimeout(timer.current);
    }
    timer.current = setTimeout(run, delayMs);
  }, []);
}
