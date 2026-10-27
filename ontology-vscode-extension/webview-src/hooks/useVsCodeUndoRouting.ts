import { useEffect } from 'react';
import { isRealVSCode } from '../utils/desktop';
import { isModalOpen } from '../utils/modalOpen';

const TEXT_SELECTOR = 'input, textarea, select, [contenteditable="true"], [contenteditable=""], .monaco-editor, .cm-editor';

function ontologyHasFocus(): boolean {
  if (typeof document === 'undefined' || !document.hasFocus()) {
    return false;
  }
  const active = document.activeElement as HTMLElement | null;
  if (active && active !== document.body && active.closest(TEXT_SELECTOR)) {
    return false;
  }
  return !isModalOpen();
}

interface Handlers {
  onUndo: () => void;
  onRedo: () => void;
}

export function useVsCodeUndoRouting({ onUndo, onRedo }: Handlers): void {
  useEffect(() => {
    if (!isRealVSCode()) {
      return;
    }
    const api = (window as unknown as { vscode?: { postMessage: (message: unknown) => void } }).vscode;
    let last: boolean | undefined;
    const report = () => {
      const next = ontologyHasFocus();
      if (next !== last) {
        last = next;
        api?.postMessage({ type: 'focusContext', ontology: next });
      }
    };
    const reportSoon = () => window.setTimeout(report, 0);
    const onMessage = (event: MessageEvent) => {
      const type = (event.data as { type?: string } | null)?.type;
      if (type === 'ontologyUndo') {
        onUndo();
      } else if (type === 'ontologyRedo') {
        onRedo();
      }
    };
    const observer = new MutationObserver(reportSoon);
    observer.observe(document.body, { childList: true, subtree: true });
    document.addEventListener('focusin', reportSoon);
    document.addEventListener('focusout', reportSoon);
    window.addEventListener('focus', reportSoon);
    window.addEventListener('blur', reportSoon);
    window.addEventListener('message', onMessage);
    report();
    return () => {
      observer.disconnect();
      document.removeEventListener('focusin', reportSoon);
      document.removeEventListener('focusout', reportSoon);
      window.removeEventListener('focus', reportSoon);
      window.removeEventListener('blur', reportSoon);
      window.removeEventListener('message', onMessage);
      api?.postMessage({ type: 'focusContext', ontology: false });
    };
  }, [onUndo, onRedo]);
}
