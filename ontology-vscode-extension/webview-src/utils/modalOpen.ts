export const MODAL_SELECTOR = '[role="dialog"], [aria-modal="true"]';

export function isModalOpen(): boolean {
  return typeof document !== 'undefined' && !!document.querySelector(MODAL_SELECTOR);
}
