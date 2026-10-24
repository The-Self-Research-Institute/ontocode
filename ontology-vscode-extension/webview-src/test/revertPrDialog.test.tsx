import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const postMock = vi.fn();
vi.mock('../services/apiClient', () => ({ default: { post: (...args: any[]) => postMock(...args) } }));

import RevertPrDialog from '../components/RevertPrDialog';
import { confirmRevert } from '../services/prRevertService';

function mount(node: React.ReactElement) {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = createRoot(host);
  act(() => root.render(node));
  return host;
}

const flush = () => act(async () => { await Promise.resolve(); await Promise.resolve(); });

describe('revert pull request dialog', () => {
  beforeEach(() => postMock.mockReset());

  it('previews on open, keeps Confirm off until a reason is typed, then sends the reason', async () => {
    postMock.mockResolvedValueOnce({ message: 'Reverting removes 2 changes', changeSets: [{ changeSetId: 'a', canRevert: true, willRemove: 2 }, { changeSetId: 'b', canRevert: false, reasons: ['Pizzanew is still used by Pizzakid'] }, { changeSetId: 'c', alreadyUndone: true }] });
    postMock.mockResolvedValueOnce({ message: 'Reverted' });
    const onDone = vi.fn();
    const host = mount(<RevertPrDialog projectId="p1" prId="7" title="Pizza" onClose={() => undefined} onDone={onDone} />);
    await flush();
    expect(postMock.mock.calls[0][0]).toBe('/api/ontology/p1/draft-prs/7/revert?dryRun=true');
    expect(host.textContent).toContain('Reverting removes 2 changes');
    expect(host.textContent).toContain('Can be reverted (2 changes)');
    expect(host.textContent).toContain('Blocked: Pizzanew is still used by Pizzakid');
    expect(host.textContent).toContain('Already undone');
    const confirm = Array.from(host.querySelectorAll('button')).find((b) => b.textContent === 'Confirm revert') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    const box = host.querySelector('textarea') as HTMLTextAreaElement;
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!;
    await act(async () => { setter.call(box, 'wrong order'); box.dispatchEvent(new Event('input', { bubbles: true })); });
    expect(confirm.disabled).toBe(false);
    await act(async () => { confirm.click(); });
    await flush();
    expect(postMock.mock.calls[1][0]).toBe('/api/ontology/p1/draft-prs/7/revert');
    expect(postMock.mock.calls[1][1].reason).toBe('wrong order');
    expect(onDone).toHaveBeenCalled();
  });

  it('shows the server message when the preview is refused', async () => {
    postMock.mockRejectedValueOnce({ data: { error: 'Pizzanew is still used by Pizzakid', changeSets: [] } });
    const host = mount(<RevertPrDialog projectId="p1" prId="7" onClose={() => undefined} onDone={() => undefined} />);
    await flush();
    expect(host.textContent).toContain('Pizzanew is still used by Pizzakid');
    const confirm = Array.from(host.querySelectorAll('button')).find((b) => b.textContent === 'Confirm revert') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
  });

  it('always sends the reason on the revert call', async () => {
    postMock.mockResolvedValueOnce({ message: 'Reverted' });
    await confirmRevert('p1', '7', '  because  ', { userId: 'u1', username: 'U One' });
    expect(postMock.mock.calls[0][1].reason).toBe('because');
  });
});
