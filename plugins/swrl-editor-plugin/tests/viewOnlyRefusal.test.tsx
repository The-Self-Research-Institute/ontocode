import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

if (typeof window === 'undefined') {
  (globalThis as any).window = new EventTarget();
  (globalThis as any).CustomEvent = class extends Event {
    detail: unknown;
    constructor(type: string, init?: { detail?: unknown }) {
      super(type);
      this.detail = init?.detail;
    }
  };
}

const { announceViewOnlyBlocked, friendlyRuleError, isEditRefused, VIEW_ONLY_BLOCKED_EVENT } = await import('../src/ruleModel');

describe('refusals for accounts that may run rules but not edit', () => {
  const heard = vi.fn();

  beforeEach(() => {
    heard.mockReset();
    window.addEventListener(VIEW_ONLY_BLOCKED_EVENT, heard);
  });

  afterEach(() => window.removeEventListener(VIEW_ONLY_BLOCKED_EVENT, heard));

  it('recognises the free-plan and view-only refusals', () => {
    expect(isEditRefused({ status: 403, data: { requiresUpgrade: true } })).toBe(true);
    expect(isEditRefused({ status: 403, data: { viewOnly: true } })).toBe(true);
  });

  it('does not treat other failures as an edit refusal', () => {
    expect(isEditRefused({ status: 403, data: { code: 'PUBLIC_EDIT_DISABLED' } })).toBe(false);
    expect(isEditRefused({ status: 409, data: { viewOnly: true } })).toBe(false);
    expect(isEditRefused(new Error('boom'))).toBe(false);
    expect(isEditRefused(undefined)).toBe(false);
  });

  it('explains the refusal in plain words instead of a bare failure', () => {
    const text = friendlyRuleError({ status: 403, data: { requiresUpgrade: true } }, 'Execution failed');
    expect(text).toMatch(/can run rules but cannot change this ontology/);
  });

  it('tells the page to open the view-only dialog', () => {
    announceViewOnlyBlocked();
    expect(heard).toHaveBeenCalledTimes(1);
  });
});
