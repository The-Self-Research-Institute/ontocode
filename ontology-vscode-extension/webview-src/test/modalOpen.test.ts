import { afterEach, describe, expect, it } from 'vitest';
import { isModalOpen } from '../utils/modalOpen';

function addDialog(attrs: Record<string, string>, style = ''): HTMLElement {
  const dialog = document.createElement('div');
  for (const [key, value] of Object.entries(attrs)) dialog.setAttribute(key, value);
  dialog.setAttribute('style', style);
  document.body.appendChild(dialog);
  return dialog;
}

describe('isModalOpen', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('is false with no dialog', () => {
    expect(isModalOpen()).toBe(false);
  });

  it('is true while a dialog is showing', () => {
    addDialog({ role: 'dialog' });
    expect(isModalOpen()).toBe(true);
  });

  it('ignores a dialog that is hidden', () => {
    addDialog({ role: 'dialog', 'aria-modal': 'true' }, 'display: none');
    const hiddenAttr = addDialog({ role: 'dialog' });
    hiddenAttr.hidden = true;
    expect(isModalOpen()).toBe(false);
  });
});
