import { isDesktop } from './desktop';

function isEmbeddedInHost(): boolean {
  return typeof window !== 'undefined' && window.parent !== window;
}

function isVsCodeWebview(): boolean {
  return typeof window !== 'undefined' && !isDesktop() && !!window.vscode && !(window as any).__ONTOCODE_BROWSER_BRIDGE__;
}

function isWebPageOrigin(event: MessageEvent): boolean {
  return /^https?:/i.test(event.origin || '') && event.origin !== window.location.origin;
}

function isFromVsCodeHost(event: MessageEvent): boolean {
  if (isEmbeddedInHost() && event.source === window.parent) return true;
  return isVsCodeWebview() && !isWebPageOrigin(event);
}

function isFromThisWindow(event: MessageEvent): boolean {
  return event.source === window && event.origin === window.location.origin;
}

export function hasHostChannel(): boolean {
  if (typeof window === 'undefined') return false;
  if (isDesktop()) return true;
  return isVsCodeWebview();
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
