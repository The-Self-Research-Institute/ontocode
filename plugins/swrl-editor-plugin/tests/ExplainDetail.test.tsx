import React from 'react';
import { createRoot, Root } from 'react-dom/client';
import { act } from 'react-dom/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
  explainInference: vi.fn(),
}));

vi.mock('../src/apiClient', () => ({ default: api }));

import { ExplainPanel } from '../src/ExplainPanel';
import { ExplainDetail } from '../src/ExplainDetail';

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

const NS = 'http://example.org/p#';
const axiom = {
  axiomType: 'ClassAssertion',
  description: 'd',
  readable: 'r',
  category: 'conclusion' as const,
  subjectIri: `${NS}ann`,
  predicateIri: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type',
  objectIri: `${NS}Person`,
};

const entry = (over: Record<string, unknown>) => ({
  axiomType: 'ClassAssertion', role: 'ASSERTED', text: '', subjectIri: null, predicateIri: null, objectIri: null,
  objectLiteral: null, literalDatatypeIri: null, objectIris: null, objectOperator: null, entities: [], ...over,
});

let host: HTMLDivElement;
let root: Root;

const render = async (node: React.ReactNode) => {
  await act(async () => {
    root.render(node);
  });
};
const text = () => host.textContent || '';
const button = (label: string) => Array.from(host.querySelectorAll('button')).find(b => b.textContent === label) as HTMLButtonElement | undefined;
const click = async (label: string) => {
  await act(async () => {
    button(label)!.click();
  });
};
const flush = async () => {
  await act(async () => {
    await Promise.resolve();
  });
};

