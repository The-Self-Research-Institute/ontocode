const NON_TEXT_INPUTS = new Set(['button', 'checkbox', 'radio', 'submit', 'range', 'color', 'file', 'image', 'reset']);
const MENU_SELECTOR = '.ontocode-top-menu-button, .ontocode-top-menu-dropdown';

let lastText: HTMLElement | null = null;

export function isTextElement(element: Element | null): element is HTMLElement {
  if (!element) {
    return false;
  }
  const html = element as HTMLElement;
  if (html.tagName === 'INPUT') {
    return !NON_TEXT_INPUTS.has((html as HTMLInputElement).type);
  }
  return html.tagName === 'TEXTAREA' || html.isContentEditable;
}

export function currentTextTarget(): HTMLElement | null {
  if (typeof document === 'undefined') {
    return null;
  }
  const active = document.activeElement;
  if (isTextElement(active)) {
    return active;
  }
  return lastText && lastText.isConnected ? lastText : null;
}

export function undoTextEdit(): boolean {
  const target = currentTextTarget();
  if (!target) {
    return false;
  }
  target.focus();
  return document.execCommand('undo');
}

if (typeof document !== 'undefined') {
  document.addEventListener('focusin', (event) => {
    const element = event.target as Element | null;
    if (isTextElement(element)) {
      lastText = element;
    } else if (element && !element.closest(MENU_SELECTOR)) {
      lastText = null;
    }
  });
}
