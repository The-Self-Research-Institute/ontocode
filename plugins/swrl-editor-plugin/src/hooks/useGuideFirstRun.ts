import { useCallback, useEffect, useRef, useState } from 'react';
import { markGuideSeen, shouldAutoOpenGuide } from '../guideStorage';

export function useGuideFirstRun(opts: { loading: boolean; failed: boolean; ruleCount: number }) {
  const [open, setOpen] = useState(false);
  const [card, setCard] = useState('');
  const checkedRef = useRef(false);

  useEffect(() => {
    if (checkedRef.current || opts.loading) return;
    checkedRef.current = true;
    if (shouldAutoOpenGuide({ loaded: true, failed: opts.failed, ruleCount: opts.ruleCount })) setOpen(true);
  }, [opts.loading, opts.failed, opts.ruleCount]);

  const show = useCallback((target = '') => {
    setCard(target);
    setOpen(true);
  }, []);

  const close = useCallback(() => {
    markGuideSeen();
    setOpen(false);
    setCard('');
  }, []);

  return { open, card, show, close };
}
