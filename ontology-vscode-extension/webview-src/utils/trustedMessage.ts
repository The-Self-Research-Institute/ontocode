import { isDesktop } from './desktop';

function isEmbeddedInHost(): boolean {
  return typeof window !== 'undefined' && window.parent !== window;
}

function isFromVsCodeHost(event: MessageEvent): boolean {
  return isEmbeddedInHost() && event.source === window.parent;
}

function isFromThisWindow(event: MessageEvent): boolean {
  return event.source === window && event.origin === window.location.origin;
}

export function hasHostChannel(): boolean {
  if (typeof window === 'undefined') return false;
  if (isDesktop()) return true;
  return !!window.vscode && isEmbeddedInHost() && !(window as any).__ONTOCODE_BROWSER_BRIDGE__;
}

export function isTrustedMessage(event: MessageEvent): boolean {
  return isFromVsCodeHost(event) || isFromThisWindow(event);
}

export function isTrustedHostMessage(event: MessageEvent): boolean {
  if (!hasHostChannel()) return false;
  return isDesktop() ? isTrustedMessage(event) : isFromVsCodeHost(event);
}

export function isTrustedAppMessage(event: MessageEvent): boolean {
  if (event.data?.type === 'storedAuthToken') return isTrustedHostMessage(event);
  return isTrustedMessage(event);
}
