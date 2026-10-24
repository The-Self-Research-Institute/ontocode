export const MODAL_SELECTOR = '[role="dialog"], [aria-modal="true"]';

function showing(element: Element): boolean {
  if ((element as HTMLElement).hidden) return false;
  const style = window.getComputedStyle(element);
  return style.display !== 'none' && style.visibility !== 'hidden';
}

export function isModalOpen(): boolean {
  return typeof document !== 'undefined' && Array.from(document.querySelectorAll(MODAL_SELECTOR)).some(showing);
}
