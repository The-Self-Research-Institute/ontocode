import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DesktopDownloadPage } from '../components/DesktopDownloadPage';

const release = (size: number) => ({ version: '1.2.6', size, filename: 'x' });
const info = {
  latest: {
    'windows-x64': release(1),
    'linux-x64': release(2),
    'mac-arm64': release(3),
    'mac-x64': release(4),
  },
};

let host: HTMLDivElement;
let root: Root;

async function mount() {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<DesktopDownloadPage />);
  });
  await act(async () => {
    await Promise.resolve();
  });
}

const buttons = () => Array.from(host.querySelectorAll('button'));
const byText = (text: string) => buttons().find((b) => b.textContent?.trim() === text);

describe('download page Mac tab', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
      ok: true,
      json: async () => (String(url).endsWith('/info') ? info : {}),
    })));
  });
  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.unstubAllGlobals();
  });

  it('shows one Mac tab, named like the Windows and Linux tabs, not one per processor', async () => {
    await mount();
    expect(buttons().filter((b) => /^(Mac|macOS)/.test(b.textContent?.trim() || '')).map((b) => b.textContent?.trim())).toEqual(['Mac']);
    expect(byText('Windows')).toBeTruthy();
    expect(byText('Linux')).toBeTruthy();
  });

  it('picks the processor inside the Mac tab', async () => {
    await mount();
    await act(async () => { byText('Mac')!.click(); });
    expect(byText('Apple Silicon')).toBeTruthy();
    expect(byText('Intel')).toBeTruthy();
    expect(host.textContent).toContain('Download for Mac (Apple Silicon)');
    await act(async () => { byText('Intel')!.click(); });
    expect(host.textContent).toContain('Download for Mac (Intel)');
  });
});
