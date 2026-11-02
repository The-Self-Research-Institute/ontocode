export function isMacPlatform(): boolean {
  return typeof navigator !== 'undefined' && /Mac|iPhone|iPod|iPad/i.test(navigator.platform);
}

export function undoShortcutText(mac: boolean = isMacPlatform()): string {
  return mac ? 'Cmd+Z' : 'Ctrl+Z';
}

export function redoShortcutText(mac: boolean = isMacPlatform()): string {
  return mac ? 'Cmd+Shift+Z' : 'Ctrl+Y / Ctrl+Shift+Z';
}
