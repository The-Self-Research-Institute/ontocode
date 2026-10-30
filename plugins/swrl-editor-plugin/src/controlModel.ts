import type { TransferSummary } from './ruleModel';

const formatCount = (n: number): string => Math.max(0, Math.trunc(n || 0)).toLocaleString('en-US');

export function plural(n: number, one: string, many: string): string {
  return `${formatCount(n)} ${n === 1 ? one : many}`;
}

export function runButtonLabel(selected: number): string {
  return selected > 0 ? `Run ${plural(selected, 'selected rule', 'selected rules')}` : 'Run rules';
}

export function scopeLine(runnable: number, selected: number): { head: string; note: string } {
  if (runnable === 0) return { head: 'No rules to run', note: '' };
  if (selected > 0) return { head: `${plural(selected, 'rule', 'rules')} will run`, note: '(selected rules)' };
  return { head: `${plural(runnable, 'rule', 'rules')} will run`, note: '(all enabled rules)' };
}

export function factsFoundText(n: number): string {
  return `${plural(n, 'new fact', 'new facts')} found`;
}

export function addButtonLabel(n: number): string {
  return `Add ${plural(n, 'fact', 'facts')} to the ontology…`;
}

export function checkSummaryText(summary: TransferSummary): string {
  return `Sends ${plural(summary.rulesExported, 'rule', 'rules')}, ${plural(summary.classDeclarations, 'class', 'classes')}, ${plural(summary.individualDeclarations, 'individual', 'individuals')}, ${plural(summary.totalAxioms, 'axiom', 'axioms')}`;
}

export function durationText(ms: number | undefined): string {
  const n = Math.max(0, ms ?? 0);
  if (n < 1000) return `${formatCount(n)} ms`;
  return `${(n / 1000).toFixed(1)} s`;
}

export function runTailText(rulesRun: number, ms: number | undefined, when: string): string {
  const base = `in ${durationText(ms)}, from ${plural(rulesRun, 'rule', 'rules')}`;
  return when ? `${base}, ${when}` : base;
}

export type RunTone = 'ok' | 'warn' | 'fail';

export function runTone(success: boolean | undefined, skippedCount: number): RunTone {
  if (!success) return 'fail';
  return skippedCount > 0 ? 'warn' : 'ok';
}

export function addDisabledReason(opts: { hasResult: boolean; success: boolean; test: boolean; total: number; alreadyAdded: boolean }): string {
  if (!opts.hasResult) return '';
  if (!opts.success) return 'The run did not finish, so there is nothing to add.';
  if (opts.test) return 'A test run is not added to the ontology.';
  if (opts.total === 0) return 'This run found no new facts to add.';
  if (opts.alreadyAdded) return 'These facts are already in the ontology.';
  return '';
}
