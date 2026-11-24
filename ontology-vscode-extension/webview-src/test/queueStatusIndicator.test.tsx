import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueueStatusIndicator } from '../components/QueueStatusIndicator';

const queue = vi.hoisted(() => ({ answer: {} as Record<string, unknown> }));

vi.mock('../services/apiClient', () => ({
  default: {
    get: () => Promise.resolve(queue.answer),
  },
}));

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

async function settle(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('QueueStatusIndicator', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
    vi.useRealTimers();
  });

  it('shows the real position and hides the card once the server says the file left the queue', async () => {
    queue.answer = { inQueue: true, status: 'QUEUED', position: 2, totalInQueue: 3, estimatedWaitMs: 0 };
    act(() => root.render(<QueueStatusIndicator projectId="p1" visible />));
    await settle();

    expect(container.textContent).toContain('Position #2 in Queue');
    expect(container.textContent).toContain('1 file ahead of you');
    expect(container.textContent).not.toContain('NaN');

    queue.answer = { inQueue: false };
    await settle(3000);

    expect(container.textContent).toBe('');
  });

  it('shows nothing when the project was never in the queue', async () => {
    queue.answer = { inQueue: false };
    act(() => root.render(<QueueStatusIndicator projectId="p1" visible />));
    await settle(3000);

    expect(container.textContent).toBe('');
  });
});
