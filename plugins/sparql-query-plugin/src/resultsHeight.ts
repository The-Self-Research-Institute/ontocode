export const MIN_RESULTS_HEIGHT = 200;

export function nextResultsHeight(clientY: number, panelTop: number, containerHeight: number, min: number = MIN_RESULTS_HEIGHT): number {
  const max = Math.max(min, containerHeight);
  const wanted = clientY - panelTop;
  if (!Number.isFinite(wanted)) return max;
  return Math.min(max, Math.max(min, Math.round(wanted)));
}
