let sessionStartMs: number | undefined;

function elapsedSincePageLoad(): number {
  return typeof performance !== 'undefined' ? performance.now() : 0;
}

export function noteServerTime(serverTime?: string): void {
  if (sessionStartMs !== undefined || !serverTime) return;
  const server = Date.parse(serverTime);
  if (Number.isFinite(server)) sessionStartMs = server - elapsedSincePageLoad();
}

export function isEarlierSession(changeTime?: string): boolean {
  if (sessionStartMs === undefined || !changeTime) return false;
  const changed = Date.parse(changeTime);
  return Number.isFinite(changed) && changed < sessionStartMs;
}

export function setSessionStartForTest(startMs: number | undefined): void {
  sessionStartMs = startMs;
}