const deferred = () => {
  let resolve!: (v: any) => void;
  let reject!: (e: any) => void;
  const promise = new Promise<any>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

beforeEach(() => {
  api.explainInference.mockReset();
  api.post.mockReset();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(async () => {
  await act(async () => {
    root.unmount();
  });
  host.remove();
});

describe('Explain in detail', () => {
  it('shows the sentence and one button for a fact no rule produced', async () => {
    await render(<ExplainPanel projectId="p1" axiom={axiom} onClose={() => {}} ruleless />);
    expect(text()).toContain('None of your rules produced this fact');
    expect(api.post).not.toHaveBeenCalled();
    expect(host.querySelectorAll('button')).toHaveLength(2);
    expect(button('Explain in detail')).toBeTruthy();
  });

  it('also offers the button when the rule check finds no rule', async () => {
    api.post.mockResolvedValue({ status: 'origin_only', notes: [] });
    await render(<ExplainPanel projectId="p1" axiom={axiom} onClose={() => {}} />);
    await flush();
    expect(button('Explain in detail')).toBeTruthy();
  });

  it('does not offer the button when the fact is already in the ontology', async () => {
    api.post.mockResolvedValue({ status: 'origin_only', notes: ['Already in the ontology (asserted).'] });
    await render(<ExplainPanel projectId="p1" axiom={axiom} onClose={() => {}} />);
    await flush();
    expect(button('Explain in detail')).toBeUndefined();
  });

  it('does not offer the button for a fact the server cannot explain', async () => {
    await render(<ExplainDetail projectId="p1" axiom={{ ...axiom, axiomType: 'DataPropertyDomain' }} />);
    expect(host.querySelectorAll('button')).toHaveLength(0);
  });

  it('sends the mapped fact with the draft flag and shows progress with a live region', async () => {
    const d = deferred();
    api.explainInference.mockReturnValue(d.promise);
    await render(<ExplainDetail projectId="p1" axiom={axiom} draftMode />);
    await click('Explain in detail');
    expect(api.explainInference).toHaveBeenCalledTimes(1);
    const [project, fact, options] = api.explainInference.mock.calls[0];
    expect(project).toBe('p1');
    expect(fact).toMatchObject({ axiomType: 'ClassAssertion', subjectIri: axiom.subjectIri, objectIri: axiom.objectIri });
    expect(options.draft).toBe(true);
    expect(options.signal).toBeInstanceOf(AbortSignal);
    expect(text()).toContain('Working it out… (up to 30 seconds)');
    expect(button('Cancel')).toBeTruthy();
    expect(button('Explain in detail')).toBeUndefined();
    expect(host.querySelector('[aria-live="polite"]')).toBeTruthy();
  });

  it('lists the reasons as a numbered list', async () => {
    api.explainInference.mockResolvedValue({
      status: 'COMPLETED',
      explainStatus: 'EXPLAINED',
      partial: true,
      justification: [
        entry({ subjectIri: `${NS}ann`, objectIri: `${NS}Customer` }),
        entry({ axiomType: 'SubClassOf', subjectIri: `${NS}Customer`, objectIri: `${NS}Person` }),
        entry({ axiomType: 'Rule', role: 'RULE', text: 'Customer(?c) -> Person(?c)' }),
      ],
    });
    await render(<ExplainDetail projectId="p1" axiom={axiom} />);
    await click('Explain in detail');
    await flush();
    const items = Array.from(host.querySelectorAll('ol li')).map(li => li.textContent);
    expect(items).toEqual(['ann is a Customer', 'Customer is a kind of Person', 'rule: Customer(?c) -> Person(?c)']);
    expect(text()).toContain('This is true because:');
    expect(text()).toContain('This may not be the shortest explanation (time ran out).');
  });

  it.each([
    ['ASSERTED', {}, 'This fact is stated directly in the ontology.'],
    ['NOT_ENTAILED', { message: 'The reasoner does not conclude this.' }, 'The reasoner does not conclude this.'],
    ['TOO_LARGE', {}, 'This ontology is too large to explain this fact in detail.'],
    ['TIMEOUT', {}, 'Working this out took too long, so it was stopped. Try again, or try a simpler fact.'],
    ['UNSUPPORTED', {}, 'Explaining this kind of fact in detail is not supported yet.'],
    ['FAILED', {}, 'The explanation could not be worked out. Please try again.'],
  ])('reads %s in plain words', async (explainStatus, extra, expected) => {
    api.explainInference.mockResolvedValue({ status: 'COMPLETED', explainStatus, ...extra });
    await render(<ExplainDetail projectId="p1" axiom={axiom} />);
    await click('Explain in detail');
    await flush();
    expect(text()).toContain(expected);
    expect(text()).not.toContain(explainStatus as string);
  });

  it('says when the service is busy and lets the user try again', async () => {
    api.explainInference.mockRejectedValueOnce(Object.assign(new Error('busy'), { status: 429, data: { busy: true, retryAfterSeconds: 7 } }));
    await render(<ExplainDetail projectId="p1" axiom={axiom} />);
    await click('Explain in detail');
    await flush();
    expect(text()).toContain('The explanation service is busy right now. Try again in 7 seconds.');
    expect(button('Explain in detail')).toBeTruthy();
  });

  it('hides the button when no reasoning service is available', async () => {
    api.explainInference.mockRejectedValue(Object.assign(new Error('x'), { status: 503, data: { explainStatus: 'UNAVAILABLE' } }));
    await render(<ExplainPanel projectId="p1" axiom={axiom} onClose={() => {}} ruleless />);
    await click('Explain in detail');
    await flush();
    expect(button('Explain in detail')).toBeUndefined();
    expect(text()).toContain('None of your rules produced this fact');
    expect(text()).not.toContain('503');
  });

  it('cancels the running job when the user presses Cancel', async () => {
    const d = deferred();
    api.explainInference.mockReturnValue(d.promise);
    await render(<ExplainDetail projectId="p1" axiom={axiom} />);
    await click('Explain in detail');
    const signal: AbortSignal = api.explainInference.mock.calls[0][2].signal;
    await click('Cancel');
    expect(signal.aborted).toBe(true);
    expect(text()).toContain('The explanation was cancelled.');
    expect(button('Explain in detail')).toBeTruthy();
  });

  it('cancels the job when the dialog is closed', async () => {
    const d = deferred();
    api.explainInference.mockReturnValue(d.promise);
    await render(<ExplainPanel projectId="p1" axiom={axiom} onClose={() => {}} ruleless />);
    await click('Explain in detail');
    const signal: AbortSignal = api.explainInference.mock.calls[0][2].signal;
    await render(<div />);
    expect(signal.aborted).toBe(true);
  });

  it('does not touch state after it is unmounted', async () => {
    const d = deferred();
    api.explainInference.mockReturnValue(d.promise);
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    await render(<ExplainDetail projectId="p1" axiom={axiom} />);
    await click('Explain in detail');
    await render(<div />);
    await act(async () => {
      d.resolve({ status: 'COMPLETED', explainStatus: 'ASSERTED' });
      await d.promise;
    });
    expect(errors).not.toHaveBeenCalled();
    expect(text()).toBe('');
    errors.mockRestore();
  });

  it('ignores an abort error from a cancelled run', async () => {
    api.explainInference.mockRejectedValue(new DOMException('cancelled', 'AbortError'));
    await render(<ExplainDetail projectId="p1" axiom={axiom} />);
    await click('Explain in detail');
    await flush();
    expect(text()).not.toContain('could not be worked out');
  });
});
