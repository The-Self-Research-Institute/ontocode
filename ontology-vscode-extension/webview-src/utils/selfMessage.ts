export function postToSelf(data: Record<string, any>): void {
  window.dispatchEvent(new MessageEvent('message', { data, source: window, origin: window.location.origin }));
}
