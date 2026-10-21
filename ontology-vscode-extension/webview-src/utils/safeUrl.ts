const SAFE_PROTOCOLS = ['https:', 'http:', 'mailto:'];

export function isSafeUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  try {
    return SAFE_PROTOCOLS.includes(new URL(value.trim()).protocol);
  } catch {
    return false;
  }
}

export function safeHref(value: unknown): string {
  return isSafeUrl(value) ? value.trim() : '#';
}
