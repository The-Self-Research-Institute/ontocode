export function readFlag(key: string): boolean | null {
  try {
    return window.localStorage.getItem(key) !== null;
  } catch {
    return null;
  }
}

export function writeFlag(key: string, on: boolean): void {
  try {
    if (on) window.localStorage.setItem(key, '1');
    else window.localStorage.removeItem(key);
  } catch {
    return;
  }
}
