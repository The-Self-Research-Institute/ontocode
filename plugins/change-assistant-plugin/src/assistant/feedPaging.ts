export function nextFeedOffset(data: { nextOffset?: unknown; changes?: unknown[] }, requestedOffset: number): number {
  if (typeof data.nextOffset === 'number') return data.nextOffset;
  return requestedOffset + (Array.isArray(data.changes) ? data.changes.length : 0);
}
