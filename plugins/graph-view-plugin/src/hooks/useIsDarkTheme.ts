import { useSyncExternalStore } from 'react';

function subscribe(onStoreChange: () => void): () => void {
  const observer = new MutationObserver(onStoreChange);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  return () => observer.disconnect();
}

function getSnapshot(): boolean {
  const classList = document.documentElement.classList;
  // Webapp/Electron use `dark`; VSCode webviews stamp `vscode-dark`.
  return classList.contains('dark') || classList.contains('vscode-dark');
}

/** Reactive dark-theme flag, so D3 renders recolor on theme switch without a data reload. */
export function useIsDarkTheme(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, () => false);
}
