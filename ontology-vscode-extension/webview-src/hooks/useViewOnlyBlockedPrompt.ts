import { useEffect } from 'react';

export const VIEW_ONLY_BLOCKED_EVENT = 'ontocode:view-only-blocked';

export function useViewOnlyBlockedPrompt(show: (type: 'edit' | 'viewer') => void, isProjectViewer: boolean): void {
  useEffect(() => {
    const onBlocked = () => show(isProjectViewer ? 'viewer' : 'edit');
    window.addEventListener(VIEW_ONLY_BLOCKED_EVENT, onBlocked);
    return () => window.removeEventListener(VIEW_ONLY_BLOCKED_EVENT, onBlocked);
  }, [show, isProjectViewer]);
}
