import { readFlag, writeFlag } from './storage';

export const GUIDE_SEEN_KEY = 'ontocode.swrlGuideSeen';

export function markGuideSeen(): void {
  writeFlag(GUIDE_SEEN_KEY, true);
}

export function shouldAutoOpenGuide(opts: { loaded: boolean; failed: boolean; ruleCount: number }): boolean {
  if (!opts.loaded || opts.failed || opts.ruleCount > 0) return false;
  return readFlag(GUIDE_SEEN_KEY) === false;
}
